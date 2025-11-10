package org.interscity.htc
package model.hybrid.actor

import model.mobility.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.*

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.entity.actor.properties.Properties

import scala.collection.mutable

class HybridPerson(
  private val properties: Properties
) extends Movable[HybridCarState](
      properties = properties
    ) {}
