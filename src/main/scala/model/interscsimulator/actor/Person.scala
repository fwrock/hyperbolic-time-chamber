package org.interscity.htc
package model.interscsimulator.actor

import model.interscsimulator.entity.state.CarState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.{ Dependency, Identify }

import scala.collection.mutable

class Person(
  private var id: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Dependency] =
    mutable.Map[String, Dependency]()
) extends Movable[CarState](
      movableId = id,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {}
