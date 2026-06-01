package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by SimulationManager to LoadDataManager to start post-load registration phase.
  *
  * Startup sequence becomes deterministic:
  * eager load -> warm-up -> post-load registration -> progressive bootstrap -> simulation start.
  */
case object StartPostLoadRegistrationPhaseEvent extends BaseEvent[DefaultBaseEventData]

