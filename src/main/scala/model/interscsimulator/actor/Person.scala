package org.interscity.htc
package model.interscsimulator.actor

import model.interscsimulator.entity.state.CarState

import org.apache.pekko.actor.ActorRef

import scala.collection.mutable

class Person(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends Movable[CarState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {}
