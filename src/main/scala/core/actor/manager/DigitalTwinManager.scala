package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.state.DefaultState

import scala.collection.mutable

class DigitalTwinManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      actorId = "data-flow-manager"
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
