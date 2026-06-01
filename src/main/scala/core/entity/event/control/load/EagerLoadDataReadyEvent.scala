package org.interscity.htc
package core.entity.event.control.load

import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by LoadDataManager to SimulationManager when all EAGER sources are loaded and created.
  *
  * SimulationManager uses this as a phase barrier to run warm-up and then explicitly trigger
  * post-load registration.
  */
case class EagerLoadDataReadyEvent(
  eagerSourcesLoaded: Int,
  progressiveSources: List[ActorDataSource] = List.empty
) extends BaseEvent[DefaultBaseEventData]

