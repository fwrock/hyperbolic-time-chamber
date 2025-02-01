package org.interscity.htc
package core.entity.control

import core.types.CoreTypes.Tick

class LamportClock {
  private var clock: Tick = 0L

  def increment(): Unit = synchronized {
    clock += 1
  }

  def getClock: Long = synchronized {
    clock
  }

  def update(otherClock: Long): Unit = synchronized {
    clock = math.max(clock, otherClock) + 1
  }
}
