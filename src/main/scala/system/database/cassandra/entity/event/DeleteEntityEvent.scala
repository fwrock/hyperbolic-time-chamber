package org.interscity.htc
package system.database.cassandra.entity.event

case class DeleteEntityEvent(
  table: String,
  conditions: String
)
