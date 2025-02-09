package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.state.DefaultState

import scala.collection.mutable

class SnapshotManager(timeManager: ActorRef)
    extends BaseActor[DefaultState](
      timeManager = timeManager,
      actorId = "snapshot-manager",
      data = null,
      dependencies = mutable.Map.empty
    ) {

  override def handleEvent: Receive = {
    case _ =>
  }

}
