package org.interscity.htc
package core.entity.event

import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.CreationTypeEnum

case class ActorInteractionEvent(
  tick: Tick,
  lamportTick: Tick,
  actorRefId: String,
  shardRefId: String,
  actorPathRef: String,
  actorClassType: String,
  eventType: String = "default",
  data: AnyRef,
  actorType: String = CreationTypeEnum.LoadBalancedDistributed.toString
) {

  def toIdentity: Identify = Identify(
    id = actorRefId,
    classType = actorClassType,
    actorRef = actorPathRef
  )
}
