package org.interscity.htc
package core.entity.control

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify

import scala.collection.mutable

case class ScheduledActors(
  tick: Tick,
  actorsRef: mutable.Set[Identify]
)
