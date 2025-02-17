package org.interscity.htc
package system.database.cassandra.entity.event

import org.interscity.htc.system.entity.event.BaseEvent

case class ReadEntityEvent (
    table: String,
    projection: String,
    selection: String,
                           ) extends BaseEvent
