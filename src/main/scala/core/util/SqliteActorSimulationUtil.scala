package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.sql.{ Connection, DriverManager, ResultSet }

/** Maps a row of the `actor_simulation` table (see `tools/scenario-db-converter/convert.py` for
  * the schema and the JSON<->SQLite converter) back into an `ActorSimulation`.
  *
  * Rebuilds the row's exact single-record JSON text and deserializes it through
  * `mapper.readValue(_, classOf[ActorSimulation])` — the same call `JsonStreamingUtil` makes per
  * record when streaming the equivalent JSON array — rather than manually constructing
  * `ActorSimulation`/`PoolDistributedConfiguration`/`ShardActorId` field-by-field. Jackson's
  * concrete runtime type for an `Any`-typed field (like `ActorDataSimulation.content`) depends on
  * *how* it's reached (a nested case-class field vs. a bare `readValue(_, classOf[Object])` call
  * produce different container types with `jackson-module-scala`'s `DefaultScalaModule`
  * registered) — going through the identical case-class deserialization path is what makes
  * `SqliteLoadData`/`ProgressiveSqliteLoadData` genuine drop-in alternatives to
  * `JsonLoadData`/`ProgressiveJsonLoadData` for every downstream consumer of `ActorSimulation`,
  * instead of merely producing an equivalent-looking but differently-typed object.
  */
object SqliteActorSimulationUtil {

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  def fromResultSet(rs: ResultSet): ActorSimulation = {
    val poolUseRoles = rs.getString("pool_use_roles")
    val poolConfigurationJson =
      if (rs.getObject("pool_total_instances") == null) "null"
      else
        s"""{"roundRobinPool":${rs.getInt("pool_round_robin_pool")},""" +
          s""""totalInstances":${rs.getInt("pool_total_instances")},""" +
          s""""maxInstancesPerNode":${rs.getInt("pool_max_instances_per_node")},""" +
          s""""allowLocalRoutes":${rs.getInt("pool_allow_local_routes") != 0},""" +
          s""""useRoles":${if (poolUseRoles == null) "null" else poolUseRoles}}"""

    val relationshipsJson = {
      val text = rs.getString("relationships")
      if (text == null) "null" else text
    }

    val actorJson =
      s"""{"id":${jsonString(rs.getString("id"))},""" +
        s""""name":${jsonString(rs.getString("name"))},""" +
        s""""typeActor":${jsonString(rs.getString("type_actor"))},""" +
        s""""creationType":${jsonString(rs.getString("creation_type"))},""" +
        s""""poolConfiguration":$poolConfigurationJson,""" +
        s""""data":{"dataType":${jsonString(rs.getString("data_type"))},"content":${rs.getString("data_content")}},""" +
        s""""relationships":$relationshipsJson}"""

    mapper.readValue(actorJson, classOf[ActorSimulation])
  }

  private def jsonString(value: String): String = mapper.writeValueAsString(value)

  /** Opens a `.db` file and returns a full-table iterator over every `ActorSimulation` row, in the
    * same shape `JsonStreamingUtil.createParser` returns for a JSON array — a resource the caller
    * must close, paired with a lazy `Iterator[ActorSimulation]`.
    *
    * For consumers that need to read every `ActorSimulation` in a source exactly once end-to-end
    * (not chunked/tick-windowed like `SqliteLoadData`/`ProgressiveSqliteLoadData`) — e.g.
    * `ScenarioPreflightValidator`, which streams every Person source once regardless of whether it
    * backs an EAGER or PROGRESSIVE load, before any actor is created.
    */
  def openIterator(dbPath: String): (Connection, Iterator[ActorSimulation]) = {
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:file:$dbPath?immutable=1")
    val pragma = conn.createStatement()
    pragma.execute("PRAGMA query_only = ON")
    pragma.close()

    val stmt = conn.createStatement()
    val rs = stmt.executeQuery("SELECT * FROM actor_simulation ORDER BY seq")

    val iterator = new Iterator[ActorSimulation] {
      private var advanced = false
      private var hasNextCached = false

      override def hasNext: Boolean = {
        if (!advanced) {
          hasNextCached = rs.next()
          advanced = true
        }
        hasNextCached
      }

      override def next(): ActorSimulation = {
        if (!hasNext) throw new NoSuchElementException("next() on exhausted iterator")
        advanced = false
        fromResultSet(rs)
      }
    }

    (conn, iterator)
  }
}
