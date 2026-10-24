package org.interscity.htc
package model.hybrid.actor

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.actor.manager.loadbalance.migration.MigrationSnapshot
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ SubwayLoadPassengerData, SubwayRequestPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData }
import org.interscity.htc.model.hybrid.support.subway.SubwayPassengerHandler
import org.interscity.htc.model.hybrid.entity.state.SubwayState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Finished, Moving, Ready, Start, Stopped, Waiting }
import org.interscity.htc.model.hybrid.util.SubwayUtil
import org.interscity.htc.model.hybrid.util.SubwayUtil.timeToNextStation
import org.interscity.htc.core.metrics.model.hybrid.{ MovableMetrics, SubwayMetrics }
import core.actor.trace.ActorTrace

/** Subway actor - Metro train following predefined rail routes.
  *
  * IMPORTANT: Subways use PREDEFINED routes (bestRoute) created by SubwayStation. Unlike cars/buses
  * that use dynamic routing (GraphRouter), subway trains follow fixed rail lines that never change.
  *
  * Key characteristics:
  *   - Uses RAIL_LINKS (not road links) - exclusive subway infrastructure
  *   - Route is predefined at creation by SubwayStation (bestRoute field)
  *   - Follows digital rail path (stations in order)
  *   - No dynamic rerouting - trains stay on their assigned line
  *   - RailLink validates vehicle type (only Subway can enter)
  *
  * Flow:
  *   1. SubwayStation creates Subway with bestRoute = rail_link IDs 2. Subway calls enterLink()
  *      with next rail_link from bestRoute 3. RailLink validates vehicle type (Subway ✓) and sends
  *      LinkInfoData 4. Subway travels through rail_link (no congestion) 5. Subway calls
  *      leavingLink() when reaching next station 6. Repeat for next rail_link in bestRoute
  *
  * @param properties
  *   Actor properties
  */
