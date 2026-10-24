package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, LinkCapacityStateEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Ready, WaitingCapacity, WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

/** Handles traffic signal and downstream-link-capacity interaction for Car actors.
  *
  * Responsibilities:
  *   - requestSignalState: determine if at destination or request link access from node
  *   - handleLinkAccess: react to Green/Red/capacity-Full response (or a later, unsolicited
  *     capacity-freed grant), guard against stale duplicates
  */
class CarSignalHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: CarJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val scheduleEventFn: Tick => Unit,
  private val leavingLinkFn: () => Unit,
  private val logWarnFn: String => Unit,
  private val logStaleEventDebugFn: String => Unit,
  private val sendMessageFn: (Long, String, AnyRef, String) => Unit,
  private val getCurrentNodeFn: () => Long,
  private val getNextLinkFn: () => Long,
  private val getTripDestinationFn: () => Option[Long],
  private val setSignalWaitUntilTickFn: Option[Tick] => Unit,
  private val setSignalWaitNeedsReverifyFn: Boolean => Unit,
  private val onSignalWaitFn: Long => Unit,
  // Defaulted to conservative-mode-safe no-ops so existing/other in-flight callers that predate
  // this Time Warp wiring keep compiling unchanged; Car.scala's real construction site passes all
  // four explicitly.
  private val isTimeWarpFn: () => Boolean = () => false,
  private val registerAwaitedReplyFn: (String, String) => Unit = (_, _) => (),
  private val isAwaitingReplyFn: (String, String, Long) => Boolean = (_, _, _) => false,
  private val consumeAwaitedReplyFn: String => Unit = _ => ()
) {

  /** Correlation key for the one outstanding `RequestLinkAccessData` -> `LinkAccessData` exchange
    * for a given target link. Keyed on `linkId` (not e.g. entityId, which is constant per car) so a
    * request to a *different* link never supersedes/consumes this one, while a second request to
    * the *same* link correctly does (docs/TIME_WARP_DESIGN.md's rollback-orphaned-reply fix).
    */
  private def linkAccessAwaitKey(linkId: Long): String = s"link-access:$linkId"

  def requestSignalState(state: CarState): Unit = {
    val currentPathNode = state.currentPath.map(_._2).orNull
    val routeDepleted   = state.bestRoute.forall(_.isEmpty)

    if (currentPathNode == null && !routeDepleted) {
      logWarnFn(
        s"${entityIdFn()} requestSignalState with null currentPathNode but non-empty route " +
          s"at tick=${currentTickFn()}; recovering to Ready"
      )
      state.status = Ready
      onFinishSpontaneousFn(Some(currentTickFn() + 1))
      return
    }

    if (getTripDestinationFn().getOrElse(state.destination) == currentPathNode || routeDepleted) {
      leavingLinkFn()
    } else {
      state.status = WaitingSignalState
      val nodeId = getCurrentNodeFn()
      if (nodeId != 0L) {
        CityMapUtil.nodesById.get(nodeId) match {
          case Some(node) =>
            val linkId = getNextLinkFn()
            if (linkId != 0L) {
              sendMessageFn(
                node.id,
                node.classType,
                RequestLinkAccessData(targetLinkId = linkId, signalAlreadyCleared = state.movableLinkExitReported, approachLinkId = state.currentPath.map(_._1).getOrElse(0L)),
                EventTypeEnum.RequestLinkAccess.toString
              )
              registerAwaitedReplyFn(linkAccessAwaitKey(linkId), "LinkAccessData")
              onFinishSpontaneousFn(None)
            } else {
              leavingLinkFn()
            }
          case None =>
            leavingLinkFn()
        }
      } else {
        leavingLinkFn()
      }
    }
  }

  /** @param eventTick the sender's tick for this reply (`ActorInteractionEvent.tick`) -- logged,
    *   not otherwise used, to diagnose the suspected Time-Warp rollback/reply-drop mechanism
    *   (`docs/TIME_WARP_DESIGN.md` §10): a rollback that restores `state.status` from before this
    *   reply's originating `RequestLinkAccess` was sent can make a genuinely-awaited reply look
    *   "stale" to the guard below, even though the request it answers was never retracted.
    * @param eventSeq the sender's Time-Warp send sequence (`ActorInteractionEvent.seq`), logged for
    *   the same reason; `0L` outside Time Warp.
    */
  def handleLinkAccess(data: LinkAccessData, state: CarState, eventTick: Tick = 0L, eventSeq: Long = 0L): Unit = {
    val linkId = getNextLinkFn()
    val awaitKey = linkAccessAwaitKey(linkId)
    val isStale =
      if (isTimeWarpFn())
        !isAwaitingReplyFn(awaitKey, "LinkAccessData", data.requestSeq)
      else
        state.status != WaitingSignalState && state.status != WaitingCapacity
    if (isStale) {
      logWarnFn(
        s"${entityIdFn()}: Ignoring stale LinkAccessData at currentTick=${currentTickFn()} " +
          s"(eventTick=$eventTick, eventSeq=$eventSeq, actual state.status=${state.status}, " +
          s"expected WaitingSignalState/WaitingCapacity) -- possible rollback-orphaned reply, see " +
          s"docs/TIME_WARP_DESIGN.md §10"
      )
      return
    }

    // Full/WaitingCapacity is NOT a terminal reply: NodeEventHandler buffers this request and
    // sends a second, unsolicited LinkAccessData later (the capacity-freed grant) for the SAME
    // key -- consuming here would make that later, genuinely-awaited reply look stale. Every other
    // branch below resolves the wait (self-schedules via scheduleEventFn instead), so it's safe to
    // consume there.
    if (data.capacityState != LinkCapacityStateEnum.Full) consumeAwaitedReplyFn(awaitKey)

    if (data.phase == Red) {
      journeyReporter.sumoArrivalSpeed = 0.0
      val tick = currentTickFn()
      val headwayTicks     = 2L
      val waitUntilTick    = data.nextTick + data.queuePosition.toLong * headwayTicks
      val waitTicks        = math.max(0L, waitUntilTick - tick)
      if (waitTicks > 0) journeyReporter.updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      if (waitTicks > 0) onSignalWaitFn(waitTicks)

      reportFn(
        Map(
          "event_type"         -> "signal_wait",
          "vehicle_type"       -> "car",
          "vehicle_id"         -> entityIdFn(),
          "phase"              -> data.phase.toString,
          "queue_position"     -> data.queuePosition,
          "wait_until_tick"    -> waitUntilTick,
          "adjusted_next_tick" -> waitUntilTick,
          "tick"               -> tick
        ),
        "signal_wait"
      )

      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(waitUntilTick))
      setSignalWaitNeedsReverifyFn(true)
      scheduleEventFn(waitUntilTick)
    } else if (data.capacityState == LinkCapacityStateEnum.Full) {
      journeyReporter.sumoArrivalSpeed = 0.0
      state.status = WaitingCapacity
    } else {
      data.junctionLink.foreach(path => state.movablePendingJunctionPath = Some(path))
      val tick = currentTickFn()
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(tick))
      setSignalWaitNeedsReverifyFn(false)
      scheduleEventFn(tick)
    }
  }

  /** Call from `onDestruct`, before any state clearing, when a car is destroyed while
    * `WaitingCapacity` (buffered at a Node, never yet granted) — otherwise that stale buffer
    * entry sits forever and could eventually waste a real capacity slot on a dead actor. No-op
    * for any other status. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  def cancelPendingCapacityRequest(state: CarState): Unit =
    if (state.status == WaitingCapacity) {
      val nodeId = getCurrentNodeFn()
      val linkId = getNextLinkFn()
      if (nodeId != 0L && linkId != 0L) {
        CityMapUtil.nodesById.get(nodeId).foreach { node =>
          sendMessageFn(
            node.id,
            node.classType,
            CancelLinkAccessRequestData(targetLinkId = linkId),
            EventTypeEnum.CancelLinkAccessRequest.toString
          )
        }
      }
    }
}
