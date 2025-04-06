package org.interscity.htc
package core.entity.control

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

case class ScheduledActors(
  tick: Tick,
  actorsRef: mutable.Set[Identify]
)
