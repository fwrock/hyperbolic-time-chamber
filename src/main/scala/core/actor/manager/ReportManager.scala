package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.state.DefaultState

class ReportManager(timeManager: ActorRef)
    extends BaseActor[DefaultState](timeManager = timeManager) {

  override def handleEvent: Receive = {
    case _ =>
  }
}