class Subway(
  private val properties: Properties
) extends Movable[SubwayState](
      properties = properties
    ) {

  // protected, not private: lets SubwayMigrationSnapshotSpec drive this directly, same rationale
  // as Car.scala's link-wait fields.
  protected var expectedUnloadResponses: Int = 0
  private val loadReplyKey = "subway-stop:load"
  private val unloadReplyKey = "subway-stop:unload"

  /** `Subway` had **no** `buildMigrationSnapshot`/`applyMigrationSnapshot` override at all before
    * this fix (`docs/TIME_WARP_DESIGN.md`'s checkpoint-completeness audit, 2026-08-07) —
    * `expectedUnloadResponses` (a genuine reply-count barrier, same mechanism as `Bus`'s) was
    * silently lost on any restore. Reuses `MigrationSnapshot`'s `expectedUnloadResponses` field,
    * shared with `Bus`'s identical fix.
    */
  private def captureSubwayMigrationFields(base: MigrationSnapshot): MigrationSnapshot =
    base.copy(expectedUnloadResponses = expectedUnloadResponses)

  /** Restores what [[captureSubwayMigrationFields]] captured. */
  private def restoreSubwayMigrationFields(snapshot: MigrationSnapshot): Unit =
    expectedUnloadResponses = snapshot.expectedUnloadResponses

  override protected def buildMigrationSnapshot(): MigrationSnapshot =
    captureSubwayMigrationFields(super.buildMigrationSnapshot())

  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    super.applyMigrationSnapshot(snapshot)
    restoreSubwayMigrationFields(snapshot)
  }

  /** Maximum simulation end tick. Unlike `Car`/`Bus`/`Motorcycle`, `Subway` never had this guard —
    * found running a real end-to-end scenario under Time Warp (docs/TIME_WARP_DESIGN.md): a
    * `Subway` stuck in `Movable`'s generic `Waiting`-state recovery loop (e.g. because the
    * `RailLink` it's entering never replies) reschedules itself forever with no upper bound, which
    * keeps its `OptimisticLocalTimeManager` permanently non-idle -- the simulation can never reach
    * a GVT plateau and terminate. Every other vehicle type already force-finishes past this tick;
    * `Subway` needs the identical safety net.
    */
  private lazy val simulationEndTick: Tick =
    model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  private lazy val passengerHandler = new SubwayPassengerHandler(
    getStateFn       = () => state,
    entityIdFn       = () => getEntityId,
    currentTickFn    = () => currentTick,
    getCurrentNodeFn = () => getCurrentNode,
    selfRefFn        = () => self,
    reportFn         = (data, label) => report(data, label),
    sendMessageFn    = (entityId, shardId, data) => sendMessageTo(entityId, shardId, data),
    scheduleEventFn  = tick => scheduleEvent(tick),
    logInfoFn        = logInfo,
    logWarnFn        = logWarn
  )

  /** Subway trains operate on the rail network, not the road network.
   *  Rail link IDs are resolved directly to their actor reference, bypassing
   *  the city-map lookup used by road-based movables.
   */
  override protected def resolveLink(linkId: Long): Option[(Long, String)] =
    Some((linkId, "hybrid.actor.RailLink"))

  override protected def onHorizonFinalization(horizon: Tick): Unit =
    if (state.status != Finished && state.departureTick.nonEmpty)
      model.hybrid.support.JourneyHorizonReporter.report("subway", getEntityId, state.origin, state.destination,
        state.departureTick.get, horizon, state.distance, state.currentPath.map(_._1),
        (data, label) => report(data ++ Map("line" -> state.line), label))

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (
      !usesAccountedRuntime && !model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.status != Finished
    ) {
      logWarn(
        s"Subway ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing."
      )
      onFinishSpontaneous(None, destruct = true)
      return
    }

    val routeSize = state.bestRoute.map(_.size).getOrElse(0)
    state.status match
      case Start =>
        state.status = Ready
        SubwayMetrics.journeysStarted.labels(state.line).inc()
        MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
        report(
          data = Map(
            "event_type" -> "journey_started",
            "subway_id" -> getEntityId,
            "line" -> state.line,
            "origin" -> state.origin,
            "destination" -> state.destination,
            "capacity" -> state.capacity,
            "tick" -> currentTick
          ),
          label = "journey_started"
        )
        // ActorTrace.trace(getEntityId, currentTick, "subway_journey_started", // #actor-trace
        //   s"line=${state.line} origin=${state.origin} destination=${state.destination} capacity=${state.capacity}") // #actor-trace
        enterLink()
      case Ready =>
        if (state.movableStatus == Waiting) {
          onFinishSpontaneous(Some(currentTick + 1))
        } else {
          enterLink()
        }
      case Moving =>
        if (!state.linkArrivalCounted) {
          state.distance += state.currentLinkLength
          state.linkArrivalCounted = true
          report(Map("event_type" -> "subway_arrival", "subway_id" -> getEntityId,
            "link_id" -> state.currentPath.map(_._1).getOrElse(0L), "line" -> state.line,
            "distance_traveled" -> state.currentLinkLength, "total_distance" -> state.distance,
            "tick" -> currentTick), "subway_arrival")
        }
        val nodeId = getCurrentNode
        val stationOpt = retrieveSubwayStationFromNodeId(nodeId)
        stationOpt match {
          case Some(stationId) =>
            state.status = Stopped
            // ActorTrace.trace(getEntityId, currentTick, "subway_stop_arrived", // #actor-trace
            //   s"line=${state.line} node=$nodeId passengers=${state.passengers.size}") // #actor-trace
            expectedUnloadResponses = state.passengers.size
            passengerHandler.requestUnloadPeopleData()
            if (expectedUnloadResponses > 0) registerAwaitedReply(unloadReplyKey, "SubwayUnloadPassengerData")
            passengerHandler.requestLoadPassenger(stationOpt)
            registerAwaitedReply(loadReplyKey, "SubwayLoadPassengerData")
            onFinishSpontaneous(None)
          case None =>
            leavingLink()
        }
      case Stopped =>
        leavingLink()
      case other =>
        super.actSpontaneous(event)
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SubwayLoadPassengerData =>
        passengerHandler.handleLoadPeople(event, d)
        consumeAwaitedReply(loadReplyKey)
      case d: SubwayUnloadPassengerData =>
        if (expectedUnloadResponses > 0) {
          expectedUnloadResponses = passengerHandler.handleUnloadPassenger(event, d, expectedUnloadResponses)
          if (expectedUnloadResponses == 0) consumeAwaitedReply(unloadReplyKey)
        } else
          logWarn(s"[SUBWAY] Spurious SubwayUnloadPassengerData from ${event.actorRefId} — ignoring (no unload in progress) id=$getEntityId")
      case _                            => super.actInteractWith(event)
    }

  private def retrieveSubwayStationFromNodeId(value: Long): Option[Long] =
    state.subwayStations.find {
      case (_, v) => v == value
    }.map(_._1)

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val awaitKey = Movable.leaveLinkAwaitKey(event.actorRefId)
    // A dwell ends in Stopped, not Moving. Match the request before changing distance
    // or releasing its dispatch, including under conservative execution.
    if (matchesAwaitedReply(awaitKey, "LeaveLinkData", data.requestSeq)) {
      consumeAwaitedReply(awaitKey)
      if (!state.linkArrivalCounted) state.distance += data.linkLength
      state.currentLinkLength = 0.0
      report(
        data = Map(
          "event_type" -> "leave_link",
          "subway_id" -> getEntityId,
          "link_id" -> event.actorRefId,
          "line" -> state.line,
          "passengers" -> state.passengers.size,
          "total_distance" -> state.distance,
          "tick" -> currentTick
        ),
        label = "leave_link"
      )
      state.status = Ready
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    // EnterLinkData -> ReceiveEnterLinkInfo is single-shot/terminal (see
    // CarLinkHandler.handleEnterLink), so always safe to consume once received.
    consumeAwaitedReply(Movable.enterLinkAwaitKey(event.actorRefId))

    if (data.linkLength <= 0 || data.linkFreeSpeed <= 0) {
      logWarn(
        s"[SUBWAY] REJECTED by RailLink ${event.actorRefId}: zero linkLength=${data.linkLength} or speed=${data.linkFreeSpeed} " +
          s"id=$getEntityId tick=$currentTick — retrying tick+1"
      )
      state.status = Ready
      state.movableCurrentPath = None
      onFinishSpontaneous(Some(currentTick + 1))
      return
    }
    val effectiveVelocity = state.velocity * math.max(0.5, math.min(1.5, state.speedFactor))
    if (state.departureTick.isEmpty) state.departureTick = Some(currentTick)
    state.currentLinkLength = data.linkLength
    state.linkArrivalCounted = false
    val time = timeToNextStation(
      distance = data.linkLength,
      velocity = effectiveVelocity
    )
    state.status = Moving
    report(
      data = Map(
        "event_type" -> "enter_link",
        "subway_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "line" -> state.line,
        "passengers" -> state.passengers.size,
        "capacity" -> state.capacity,
        "travel_time" -> time,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  override def getNextPath: Option[(Long, Long)] =
    state.bestRoute match
      case Some(routePath) =>
        if state.currentPathPosition < routePath.size then
          val nextPath = routePath(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        else
          state.currentPathPosition = 1
          Some(routePath(0))
      case None =>
        None
}
