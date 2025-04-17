package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.state.DefaultState

class ReportManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "report-manager"
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }
}
