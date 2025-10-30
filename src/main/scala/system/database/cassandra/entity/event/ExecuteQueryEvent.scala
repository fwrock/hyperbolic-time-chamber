package org.interscity.htc
package system.database.cassandra.entity.event

import system.entity.event.BaseEvent

case class QuerySuccess(result: Seq[Map[String, AnyRef]])

case object WriteSuccess

case class ExecuteQueryEvent(
  query: String
) extends BaseEvent()
