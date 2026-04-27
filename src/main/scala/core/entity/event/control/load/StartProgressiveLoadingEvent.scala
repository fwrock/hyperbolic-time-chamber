package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick
import core.entity.configuration.ActorDataSource

import org.apache.pekko.actor.ActorRef

/** Sent by SimulationManager to the ProgressiveLoadDataManager to start progressive loading during
  * simulation.
  *
  * The ProgressiveLoadDataManager creates its own independent creator pools so they are not
  * destroyed when LoadDataManager is stopped after eager loading.
  *
  * @param progressiveSources
  *   the actor data sources marked as PROGRESSIVE
  * @param timeManagerRef
  *   reference to the GlobalTimeManager
  * @param lookAheadTicks
  *   how many ticks ahead to pre-load actors
  */
case class StartProgressiveLoadingEvent(
  progressiveSources: List[ActorDataSource],
  timeManagerRef: ActorRef,
  lookAheadTicks: Tick = 1000
)
