package org.interscity.htc
package core.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.ReportTypeEnum

/** Base class for all states.
  *
  * @param startTick
  *   the tick when the state started
  */
abstract class BaseState(
  startTick: Tick = Long.MinValue,
  reporterType: ReportTypeEnum = null
) {

  /** Gets the tick when the state started.
    *
    * @return
    *   the tick when the state started
    */
  def getStartTick: Tick = startTick

  def getReporterType: ReportTypeEnum = reporterType
}
