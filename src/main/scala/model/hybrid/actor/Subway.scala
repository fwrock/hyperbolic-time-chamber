package org.interscity.htc
package model.hybrid.actor

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ SubwayLoadPassengerData, SubwayRequestPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData }
import org.interscity.htc.model.hybrid.support.subway.SubwayPassengerHandler
import org.interscity.htc.model.hybrid.entity.state.SubwayState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, Stopped }
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

  private var expectedUnloadResponses: Int = 0

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
  override protected def resolveLink(linkId: String): Option[(String, String)] =
    Some((linkId, "hybrid.actor.RailLink"))

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
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
        ActorTrace.trace(getEntityId, currentTick, "subway_journey_started", // #actor-trace
          s"line=${state.line} origin=${state.origin} destination=${state.destination} capacity=${state.capacity}") // #actor-trace
        logInfo(s"[SUBWAY] Start→Ready, calling enterLink. id=$getEntityId line=${state.line}")
        enterLink()
      case Ready =>
        logInfo(s"[SUBWAY] Ready, calling enterLink. id=$getEntityId path=${state.movableCurrentPath}")
        enterLink()
      case Moving =>
        val nodeId = getCurrentNode
        val stationOpt = if (nodeId != null) retrieveSubwayStationFromNodeId(nodeId) else None
        logInfo(
          s"[SUBWAY] Moving: nodeId=$nodeId stationFound=${stationOpt.isDefined} " +
          s"id=$getEntityId path=${state.movableCurrentPath}"
        )
        stationOpt match {
          case Some(stationId) =>
            state.status = Stopped
            logInfo(s"[SUBWAY] Stopping at station $stationId node=$nodeId id=$getEntityId")
            ActorTrace.trace(getEntityId, currentTick, "subway_stop_arrived", // #actor-trace
              s"line=${state.line} node=$nodeId passengers=${state.passengers.size}") // #actor-trace
            passengerHandler.requestUnloadPeopleData()
            passengerHandler.requestLoadPassenger(stationOpt)
            onFinishSpontaneous(None)
          case None =>
            logInfo(s"[SUBWAY] No station at nodeId=$nodeId, calling leavingLink id=$getEntityId")
            leavingLink()
        }
      case Stopped =>
        logInfo(s"[SUBWAY] Stopped, calling leavingLink id=$getEntityId path=${state.movableCurrentPath}")
        leavingLink()
      case other =>
        logInfo(s"[SUBWAY] Unhandled status=$other, delegating to super id=$getEntityId")
        super.actSpontaneous(event)
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SubwayLoadPassengerData   => passengerHandler.handleLoadPeople(event, d)
      case d: SubwayUnloadPassengerData =>
        expectedUnloadResponses = passengerHandler.handleUnloadPassenger(event, d, expectedUnloadResponses)
      case _                            => super.actInteractWith(event)
    }

  private def retrieveSubwayStationFromNodeId(value: String): Option[String] =
    state.subwayStations.find {
      case (_, v) => v == value
    }.map(_._1)

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    logInfo(
      s"[SUBWAY] ReceiveLeaveLinkInfo from ${event.actorRefId} | " +
      s"id=$getEntityId status=${state.status} tick=$currentTick " +
      s"linkLen=${data.linkLength} totalDist=${state.distance}"
    )
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
    // Only update status if the subway has not already moved on to the next
    // link (Waiting) or stopped at a station.  A late-arriving ReceiveLeaveLinkInfo
    // for a link we already departed must not override the Waiting status set
    // by enterLink() for the NEXT segment.
    import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum._
    if (state.status == Moving) {
      logInfo(s"[SUBWAY] ReceiveLeaveLinkInfo: status was Moving → setting Ready and scheduling tick+1 | id=$getEntityId")
      state.status = Ready
      onFinishSpontaneous(Some(currentTick + 1))
    } else {
      logInfo(s"[SUBWAY] ReceiveLeaveLinkInfo: status=${state.status} (not Moving) → suppressing duplicate FinishEvent | id=$getEntityId")
    }
    // If status is already Waiting/Ready/Stopped the subway is managing its
    // own scheduling; suppress the duplicate FinishEvent to avoid a spurious
    // extra tick.
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    logInfo(
      s"[SUBWAY] ReceiveEnterLinkInfo from ${event.actorRefId} | " +
      s"id=$getEntityId status=${state.status} tick=$currentTick " +
      s"linkLen=${data.linkLength} speed=${data.linkFreeSpeed}"
    )
    // Guard: zero-length / zero-speed response means the RailLink rejected us.
    // Schedule a retry at the next tick rather than computing time=0 (which
    // would reschedule at currentTick, potentially looping forever at the
    // same tick).
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
    val time = timeToNextStation(
      distance = data.linkLength,
      velocity = effectiveVelocity
    )
    logInfo(
      s"[SUBWAY] Entering rail link ${event.actorRefId} | id=$getEntityId line=${state.line} " +
      s"len=${data.linkLength}m speed=${effectiveVelocity}km/h travelTime=$time ticks"
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

  override def getNextPath: Option[(String, String)] =
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
