package org.interscity.htc
package core.entity.state

import core.enumeration.{LocalTimeManagerTypeEnum, ReportTypeEnum}

import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum.DiscreteEventSimulation
import org.interscity.htc.core.types.Tick

/** Base class for all states.
  *
  * @param startTick
  *   the tick when the state started
  */
abstract class SimulationBaseState(
                                    startTick: Tick = Long.MinValue,
                                    reporterType: ReportTypeEnum = null,
                                    var localTimeManagerType: LocalTimeManagerTypeEnum = DiscreteEventSimulation,
                                    scheduleOnTimeManager: Boolean = true
                                  ) extends BaseState(

) {

  /** Gets the tick when the state started.
   *
   * @return
   * the tick when the state started
   */
  def getStartTick: Tick = startTick

  def getReporterType: ReportTypeEnum = reporterType

  def getLocalTimeManagerType: LocalTimeManagerTypeEnum = localTimeManagerType

  def isSetScheduleOnTimeManager: Boolean = scheduleOnTimeManager
}
