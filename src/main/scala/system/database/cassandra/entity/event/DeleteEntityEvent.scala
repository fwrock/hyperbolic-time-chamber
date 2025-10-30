package org.interscity.htc
package system.database.cassandra.entity.event

import system.entity.event.BaseEvent

case class DeleteEntityEvent(
  table: String,
  conditions: String, // Condições para a cláusula WHERE
  conditionArgs: List[Any] // Valores para a cláusula WHERE
) extends BaseEvent
