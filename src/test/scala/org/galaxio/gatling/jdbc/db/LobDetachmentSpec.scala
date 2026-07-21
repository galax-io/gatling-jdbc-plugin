package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Regression spec for issue #87 (US5): large-object values are detached while the ResultSet is open — BLOB → Array[Byte],
  * CLOB/NCLOB → String, ARRAY → Vector — so checks read real content after the operation completes instead of dead locators.
  */
class LobDetachmentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dbName       = "lob_detachment"
  private val dataSource   = H2.dataSource(dbName, 2)
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def beforeAll(): Unit = {
    exec("CREATE TABLE IF NOT EXISTS lob_rows (id INT PRIMARY KEY, bin BLOB, txt CLOB, ntxt NCLOB, arr INTEGER ARRAY)")
    exec("DELETE FROM lob_rows")
    exec("INSERT INTO lob_rows VALUES (1, X'DEADBEEF', 'text-content', 'ntext-content', ARRAY[1, 2, 3])")
    exec("INSERT INTO lob_rows VALUES (2, NULL, NULL, NULL, NULL)")
    exec("INSERT INTO lob_rows VALUES (3, X'', '', '', ARRAY[])")
  }

  override def afterAll(): Unit =
    client.close()

  private def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  private def selectRow(id: Int): Map[String, Any] =
    Await
      .result(client.executeSelect(s"SELECT * FROM lob_rows WHERE id = $id", Seq.empty)(identity), 10.seconds)
      .success
      .value
      .head

  "executeSelect" should "detach a BLOB to a fully readable byte array after the operation completes" in {
    val row = selectRow(1)

    row("BIN") shouldBe a[Array[_]]
    row("BIN").asInstanceOf[Array[Byte]] shouldBe Array(0xde, 0xad, 0xbe, 0xef).map(_.toByte)
  }

  it should "detach a CLOB to an ordinary String" in {
    selectRow(1)("TXT") shouldBe "text-content"
  }

  it should "detach an NCLOB to an ordinary String" in {
    selectRow(1)("NTXT") shouldBe "ntext-content"
  }

  it should "detach an ARRAY to a Vector with usable elements" in {
    selectRow(1)("ARR") shouldBe Vector(1, 2, 3)
  }

  it should "detach an array of LOB elements recursively" in {
    val rows = Await
      .result(client.executeSelect("SELECT ARRAY[CAST('deep' AS CLOB)] AS lob_arr", Seq.empty)(identity), 10.seconds)
      .success
      .value

    rows.head("LOB_ARR") shouldBe Vector("deep")
  }

  it should "map SQL NULL large objects to null" in {
    val row = selectRow(2)

    row("BIN").asInstanceOf[AnyRef] shouldBe null
    row("TXT").asInstanceOf[AnyRef] shouldBe null
    row("NTXT").asInstanceOf[AnyRef] shouldBe null
    row("ARR").asInstanceOf[AnyRef] shouldBe null
  }

  it should "map empty large objects to empty values, not errors" in {
    val row = selectRow(3)

    row("BIN").asInstanceOf[Array[Byte]] shouldBe empty
    row("TXT") shouldBe ""
    row("NTXT") shouldBe ""
    row("ARR") shouldBe Vector.empty
  }
}
