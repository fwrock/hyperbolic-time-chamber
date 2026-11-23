package org.interscity.htc
package model.mobility.support.bus

import core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.state.BusState
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Moving}
import org.interscity.htc.model.mobility.util.SpeedUtil.linkEntryDensitySpeed

/** Handles MESO link enter/leave events for Bus actors.
  *
  * Responsibilities:
  *   - handleEnterLink: speed calculation, SUMO depart tracking
  *   - handleLeaveLink: distance accumulation, route completion check
  */
class BusLinkHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: BusJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val onFinishDestructFn: () => Unit,
  private val finishJourneyFn: (String, Long) => Unit,
  private val setMesoExitTickFn: Option[Tick] => Unit,
  private val restoreRouteIfMissingFn: String => Unit,
  // Defaulted to conservative-mode-safe no-ops so existing/other in-flight callers that predate
  // this Time Warp wiring keep compiling unchanged; Bus.scala's real construction site passes all
  // four explicitly -- same pattern as CarLinkHandler/BicycleLinkHandler.
  private val logDebugFn: String => Unit = _ => (),
  private val isTimeWarpFn: () => Boolean = () => false,
  private val isAwaitingReplyFn: (String, String, Long) => Boolean = (_, _, _) => false,
  private val consumeAwaitedReplyFn: String => Unit = _ => ()
) {

  private def enterLinkAwaitKey(linkId: Long): String = model.mobility.actor.Movable.enterLinkAwaitKey(linkId)
  private def leaveLinkAwaitKey(linkId: Long): String = model.mobility.actor.Movable.leaveLinkAwaitKey(linkId)

  def handleEnterLink(linkId: Long, data: LinkInfoData, state: BusState): Unit = {
    val tick     = if (data.entryTick >= 0L) data.entryTick else currentTickFn()
    val entityId = entityIdFn()

    // EnterLinkData -> ReceiveEnterLinkInfo is single-shot/terminal (see
    // CarLinkHandler.handleEnterLink), so always safe to consume once received.
    consumeAwaitedReplyFn(enterLinkAwaitKey(linkId))
    state.journey.mesoLinkLength = data.linkLength
    state.journey.leaveAckRequired = data.leaveAckRequired

    val speed = linkEntryDensitySpeed(
      length       = data.linkLength,
      capacity     = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed    = data.linkFreeSpeed,
      lanes        = data.linkLanes
    )
    val speedMs = speed / 3.6
    val time = if (speed > 0.0) data.linkLength / speedMs else data.linkLength

    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / (math.max(0.1, data.linkFreeSpeed) / 3.6)
    journeyReporter.updateHaltingState(speed, 0.0)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = speed
      journeyReporter.sumoDepartLane  = Some(s"${linkId}_0")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"  -> "enter_link",
        "bus_id"      -> entityId,
        "link_id"     -> linkId,
        "mode"        -> "MESO",
        "passengers"  -> state.people.size,
        "capacity"    -> state.capacity,
        "occupancy"   -> state.occupancyPercentage,
        "travel_time" -> time,
        "tick"        -> tick
      ),
      "enter_link"
    )

    // Legacy truncated the traversal time; with carried rounding the exit follows the continuous time (at least one tick).
    val exitTick = if (model.mobility.util.VehicleSimulationConfig.mesoCarryRounding) model.mobility.support.car.MesoLinkTiming.exitTick(tick, time, originAtEnd = false, carry = true, state.journey) else tick + time.toLong
    setMesoExitTickFn(Some(exitTick))
    // A reply the Link held until the exit tick: the caller continues the wake in this very callback.
    if (!(data.entryTick >= 0L && exitTick <= currentTickFn())) onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: Long, data: LinkInfoData, state: BusState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    // Conservative mode has no staleness guard here at all (pre-existing behavior, unchanged).
    // Under Time Warp, a rollback can restore state to a point before this reply's originating
    // LeaveLinkData was sent -- consult the rollback-immune awaited-reply marker so a genuine,
    // un-retractable reply is never silently dropped (docs/TIME_WARP_DESIGN.md §10; see
    // CarSignalHandler.handleLinkAccess for the reference implementation of this pattern).
    val awaitKey = leaveLinkAwaitKey(linkId)
    if (isTimeWarpFn()) {
      if (!isAwaitingReplyFn(awaitKey, "LeaveLinkData", data.requestSeq)) {
        logDebugFn(
          s"${entityIdFn()}: Discarding stale ReceiveLeaveLinkInfo for link $linkId " +
            s"(no matching outstanding request under Time Warp)."
        )
        return
      }
    }
    consumeAwaitedReplyFn(awaitKey)
    completeLeave(linkId, data.linkLength, state)
  }

  /** A plain MESO link only repeats the length the bus stored at entry, so the bus completes the leave itself. */
  def leaveWithoutReply(linkId: Long, state: BusState): Unit =
    completeLeave(linkId, state.journey.mesoLinkLength, state)

  private def completeLeave(linkId: Long, linkLength: Double, state: BusState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()
    state.distance += linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = linkLength
    journeyReporter.updateHaltingState(0.0, 0.0)
    setMesoExitTickFn(None)
    state.journey.leaveAckRequired = true
    state.journey.leaveHandledByLink = false
    val linkOwnedExit = state.journey.linkOwnedExit
    state.journey.linkOwnedExit = false

    reportFn(
      Map(
        "event_type"     -> "leave_link",
        "bus_id"         -> entityId,
        "link_id"        -> linkId,
        "mode"           -> "MESO",
        "passengers"     -> state.people.size,
        "total_distance" -> state.distance,
        "tick"           -> tick
      ),
      "leave_link"
    )

    restoreRouteIfMissingFn("ReceiveLeaveLinkInfo")
    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      finishJourneyFn("reached_destination", state.destination)
      onFinishDestructFn()
    } else {
      // The time manager grants a same-tick continuation only to a wake that has not yet run twice in the tick, and the
      // exit chain always has: with the Link owning the exit it runs inline, so the continuation moves to the next tick here.
      onFinishSpontaneousFn(Some(if (model.mobility.util.VehicleSimulationConfig.mesoImmediateNodeTransfer && !linkOwnedExit) tick else tick + 1))
    }
  }
}
