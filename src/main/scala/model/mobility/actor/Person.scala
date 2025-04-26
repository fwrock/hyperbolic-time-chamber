package org.interscity.htc
package model.mobility.actor

import model.mobility.entity.state.CarState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency

import scala.collection.mutable

class Person(
  private var id: String = null,
  private val timeManager: ActorRef = null
) extends Movable[CarState](
      movableId = id,
      timeManager = timeManager
    ) {}
