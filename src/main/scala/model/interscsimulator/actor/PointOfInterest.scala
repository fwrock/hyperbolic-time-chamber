package org.interscity.htc
package model.interscsimulator.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.actor.BaseActor
import org.interscity.htc.model.interscsimulator.entity.state.PointOfInterestState

import scala.collection.mutable

class PointOfInterest(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[PointOfInterestState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

}
