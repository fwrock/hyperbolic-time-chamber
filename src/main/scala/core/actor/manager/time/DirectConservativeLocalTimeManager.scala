package org.interscity.htc
package core.actor.manager.time

import org.apache.pekko.actor.{ ActorRef, Props }
import core.types.Tick

/** Direct conservative policy; the inherited owner retains scheduling and lifecycle fences. */
class DirectConservativeLocalTimeManager(duration: Tick, manager: ActorRef, coordinator: ActorRef)
    extends ChannelAwareLocalTimeManager(duration, manager, coordinator) {
  override protected def directInteractions: Boolean = true
}

object DirectConservativeLocalTimeManager {

  /** Serializable constructor arguments preserve remote pool deployment. */
  def props(duration: Tick, manager: ActorRef, coordinator: ActorRef): Props =
    Props(classOf[DirectConservativeLocalTimeManager], Long.box(duration), manager, coordinator)
}
