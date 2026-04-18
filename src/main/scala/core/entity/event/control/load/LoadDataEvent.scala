package org.interscity.htc
package core.entity.event.control.load

import core.entity.configuration.ActorDataSource

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class LoadDataEvent(
  actorRef: ActorRef,
  actorsDataSources: List[ActorDataSource],
  /** Short class names that should auto-register for post-load registration (from simulation config). */
  postLoadRegistrationClasses: List[String] = List.empty
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
