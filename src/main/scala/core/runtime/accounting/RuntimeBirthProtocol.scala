package org.interscity.htc
package core.runtime.accounting

import core.entity.actor.ShardActorId
import core.entity.event.data.BaseEventData
import core.enumeration.ReportTypeEnum
import org.apache.pekko.actor.ActorRef

final case class RuntimeBirthOrigin(parent: LogicalProcessId, key: String, tick: Long, microstep: Long = 0L) {
  def order: core.runtime.EventOrder = core.runtime.EventOrder(tick, microstep, key)
}
final case class RuntimeBirthData(stateJson: String, relationships: Map[String, ShardActorId],
  resourceId: String, timeManagers: Map[String, ActorRef], reporters: Map[ReportTypeEnum, ActorRef]) extends BaseEventData
final case class RuntimeBirthAccepted(child: LogicalProcessId, requestSeq: Long) extends BaseEventData
sealed trait RuntimeBirthCommand
final case class RequestRuntimeBirth(epoch: String, target: LogicalProcessId, origin: RuntimeBirthOrigin) extends RuntimeBirthCommand
final case class AuthorizeRuntimeBirth(epoch: String, target: LogicalProcessId, origin: RuntimeBirthOrigin) extends RuntimeBirthCommand
final case class RuntimeBirthInitialized(target: LogicalProcessId, ref: ActorRef) extends RuntimeBirthCommand
case object StopRuntimeBirthInitializer extends RuntimeBirthCommand
