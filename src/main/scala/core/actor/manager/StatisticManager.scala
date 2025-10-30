package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.state.DefaultState

import scala.collection.mutable

class StatisticManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      actorId = "statistic-manager"
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
