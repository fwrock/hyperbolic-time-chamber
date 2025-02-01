package org.interscity.htc
package core.entity.state

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.{ ActorContext, ActorRef }
import org.apache.pekko.event.Logging

abstract class BaseState(
  startTick: Tick = Long.MinValue
) {

  def getStartTick: Tick = startTick
}
