package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2ClientSpecFixture
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Regression spec for issue #87 (US5): large-object values are detached while the ResultSet is open — BLOB → Array[Byte],
  * CLOB/NCLOB → String, ARRAY → Vector — so checks read real content after the operation completes instead of dead locators.
  */
class LobDetachmentSpec extends AnyFlatSpec with Matchers with H2ClientSpecFixture {

  override protected val dbName = "lob_detachment"

  override def beforeAll(): Unit = {
    exec("CREATE TABLE IF NOT EXISTS lob_rows (id INT PRIMARY KEY, bin BLOB, txt CLOB, ntxt NCLOB, arr INTEGER ARRAY)")
    exec("DELETE FROM lob_rows")
    exec("INSERT INTO lob_rows VALUES (1, X'DEADBEEF', 'text-content', 'ntext-content', ARRAY[1, 2, 3])")
    exec("INSERT INTO lob_rows VALUES (2, NULL, NULL, NULL, NULL)")
    exec("INSERT INTO lob_rows VALUES (3, X'', '', '', ARRAY[])")
  }

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

  /** Fake locator for the free()-failure paths of `detach` — no real driver can be forced to fail `free` on demand. */
  private final class FakeBlob(failCopy: Boolean, failFree: Boolean) extends java.sql.Blob {
    var freeAttempted = false

    override def length(): Long                                = 4L
    override def getBytes(pos: Long, length: Int): Array[Byte] =
      if (failCopy) throw new RuntimeException("copy failure") else Array[Byte](1, 2, 3, 4)
    override def free(): Unit                                  = {
      freeAttempted = true
      if (failFree) throw new RuntimeException("free failure")
    }

    override def getBinaryStream: java.io.InputStream                                = throw new UnsupportedOperationException
    override def getBinaryStream(pos: Long, length: Long): java.io.InputStream       = throw new UnsupportedOperationException
    override def position(pattern: Array[Byte], start: Long): Long                   = throw new UnsupportedOperationException
    override def position(pattern: java.sql.Blob, start: Long): Long                 = throw new UnsupportedOperationException
    override def setBytes(pos: Long, bytes: Array[Byte]): Int                        = throw new UnsupportedOperationException
    override def setBytes(pos: Long, bytes: Array[Byte], offset: Int, len: Int): Int = throw new UnsupportedOperationException
    override def setBinaryStream(pos: Long): java.io.OutputStream                    = throw new UnsupportedOperationException
    override def truncate(len: Long): Unit                                           = throw new UnsupportedOperationException
  }

  "detach" should "suppress a free() failure onto the primary copy failure, never replacing it" in {
    val blob = new FakeBlob(failCopy = true, failFree = true)

    val primary = intercept[RuntimeException](detach(blob))

    primary.getMessage shouldBe "copy failure"
    primary.getSuppressed.map(_.getMessage) should contain("free failure")
    blob.freeAttempted shouldBe true
  }

  it should "throw the free() failure itself when the copy succeeded — the locator is never silently leaked" in {
    val blob = new FakeBlob(failCopy = false, failFree = true)

    intercept[RuntimeException](detach(blob)).getMessage shouldBe "free failure"
  }

  it should "free the locator after a successful copy" in {
    val blob = new FakeBlob(failCopy = false, failFree = false)

    detach(blob).asInstanceOf[Array[Byte]] shouldBe Array[Byte](1, 2, 3, 4)
    blob.freeAttempted shouldBe true
  }
}
