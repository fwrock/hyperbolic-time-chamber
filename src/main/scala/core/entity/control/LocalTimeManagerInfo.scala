package org.interscity.htc
package core.entity.control

import core.types.Tick

import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum.DiscreteEventSimulation

case class LocalTimeManagerInfo(
  tick: Tick,
  hasSchedule: Boolean = false,
  isProcessed: Boolean = false,
  localTimeManagerType: LocalTimeManagerTypeEnum = DiscreteEventSimulation
)
