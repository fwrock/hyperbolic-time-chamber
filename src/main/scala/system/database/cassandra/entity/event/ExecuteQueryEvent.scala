package org.interscity.htc
package system.database.cassandra.entity.event

import system.entity.event.BaseEvent

case class ExecuteQueryEvent(
  query: String
) extends BaseEvent()
