package org.interscity.htc
package core.util

import com.fasterxml.jackson.core.JsonProcessingException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.SQLException
import scala.util.Using

class SqliteActorSourceSpec extends AnyFlatSpec with Matchers {
  import SqliteActorSourceFixture.{ insertActor, withDatabase }

  "openIterator" should "retain empty-source iterator semantics without closing the caller's connection" in {
    withDatabase(
      _ => ()
    ) {
      path =>
        val (connection, actors) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(connection) {
          _ =>
            actors.hasNext shouldBe false
            actors.hasNext shouldBe false
            intercept[NoSuchElementException](actors.next())
            connection.isClosed shouldBe false
        }
        connection.isClosed shouldBe true
    }
  }

  it should "order by seq and advance only when the next actor is requested" in {
    withDatabase {
      connection =>
        insertActor(connection, id = 1, seq = 20)
        insertActor(connection, id = 2, seq = 10)
    } {
      path =>
        val (connection, actors) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(connection) {
          _ =>
            actors.hasNext shouldBe true
            actors.hasNext shouldBe true
            actors.next().id shouldBe 2L
            actors.next().id shouldBe 1L
            actors.hasNext shouldBe false
            actors.hasNext shouldBe false
            intercept[NoSuchElementException](actors.next())
        }
    }
  }

  it should "allow callers to close a partially consumed source and open a fresh scan" in {
    withDatabase {
      connection =>
        insertActor(connection, id = 1, seq = 1)
        insertActor(connection, id = 2, seq = 2)
    } {
      path =>
        val (firstConnection, firstActors) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(firstConnection) {
          _ =>
            firstActors.next().id shouldBe 1L
            firstConnection.isClosed shouldBe false
        }
        firstConnection.isClosed shouldBe true
        val (nextConnection, nextActors) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(nextConnection) {
          _ =>
            nextActors.map(_.id).toList shouldBe List(1L, 2L)
        }
    }
  }

  it should "enable query-only mode and reject writes through the returned connection" in {
    withDatabase(
      _ => ()
    ) {
      path =>
        val (connection, _) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(connection) {
          _ =>
            Using.resource(connection.createStatement()) {
              statement =>
                Using.resource(statement.executeQuery("PRAGMA query_only")) {
                  result =>
                    result.next() shouldBe true
                    result.getInt(1) shouldBe 1
                }
                intercept[SQLException](statement.executeUpdate("DELETE FROM actor_simulation"))
            }
        }
    }
  }

  it should "decode rows lazily and propagate malformed payloads when reached" in {
    withDatabase {
      connection =>
        insertActor(connection, id = 1, seq = 1)
        insertActor(connection, id = 2, seq = 2, content = "{")
    } {
      path =>
        val (connection, actors) = SqliteActorSimulationUtil.openIterator(path.toString)
        Using.resource(connection) {
          _ =>
            actors.next().id shouldBe 1L
            actors.hasNext shouldBe true
            intercept[JsonProcessingException](actors.next())
            connection.isClosed shouldBe false
        }
        connection.isClosed shouldBe true
    }
  }

  it should "propagate missing-table failures during source initialization" in {
    withDatabase {
      connection =>
        Using.resource(connection.createStatement())(_.execute("DROP TABLE actor_simulation"))
        ()
    } {
      path =>
        intercept[SQLException](SqliteActorSimulationUtil.openIterator(path.toString))
    }
  }
}
