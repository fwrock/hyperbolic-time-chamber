package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.state.DefaultState

import scala.collection.mutable

class DigitalTwinManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "data-flow-manager",
      data = null,
      dependencies = mutable.Map.empty
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
