package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.state.DefaultState

class SnapshotManager(timeManager: ActorRef)
    extends BaseActor[DefaultState](timeManager = timeManager) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
