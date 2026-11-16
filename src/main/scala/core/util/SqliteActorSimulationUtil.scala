package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import java.sql.{ Connection, DriverManager, ResultSet }
import scala.util.Using
import scala.util.control.NonFatal

/** Opens immutable SQLite actor sources and exposes their JSON-compatible row decoding.
  *
  * The actor_simulation schema is defined by tools/scenario-db-converter/convert.py. Decoding keeps
  * the complete JSON binding path so eager and progressive SQLite sources retain the runtime data
  * representations produced by JsonStreamingUtil.
  */
object SqliteActorSimulationUtil {

  /** Decodes the current row without advancing or closing the caller's ResultSet. */
  def fromResultSet(rs: ResultSet): ActorSimulation = SqliteActorRowDecoder.decode(rs)

  /** Opens a lazy full-table scan ordered by seq, without applying a tick window.
    *
    * The caller owns the returned connection and must close it, including after partial iteration
    * or decoding failures. The database must remain unchanged while opened with immutable=1. Query
    * setup failures close the connection before propagating; successful iteration keeps it open.
    */
  def openIterator(dbPath: String): (Connection, Iterator[ActorSimulation]) = {
    Class.forName("org.sqlite.JDBC")
    val connection = DriverManager.getConnection(s"jdbc:sqlite:file:$dbPath?immutable=1")
    try {
      enableQueryOnly(connection)
      val statement = connection.createStatement()
      val rows = statement.executeQuery("SELECT * FROM actor_simulation ORDER BY seq")
      (connection, new SqliteActorSourceIterator(rows))
    } catch {
      case NonFatal(error) =>
        Using.resource(connection)(
          _ => throw error
        )
    }
  }

  /** Releases the setup statement before handing the connection to the source iterator. */
  private def enableQueryOnly(connection: Connection): Unit =
    Using.resource(connection.createStatement()) {
      statement =>
        statement.execute("PRAGMA query_only = ON")
        ()
    }
}
