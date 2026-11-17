package org.galaxio.gatling.jdbc.db

import com.zaxxer.hikari.HikariDataSource
import org.galaxio.gatling.jdbc.db.testsupport.{CloseCountingDataSource, H2, H2ClientSpecFixture}
import org.scalatest.TryValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/** A SQL placeholder with no parameter binding fails every prepared-statement operation before execution with the same
  * `IllegalArgumentException` the callable path throws, naming the placeholder, and still releases every resource exactly once.
  */
class UnboundPlaceholderBindingSpec extends AnyFlatSpec with Matchers with H2ClientSpecFixture {

  override protected val dbName = "unbound_placeholder_binding"

  private lazy val countingDataSource = new CloseCountingDataSource(H2.config(dbName, poolSize))

  override protected def buildDataSource(): HikariDataSource = countingDataSource

  override def beforeAll(): Unit =
    exec("CREATE TABLE IF NOT EXISTS ub_items (id INT PRIMARY KEY, name VARCHAR(64))")

  private def bindingMessage(name: String): String = s"SQL placeholder '$name' has no corresponding parameter binding"

  private def await[T](future: Future[T]): T = Await.result(future, 10.seconds)

  private def seed(): Unit = {
    exec("DELETE FROM ub_items")
    exec("INSERT INTO ub_items (id, name) VALUES (1, 'KEEP')")
  }

  private def nameOf(id: Int): String = withFreshConnection { conn =>
    val rs = conn.createStatement().executeQuery(s"SELECT name FROM ub_items WHERE id = $id")
    rs.next()
    rs.getString(1)
  }

  private val unboundSelect = "SELECT id FROM ub_items WHERE id = {a} AND name = {b}"

  private def failingSelect(): Throwable =
    await(client.executeSelect(unboundSelect, Seq("a" -> IntParam(1)))(identity)).failure.exception

  behavior of "FR-001: an unbound placeholder"

  it should "fail an update with IllegalArgumentException naming it, before execution" in {
    seed()
    val insert = "INSERT INTO ub_items (id, name) VALUES ({id},{name})"
    val result = await(client.executeUpdate(insert, Seq("id" -> IntParam(2)))(identity))

    result.failure.exception shouldBe an[IllegalArgumentException]
    result.failure.exception.getMessage shouldBe bindingMessage("name")
    countFromFreshConnection("ub_items") shouldBe 1 // only the seeded row: the INSERT never ran
  }

  it should "fail a select with IllegalArgumentException, releasing connection and statement exactly once" in {
    seed()
    countingDataSource.resetTracking()

    val exception = failingSelect()

    exception shouldBe an[IllegalArgumentException]
    exception.getMessage shouldBe bindingMessage("b")
    countingDataSource.trackedResources.map(_.kind) should contain allOf ("connection", "statement")
    all(countingDataSource.trackedResources.map(_.closeCount.get())) shouldBe 1

    Thread.sleep(200)
    countingDataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
  }

  it should "keep IllegalArgumentException primary when the statement close fails, the close failure suppressed" in {
    seed()
    countingDataSource.resetTracking()
    countingDataSource.failNextStatementClose = true

    val exception = failingSelect()

    exception shouldBe an[IllegalArgumentException]
    exception.getSuppressed.map(_.getMessage) should contain("injected close failure")
    all(countingDataSource.trackedResources.map(_.closeCount.get())) shouldBe 1

    Thread.sleep(200)
    countingDataSource.getHikariPoolMXBean.getActiveConnections shouldBe 0
  }

  it should "fail a batch with IllegalArgumentException naming it, changing no row" in {
    seed()
    val update = SqlWithParam("UPDATE ub_items SET name = {name} WHERE id = {id}", Seq("name" -> StrParam("CHANGED")))
    val result = await(client.batch(Seq(update))(identity))

    result.failure.exception shouldBe an[IllegalArgumentException]
    result.failure.exception.getMessage shouldBe bindingMessage("id")
    nameOf(1) shouldBe "KEEP"
  }

  it should "fail the prepared and the callable path with the same exception and message" in {
    seed()
    val insert   = "INSERT INTO ub_items (id, name) VALUES ({id},{n})"
    val prepared = await(client.executeUpdate(insert, Seq("id" -> IntParam(3)))(identity)).failure.exception
    val callable = await(client.call("CALL CONCAT({s},{n})", Seq("s" -> StrParam("x")), Seq.empty)(identity)).failure.exception

    callable shouldBe an[IllegalArgumentException]
    callable.getMessage shouldBe bindingMessage("n")
    prepared.getClass shouldBe callable.getClass
    prepared.getMessage shouldBe callable.getMessage
  }
}
