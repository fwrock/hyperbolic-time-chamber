package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.interscsimulator.entity.state.SubwayState

import scala.collection.mutable

class Subway(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[SubwayState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {}
