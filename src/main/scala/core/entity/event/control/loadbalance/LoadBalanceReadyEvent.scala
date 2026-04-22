package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Event sent by LoadBalanceManager to SimulationManager and TimeManager
  * when it has completed initialization and is ready to coordinate.
  *
  * @param loadBalanceManagerRef
  *   Reference to the LoadBalanceManager singleton proxy
  */
case class LoadBalanceReadyEvent(
  loadBalanceManagerRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]
