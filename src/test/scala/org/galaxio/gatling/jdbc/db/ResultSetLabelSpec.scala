package org.galaxio.gatling.jdbc.db

import org.galaxio.gatling.jdbc.db.testsupport.H2
import org.scalatest.BeforeAndAfterAll
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Regression spec for issue #122 (US2): result rows are keyed by the column label as written in the query — the alias when
  * `AS` is present — never by the physical column name. H2 upper-cases unquoted labels; quoted labels stay verbatim.
  */
class ResultSetLabelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dbName       = "label_spec"
  private val dataSource   = H2.dataSource(dbName, 2)
  private val blockingPool = Executors.newFixedThreadPool(2)
  private val client       = JDBCClient(dataSource, blockingPool)

  override def beforeAll(): Unit = {
    exec("CREATE TABLE IF NOT EXISTS label_rows (id INT PRIMARY KEY, name VARCHAR(50))")
    exec("DELETE FROM label_rows")
    exec("INSERT INTO label_rows (id, name) VALUES (1, 'alice')")
  }

  override def afterAll(): Unit =
    client.close()

  private def exec(sql: String): Unit =
    Await.result(client.executeRaw(sql)(identity), 10.seconds)

  private def selectRows(sql: String): List[Map[String, Any]] =
    Await.result(client.executeSelect(sql, Seq.empty)(identity), 10.seconds).success.value

  "executeSelect" should "key aliased columns by the alias, not the physical column name" in {
    val rows = selectRows("SELECT id AS customer_id FROM label_rows")

    rows should have size 1
    rows.head.keySet shouldBe Set("CUSTOMER_ID") // H2 upper-cases unquoted labels
    rows.head("CUSTOMER_ID") shouldBe 1
  }

  it should "preserve a quoted alias verbatim, including its case" in {
    val rows = selectRows("""SELECT id AS "customer_id" FROM label_rows""")

    rows.head.keySet shouldBe Set("customer_id")
    rows.head("customer_id") shouldBe 1
  }

  it should "keep non-aliased keys identical to the physical column names" in {
    val rows = selectRows("SELECT id, name FROM label_rows")

    rows.head.keySet shouldBe Set("ID", "NAME")
    rows.head("NAME") shouldBe "alice"
  }

  it should "key mixed aliased and non-aliased columns consistently" in {
    val rows = selectRows("SELECT id AS customer_id, name FROM label_rows")

    rows.head.keySet shouldBe Set("CUSTOMER_ID", "NAME")
  }
}
