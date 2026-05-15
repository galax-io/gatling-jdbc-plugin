package org.galaxio.gatling.jdbc.integration

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.galaxio.gatling.jdbc.db._
import org.galaxio.gatling.jdbc.tags.DockerTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

class PostgresIntegrationSpec extends AnyWordSpec with Matchers with ForAllTestContainer with BeforeAndAfterAll {

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
  )

  private lazy val ds = {
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(container.jdbcUrl)
    cfg.setUsername(container.username)
    cfg.setPassword(container.password)
    cfg.setMaximumPoolSize(4)
    new HikariDataSource(cfg)
  }

  private lazy val client = JDBCClient(ds, Executors.newFixedThreadPool(4))

  private def await[T](action: ((T => Unit, Throwable => Unit) => Unit)): T = {
    val latch                        = new CountDownLatch(1)
    var result: Either[Throwable, T] = Left(new RuntimeException("timeout"))
    action(
      v => { result = Right(v); latch.countDown() },
      e => { result = Left(e); latch.countDown() },
    )
    latch.await(30, TimeUnit.SECONDS)
    result.fold(throw _, identity)
  }

  "PostgreSQL integration" should {
    "execute DDL via raw SQL" taggedAs DockerTest in {
      val ok = await[Boolean](
        client.executeRaw("CREATE TABLE IF NOT EXISTS test_pg (id SERIAL PRIMARY KEY, name TEXT, active BOOLEAN)"),
      )
      ok shouldBe false // DDL returns false for execute()
    }

    "insert and select data" taggedAs DockerTest in {
      await[Boolean](client.executeRaw("INSERT INTO test_pg (name, active) VALUES ('Alice', true)"))
      await[Boolean](client.executeRaw("INSERT INTO test_pg (name, active) VALUES ('Bob', false)"))

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT name, active FROM test_pg ORDER BY name", Seq.empty),
      )

      rows should have size 2
      rows.head("name") shouldBe "Alice"
      rows.head("active") shouldBe true
      rows(1)("name") shouldBe "Bob"
      rows(1)("active") shouldBe false
    }

    "execute parameterized queries" taggedAs DockerTest in {
      val rows = await[List[Map[String, Any]]](
        client.executeSelect(
          "SELECT name FROM test_pg WHERE active = {active}",
          Seq("active" -> BooleanParam(true)),
        ),
      )

      rows should have size 1
      rows.head("name") shouldBe "Alice"
    }

    "execute update with params" taggedAs DockerTest in {
      val updated = await[Int](
        client.executeUpdate(
          "UPDATE test_pg SET name = {name} WHERE name = {old}",
          Seq("name" -> StrParam("Charlie"), "old" -> StrParam("Bob")),
        ),
      )
      updated shouldBe 1

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT name FROM test_pg WHERE name = {n}", Seq("n" -> StrParam("Charlie"))),
      )
      rows should have size 1
    }

    "handle NULL values" taggedAs DockerTest in {
      await[Boolean](client.executeRaw("INSERT INTO test_pg (name, active) VALUES (NULL, NULL)"))

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT name, active FROM test_pg WHERE name IS NULL", Seq.empty),
      )
      rows should have size 1
      Option(rows.head("name")) shouldBe None
      Option(rows.head("active")) shouldBe None
    }

    "execute batch operations" taggedAs DockerTest in {
      await[Boolean](client.executeRaw("CREATE TABLE IF NOT EXISTS batch_pg (id SERIAL PRIMARY KEY, val TEXT)"))

      val queries = (1 to 5).map { i =>
        SQL(s"INSERT INTO batch_pg (val) VALUES ('item-$i')").withParams()
      }

      val results = await[Array[Int]](client.batch(queries))
      results.length shouldBe 5

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT COUNT(*) AS cnt FROM batch_pg", Seq.empty),
      )
      rows.head("cnt").asInstanceOf[Long] shouldBe 5L
    }

    "execute transaction with commit" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw("CREATE TABLE IF NOT EXISTS tx_pg (id SERIAL PRIMARY KEY, val TEXT)"),
      )

      val count = await[Int](
        client.executeTransaction(
          Seq(
            "INSERT INTO tx_pg (val) VALUES ('tx1')",
            "INSERT INTO tx_pg (val) VALUES ('tx2')",
            "INSERT INTO tx_pg (val) VALUES ('tx3')",
          ),
        ),
      )
      count shouldBe 3

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT COUNT(*) AS cnt FROM tx_pg", Seq.empty),
      )
      rows.head("cnt").asInstanceOf[Long] shouldBe 3L
    }

    "rollback transaction on error" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw("CREATE TABLE IF NOT EXISTS tx_rollback_pg (id SERIAL PRIMARY KEY, val TEXT NOT NULL)"),
      )

      an[Exception] shouldBe thrownBy {
        await[Int](
          client.executeTransaction(
            Seq(
              "INSERT INTO tx_rollback_pg (val) VALUES ('ok')",
              "INSERT INTO tx_rollback_pg (val) VALUES (NULL)",
            ),
          ),
        )
      }

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT COUNT(*) AS cnt FROM tx_rollback_pg", Seq.empty),
      )
      rows.head("cnt").asInstanceOf[Long] shouldBe 0L
    }

    "handle empty transaction" taggedAs DockerTest in {
      val count = await[Int](client.executeTransaction(Seq.empty))
      count shouldBe 0
    }

    "handle empty batch" taggedAs DockerTest in {
      val results = await[Array[Int]](client.batch(Seq.empty))
      results shouldBe empty
    }

    "execute callable statement (function)" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw(
          """CREATE OR REPLACE FUNCTION add_one(x INT) RETURNS INT AS $$
          |BEGIN RETURN x + 1; END;
          |$$ LANGUAGE plpgsql""".stripMargin,
        ),
      )

      await[Boolean](client.executeRaw("CREATE TABLE IF NOT EXISTS func_result_pg (result INT)"))
      await[Boolean](client.executeRaw("INSERT INTO func_result_pg (result) SELECT add_one(41)"))

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT result FROM func_result_pg", Seq.empty),
      )
      rows.head("result") shouldBe 42
    }
  }

  override def afterAll(): Unit = {
    client.close()
    super.afterAll()
  }
}
