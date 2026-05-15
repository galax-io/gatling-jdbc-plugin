package org.galaxio.gatling.jdbc.integration

import com.dimafeng.testcontainers.{ForAllTestContainer, MySQLContainer}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.galaxio.gatling.jdbc.db._
import org.galaxio.gatling.jdbc.tags.DockerTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

class MysqlIntegrationSpec extends AnyWordSpec with Matchers with ForAllTestContainer with BeforeAndAfterAll {

  override val container: MySQLContainer = MySQLContainer(
    mysqlImageVersion = DockerImageName.parse("mysql:8.0"),
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

  "MySQL integration" should {
    "execute DDL and CRUD" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw(
          "CREATE TABLE IF NOT EXISTS test_mysql (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), score DOUBLE)",
        ),
      )

      await[Boolean](client.executeRaw("INSERT INTO test_mysql (name, score) VALUES ('Alice', 95.5)"))
      await[Boolean](client.executeRaw("INSERT INTO test_mysql (name, score) VALUES ('Bob', 87.3)"))

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT name, score FROM test_mysql ORDER BY name", Seq.empty),
      )

      rows should have size 2
      rows.head("name") shouldBe "Alice"
      rows.head("score") shouldBe 95.5
    }

    "execute parameterized queries" taggedAs DockerTest in {
      val rows = await[List[Map[String, Any]]](
        client.executeSelect(
          "SELECT name FROM test_mysql WHERE score > {minScore}",
          Seq("minScore" -> DoubleParam(90.0)),
        ),
      )

      rows should have size 1
      rows.head("name") shouldBe "Alice"
    }

    "execute batch operations" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw(
          "CREATE TABLE IF NOT EXISTS batch_mysql (id INT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255))",
        ),
      )

      val queries = (1 to 3).map { i =>
        SQL(s"INSERT INTO batch_mysql (val) VALUES ('item-$i')").withParams()
      }

      val results = await[Array[Int]](client.batch(queries))
      results.length shouldBe 3
    }

    "execute update with params" taggedAs DockerTest in {
      val updated = await[Int](
        client.executeUpdate(
          "UPDATE test_mysql SET score = {score} WHERE name = {name}",
          Seq("score" -> DoubleParam(99.9), "name" -> StrParam("Alice")),
        ),
      )
      updated shouldBe 1
    }

    "execute transaction with commit" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw("CREATE TABLE IF NOT EXISTS tx_mysql (id INT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255))"),
      )

      val count = await[Int](
        client.executeTransaction(
          Seq(
            "INSERT INTO tx_mysql (val) VALUES ('tx1')",
            "INSERT INTO tx_mysql (val) VALUES ('tx2')",
          ),
        ),
      )
      count shouldBe 2

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT COUNT(*) AS cnt FROM tx_mysql", Seq.empty),
      )
      rows.head("cnt").asInstanceOf[Long] shouldBe 2L
    }

    "rollback transaction on error" taggedAs DockerTest in {
      await[Boolean](
        client.executeRaw(
          "CREATE TABLE IF NOT EXISTS tx_rollback_mysql (id INT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255) NOT NULL)",
        ),
      )

      an[Exception] shouldBe thrownBy {
        await[Int](
          client.executeTransaction(
            Seq(
              "INSERT INTO tx_rollback_mysql (val) VALUES ('ok')",
              "INSERT INTO tx_rollback_mysql (val) VALUES (NULL)",
            ),
          ),
        )
      }

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT COUNT(*) AS cnt FROM tx_rollback_mysql", Seq.empty),
      )
      rows.head("cnt").asInstanceOf[Long] shouldBe 0L
    }

    "handle NULL values" taggedAs DockerTest in {
      await[Boolean](client.executeRaw("INSERT INTO test_mysql (name, score) VALUES (NULL, NULL)"))

      val rows = await[List[Map[String, Any]]](
        client.executeSelect("SELECT name, score FROM test_mysql WHERE name IS NULL", Seq.empty),
      )
      rows should have size 1
      Option(rows.head("name")) shouldBe None
    }
  }

  override def afterAll(): Unit = {
    client.close()
    super.afterAll()
  }
}
