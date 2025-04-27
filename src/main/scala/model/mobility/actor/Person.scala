package org.interscity.htc
package model.mobility.actor

import model.mobility.entity.state.CarState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.entity.actor.properties.Properties

import scala.collection.mutable

class Person(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    ) {}
