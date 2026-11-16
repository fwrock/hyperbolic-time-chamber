package org.interscity.htc
package model.hybrid.entity.event.data

import model.hybrid.entity.state.enumeration.ActorTypeEnum
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.enumeration.CreationTypeEnum

// shardId/actorId removed (recommendation 11, docs/EVENTS_MESSAGES_ANALYSIS.md): always equal to
// the sending actor's own getShardId/getEntityId, already carried by the ActorInteractionEvent
// envelope that contains this payload (actorRefId/shardRefId) — consumers read event.actorRefId/
// event.shardRefId instead.
final case class EnterLinkData(
  actorType: ActorTypeEnum,
  actorCreationType: CreationTypeEnum,
  actorSize: Double,
  initialVelocity: Double = 0.0,
  nextLinkId: Long = 0L,
  initialRemainderSeconds: Double = 0.0,
  maxAcceleration: Option[Double] = None,
  maxDeceleration: Option[Double] = None,
  initialPosition: Double = 0.0,
  arrivalPosition: Option[Double] = None,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Integer])
  preferredLane: Option[Int] = None,
  minGap: Option[Double] = None,
  reactionTime: Option[Double] = None,
  maxSpeed: Option[Double] = None,
  speedFactor: Double = 1.0,
  desiredMaxSpeed: Option[Double] = None,
  sigma: Double = 0.0,
  randomSeed: Long = 0L,
  lc2013: model.hybrid.entity.state.model.Lc2013Parameters = model.hybrid.entity.state.model.Lc2013Parameters(),
  mesoOriginAtEnd: Boolean = false
) extends BaseEventData
