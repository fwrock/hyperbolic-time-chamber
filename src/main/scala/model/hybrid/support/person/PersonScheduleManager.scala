package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.{Activity, PersonState}
import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.core.metrics.model.hybrid.PersonMetrics

/** Manages person schedule timing, delays, and truncation.
  *
  * Responsibilities:
  *   - Schedule truncation beyond simulation duration
  *   - Delay offset calculation and tracking
  *   - Activity timing calculations
  *   - Tick parsing and validation
  *
  * @param personId Person actor ID for logging
  * @param configProvider Function to get config values
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  * @param reportFn Reporting function for events
  */
class PersonScheduleManager(
  personId: String,
  configProvider: String => Option[String],
  logDebug: String => Unit,
  logWarn: String => Unit,
  reportFn: (Map[String, Any], String) => Unit
) {
  private var scheduleAlreadyTruncated: Boolean = false

  /** Parse tick string to Long.
    */
  def parseTick(value: String): Option[Long] =
    try Some(value.toLong)
    catch {
      case _: NumberFormatException => None
    }

  /** Get effective end tick for activity (includes delay offset).
    */
  def effectiveEndTick(activity: Activity, state: PersonState): Option[Long] =
    parseTick(activity.endTime).map(_ + state.scheduleDelayOffsetTicks)

  /** Get planned start tick for activity at given index.
    */
  def plannedStartTickForActivity(index: Int, schedule: List[Activity]): Option[Long] = {
    val previousIndex = index - 1
    if (previousIndex >= 0 && previousIndex < schedule.length)
      parseTick(schedule(previousIndex).endTime)
    else
      None
  }

  /** Update schedule delay offset based on observed arrival time.
    *
    * @param state Current person state
    * @param arrivedActivityIndex Index of activity just arrived at
    * @param currentTick Current simulation tick
    * @return Updated PersonState with new delay offset
    */
  def updateScheduleDelayOnArrival(
    state: PersonState,
    arrivedActivityIndex: Int,
    currentTick: Tick
  ): PersonState =
    plannedStartTickForActivity(arrivedActivityIndex, state.dailySchedule) match {
      case Some(plannedStartTick) =>
        val observedDelay = Math.max(0L, currentTick - plannedStartTick)
        val activityType = state.dailySchedule
          .lift(arrivedActivityIndex)
          .map(_.activityType)
          .getOrElse("unknown")

        PersonMetrics.personArrivalDelayTicks
          .labels(activityType)
          .observe(observedDelay.toDouble)

        if (observedDelay > 0L)
          PersonMetrics.personDelayedArrival.labels(activityType).inc()

        val firstTripDelay =
          if (arrivedActivityIndex == 1) Some(observedDelay)
          else state.firstTripDelayTicks

        if (observedDelay > state.scheduleDelayOffsetTicks) {
          logDebug(
            s"$personId updated schedule delay offset to ${observedDelay}s " +
              s"after arriving at activity $arrivedActivityIndex"
          )
          state.copy(
            scheduleDelayOffsetTicks = observedDelay,
            firstTripDelayTicks = firstTripDelay
          )
        } else if (arrivedActivityIndex == 1 && state.firstTripDelayTicks.isEmpty) {
          state.copy(firstTripDelayTicks = firstTripDelay)
        } else {
          state
        }

      case None =>
        state
    }

  /** Apply schedule truncation if enabled and not already done.
    *
    * Truncates activities whose endTime exceeds simulation duration.
    * Should be called once on first actSpontaneous.
    *
    * @param state Current person state
    * @return Updated PersonState with truncated schedule
    */
  def applyTruncationIfNeeded(state: PersonState): PersonState =
    if (scheduleAlreadyTruncated || state == null) {
      state
    } else {
      scheduleAlreadyTruncated = true

      val truncate = SimulatorSettingsRegistry
        .get("htc.person.truncate-schedule-beyond-duration")
        .orElse(sys.env.get("HTC_PERSON_TRUNCATE_SCHEDULE"))
        .orElse(configProvider("htc.person.truncate-schedule-beyond-duration"))
        .exists(_.equalsIgnoreCase("true"))

      if (truncate && state.dailySchedule.nonEmpty) {
        SimulatorSettingsRegistry
          .get("htc.simulation.duration")
          .flatMap(s => scala.util.Try(s.toLong).toOption) match {
          case None =>
            logWarn(s"$personId htc.simulation.duration not set in registry — schedule truncation skipped")
            state
          case Some(duration) =>
            val (filtered, removedActivities) = state.dailySchedule.partition { activity =>
              parseTick(activity.endTime).forall(_ <= duration)
            }
            val removed = removedActivities.size
            if (removed > 0) {
              val byType = removedActivities.groupBy(_.activityType).view.mapValues(_.size).toMap
              byType.foreach { case (actType, count) =>
                PersonMetrics.personTruncatedActivity.labels(actType).inc(count.toDouble)
              }
              // Histogram: how many ticks beyond sim duration each removed activity falls
              removedActivities.foreach { activity =>
                parseTick(activity.endTime).foreach { endTick =>
                  val excess = (endTick - duration).toDouble
                  PersonMetrics.personTruncatedActivityExcessTicks
                    .labels(activity.activityType)
                    .observe(excess)
                }
              }
              reportFn(
                Map(
                  "event_type"          -> "schedule_truncated",
                  "person_id"           -> personId,
                  "removed_count"       -> removed,
                  "remaining_count"     -> filtered.size,
                  "simulation_duration" -> duration,
                  "removed_by_type"     -> byType.map { case (t, c) => s"$t=$c" }.mkString(",")
                ),
                "person_schedule_truncated"
              )
              logDebug(
                s"$personId truncated $removed activities with endTime > $duration" +
                  s" (${filtered.size} remaining): ${byType.map { case (t, c) => s"$t=$c" }.mkString(", ")}"
              )
              state.copy(dailySchedule = filtered)
            } else {
              state
            }
        }
      } else {
        state
      }
    }
}
