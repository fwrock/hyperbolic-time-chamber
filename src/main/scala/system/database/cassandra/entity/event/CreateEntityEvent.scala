package org.interscity.htc
package system.database.cassandra.entity.event

import org.interscity.htc.system.entity.event.BaseEvent

case class CreateEntityEvent (
  table: String,
  columns: List[String],
    values: List[String]
                             ) extends BaseEvent
