package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick
import core.entity.configuration.ActorDataSource

import org.apache.pekko.actor.ActorRef

/**
 * Sent by SimulationManager/LoadDataManager to the ProgressiveLoadDataManager
 * to start progressive loading during simulation.
 *
 * @param progressiveSources  the actor data sources marked as PROGRESSIVE
 * @param creatorRef          reference to the CreatorLoadData pool
 * @param creatorPoolRef      reference to the CreatorPoolLoadData pool
 * @param timeManagerRef      reference to the GlobalTimeManager
 * @param lookAheadTicks      how many ticks ahead to pre-load actors
 */
case class StartProgressiveLoadingEvent(
  progressiveSources: List[ActorDataSource],
  creatorRef: ActorRef,
  creatorPoolRef: ActorRef,
  timeManagerRef: ActorRef,
  lookAheadTicks: Tick = 1000
)
