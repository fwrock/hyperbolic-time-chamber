package org.interscity.htc
package core.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum.DiscreteEventSimulation
import org.interscity.htc.core.enumeration.{ LocalTimeManagerTypeEnum, ReportTypeEnum }

/** Base class for all states.
  *
  * @param startTick
  *   the tick when the state started
  */
abstract class BaseState