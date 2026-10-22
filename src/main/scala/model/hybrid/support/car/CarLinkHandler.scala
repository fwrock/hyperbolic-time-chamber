package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Finished, Moving, Parked}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

/** Handles MESO link enter/leave events for Car actors.
  *
  * Responsibilities:
  *   - actHandleReceiveEnterLinkInfo: speed calculation, SUMO depart tracking
  *   - actHandleReceiveLeaveLinkInfo: distance accumulation, halting state, route completion
  */
class CarLinkHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: CarJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val finishAndCleanupFn: (String, Long) => Unit,
  private val logStaleEventDebugFn: String => Unit,
  private val setCurrentLinkIdFn: Option[Long] => Unit,
  private val setCurrentLinkLengthFn: Double => Unit,
  private val setLinkEntryTickFn: Option[Tick] => Unit,
  private val getLinkEntryTickFn: () => Option[Tick],
  private val setMesoExitTickFn: Option[Tick] => Unit,
  private val getTripDestinationFn: () => Option[Long],
  // Defaulted to conservative-mode-safe no-ops so existing/other in-flight callers that predate
  // this Time Warp wiring keep compiling unchanged; Car.scala's real construction site passes all
  // three explicitly -- same pattern as CarSignalHandler.
  private val isTimeWarpFn: () => Boolean = () => false,
  private val isAwaitingReplyFn: (String, String, Long) => Boolean = (_, _, _) => false,
  private val consumeAwaitedReplyFn: String => Unit = _ => ()
) {

  private def enterLinkAwaitKey(linkId: Long): String = model.hybrid.actor.Movable.enterLinkAwaitKey(linkId)
  private def leaveLinkAwaitKey(linkId: Long): String = model.hybrid.actor.Movable.leaveLinkAwaitKey(linkId)

  def handleEnterLink(linkId: Long, data: LinkInfoData, state: CarState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    // EnterLinkData -> ReceiveEnterLinkInfo is a single-shot, terminal reply (confirmed against
    // LinkVehicleFlowHandler.handleEnterLinkMeso -- unlike Node's capacity-Full case, the Link
    // never sends a second, later reply for the same request) -- but "single-shot" only means the
    // Link never sends a SECOND reply to the SAME request; it does NOT mean this specific delivery
    // can't be a reply to an OLDER, since-superseded request (e.g. Car re-sent EnterLinkData after
    // an unrelated Time Warp rollback). Same rollback-immune staleness gate as handleLeaveLink
    // below, added 2026-09-11 after a real run showed this exact reply dominating rollback volume
    // (docs/KNOWN_GAPS.md's Cause 3) -- Movable.isKnownSupersededReply now also short-circuits most
    // of these before they even reach here, but a defensive check at the point that actually
    // applies this data to `state` costs little and stays correct even if that upstream check ever
    // has a gap.
    val awaitKey = enterLinkAwaitKey(linkId)
    val isStale =
      if (isTimeWarpFn())
        !isAwaitingReplyFn(awaitKey, "EnterLinkData", data.requestSeq)
      else
        state.status == Parked || state.status == Finished
    if (isStale) {
      logStaleEventDebugFn(
        s"$entityId: Discarding stale ReceiveEnterLinkInfo for link $linkId " +
          s"(status=${state.status}, request already superseded)."
      )
      return
    }
    consumeAwaitedReplyFn(awaitKey)

    setCurrentLinkIdFn(Some(linkId))
    setCurrentLinkLengthFn(data.linkLength)
    setLinkEntryTickFn(Some(tick))

    val speed = linkDensitySpeed(
      length       = data.linkLength,
      capacity     = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed    = data.linkFreeSpeed,
      lanes        = data.linkLanes
    )

    val speedMs = speed / 3.6
    val time = data.linkLength / speedMs
    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / (math.max(0.1, data.linkFreeSpeed) / 3.6)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = speed
      journeyReporter.sumoDepartLane  = Some(s"${linkId}_0")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"      -> "enter_link",
        "car_id"          -> entityId,
        "link_id"         -> linkId,
        "mode"            -> "MESO",
        "link_length"     -> data.linkLength,
        "link_capacity"   -> data.linkCapacity,
        "cars_in_link"    -> data.linkNumberOfCars,
        "free_speed"      -> data.linkFreeSpeed,
        "calculated_speed" -> speed,
        "travel_time"     -> time,
        "lanes"           -> data.linkLanes,
        "tick"            -> tick
      ),
      "enter_link"
    )

    val exitTick = tick + Math.ceil(time).toLong
    setMesoExitTickFn(Some(exitTick))
    onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: Long, data: LinkInfoData, state: CarState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    // Guard: discard stale callbacks for already-finalised trips. Under Time Warp, a rollback can
    // restore state.status to Parked/Finished from before this reply's originating LeaveLinkData
    // was sent, making a genuine, un-retractable reply look stale to a status-only check -- so
    // consult the rollback-immune awaited-reply marker instead (see docs/TIME_WARP_DESIGN.md §10,
    // and CarSignalHandler.handleLinkAccess for the reference implementation of this pattern).
    val awaitKey = leaveLinkAwaitKey(linkId)
    val isStale =
      if (isTimeWarpFn())
        !isAwaitingReplyFn(awaitKey, "LeaveLinkData", data.requestSeq)
      else
        state.status == Parked || state.status == Finished
    if (isStale) {
      logStaleEventDebugFn(
        s"$entityId: Discarding stale ReceiveLeaveLinkInfo for link $linkId " +
          s"(status=${state.status}, trip already finalized)."
      )
      return
    }
    // LeaveLinkData -> ReceiveLeaveLinkInfo is single-shot/terminal (same as EnterLinkData above),
    // so always safe to consume once accepted.
    consumeAwaitedReplyFn(awaitKey)

    state.distance += data.linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = data.linkLength

    getLinkEntryTickFn().foreach { entryTick =>
      val travelTimeSecs = (tick - entryTick).toDouble // 1 tick = 1 second
      val actualSpeed    = if (travelTimeSecs > 0) data.linkLength / travelTimeSecs else 0.0
      journeyReporter.updateHaltingState(actualSpeed, travelTimeSecs)
    }

    setCurrentLinkIdFn(None)
    setCurrentLinkLengthFn(0.0)
    setLinkEntryTickFn(None)
    setMesoExitTickFn(None)

    reportFn(
      Map(
        "event_type"     -> "leave_link",
        "car_id"         -> entityId,
        "link_id"        -> linkId,
        "mode"           -> "MESO",
        "link_length"    -> data.linkLength,
        "total_distance" -> state.distance,
        "tick"           -> tick
      ),
      "leave_link"
    )

    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      val tripDest = getTripDestinationFn().getOrElse(state.destination)
      finishAndCleanupFn("reached_destination", tripDest)
    } else {
      onFinishSpontaneousFn(Some(tick + 1))
    }
  }
}
