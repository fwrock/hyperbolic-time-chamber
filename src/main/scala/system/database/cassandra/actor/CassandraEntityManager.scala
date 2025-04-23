package org.interscity.htc
package system.database.cassandra.actor

import system.actor.BaseActorSystem

import org.apache.pekko.actor.Props
import org.htc.protobuf.system.database.database.CreateEntityEvent
import org.interscity.htc.system.database.cassandra.connection.CassandraConnection
import org.interscity.htc.system.database.cassandra.entity.event.{DeleteEntityEvent, ReadEntityEvent, UpdateEntityEvent}

class CassandraEntityManager(
  connectionName: String,
  keyspace: String = null
) extends BaseActorSystem {

  private val connection = CassandraConnection.createSession(connectionName, context.system)

  override def receive: Receive = {
    case event: CreateEntityEvent => insert(event.table, event.columns, event.values)

      case event: ReadEntityEvent =>
        val result = connection.select(s"SELECT ${event.projection} FROM ${event.table} ${
            if (event.selection != null) s"WHERE ${event.selection}" else ""
          }")
        sender() ! result

    case event: UpdateEntityEvent =>
      val setClause = event.setColumns.map {
        case (col, value) => s"$col = $value"
      }.mkString(", ")
      val result =
        connection.executeWrite(s"UPDATE ${event.table} SET $setClause WHERE ${event.conditions}")
      sender() ! result

    case event: DeleteEntityEvent =>
      val result = connection.executeWrite(s"DELETE FROM ${event.table} WHERE ${event.conditions}")
      sender() ! result
  }

  private def insert(
                      table: String,
                      columns: Seq[String] ,
                      values: Seq[String]
                    ): Unit = {
    val result = connection.executeWrite(
      s"INSERT INTO ${table} (${columns.mkString(",")}) VALUES (${values.mkString(",")})"
    )
    sender() ! result
  }


}


object CassandraEntityManager {
  def props(
    connectionName: String,
    keyspace: String = null
  ): Props =
    Props(
      classOf[CassandraEntityManager],
      connectionName,
      keyspace
    )
}
