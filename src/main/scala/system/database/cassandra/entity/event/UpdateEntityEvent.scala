package org.interscity.htc
package system.database.cassandra.entity.event

import system.entity.event.BaseEvent

case class UpdateEntityEvent (
  setColumns: Map[String, Any],
  conditions: String,
  table: String
                             ) extends BaseEvent
