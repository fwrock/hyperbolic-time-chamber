package org.interscity.htc
package core.entity.state

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.{ ActorContext, ActorRef }
import org.apache.pekko.event.Logging

/**
 * Base class for all states.
 *
 * @param startTick the tick when the state started
 */
abstract class BaseState(
  startTick: Tick = Long.MinValue
) {

  /**
   * Gets the tick when the state started.
   *
   * @return the tick when the state started
   */
  def getStartTick: Tick = startTick
}
