package org.interscity.htc
package system.database.cassandra.actor

import system.actor.BaseSystem

import org.apache.pekko.stream.scaladsl.Sink
import org.interscity.htc.system.database.cassandra.connection.CassandraConnection
import org.interscity.htc.system.database.cassandra.entity.event.{CreateEntityEvent, DeleteEntityEvent, ReadEntityEvent, UpdateEntityEvent}

class CassandraEntityManager(
  connectionName: String,
               ) extends BaseSystem {
  
  private val connection = CassandraConnection.createSession(connectionName, context.system)

  override def receive: Receive = {
    case entity: CreateEntityEvent=>
      val result = connection.executeWrite(s"INSERT INTO ${entity.table} (${entity.columns.mkString(",")}) VALUES (${entity.values.mkString(",")})")
      sender() ! result

    case event: ReadEntityEvent =>
      val result = connection.select(s"SELECT ${event.projection} FROM ${event.table} ${if (event.selection != null) s"WHERE ${event.selection}" else ""}")
      sender() ! result

    case event: UpdateEntityEvent =>
      val setClause = event.setColumns.map { case (col, value) => s"$col = $value" }.mkString(", ")
      val result = connection.executeWrite(s"UPDATE ${event.table} SET $setClause WHERE ${event.conditions}")
      sender() ! result

    case event: DeleteEntityEvent =>
      val result = connection.executeWrite(s"DELETE FROM ${event.table} WHERE ${event.conditions}")
      sender() ! result
  }
 
}
