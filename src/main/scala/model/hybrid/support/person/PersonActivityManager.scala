package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.{Activity, PersonState}

/** Manages person activity lifecycle and transitions.
  *
  * Responsibilities:
  *   - Check if activity end time reached
  *   - Calculate time until activity end
  *   - Advance to next activity in schedule
  *   - Activity state management
  *
  * @param personId Person actor ID for logging
  * @param scheduleManager Schedule manager for timing calculations
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  */
class PersonActivityManager(
  personId: String,
  scheduleManager: PersonScheduleManager,
  logDebug: String => Unit,
  logWarn: String => Unit
) {

  /** Check if current activity's end time has been reached.
    *
    * @param activity Activity to check
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return true if activity should end
    */
  def isActivityEndTime(activity: Activity, state: PersonState, currentTick: Tick): Boolean =
    scheduleManager.effectiveEndTick(activity, state) match {
      case Some(endTick) =>
        currentTick >= endTick
      case None =>
        logWarn(s"$personId cannot parse endTime: ${activity.endTime}, assuming time reached")
        true
    }

  /** Calculate ticks until activity end time.
    *
    * @param activity Activity to calculate for
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return Ticks until activity ends (minimum 1)
    */
  def getTickUntilActivityEnd(activity: Activity, state: PersonState, currentTick: Tick): Long =
    scheduleManager.effectiveEndTick(activity, state)
      .map(endTick => Math.max(1L, endTick - currentTick))
      .getOrElse(1L)

  /** Advance to next activity in daily schedule.
    *
    * @param state Current person state
    * @return Updated PersonState with advanced activity index
    */
  def advanceActivity(state: PersonState): PersonState = {
    logDebug(
      s"$personId advancing from activity ${state.currentActivityIndex} " +
        s"(${state.currentActivity.map(_.activityType).getOrElse("none")}) " +
        s"to ${state.nextActivity.map(_.activityType).getOrElse("none")}"
    )
    state.advanceActivity()
  }

  /** Update schedule delay after arriving at an activity.
    *
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return Updated PersonState with recalculated delays
    */
  def updateScheduleDelayOnArrival(state: PersonState, currentTick: Tick): PersonState =
    scheduleManager.updateScheduleDelayOnArrival(state, state.currentActivityIndex, currentTick)
}
