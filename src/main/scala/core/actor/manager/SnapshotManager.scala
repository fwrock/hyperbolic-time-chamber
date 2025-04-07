package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.ActorRef
import core.entity.state.DefaultState

import scala.collection.mutable

class SnapshotManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "snapshot-manager",
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
