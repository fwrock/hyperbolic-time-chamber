package org.interscity.htc
package model.mobility.actor

import model.mobility.entity.state.CarState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.mobility.util.{ CityMapUtil, GPSUtilWithCache, SpeedUtil }
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Finished, Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.core.metrics.model.hybrid.MovableMetrics
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

class Car(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match {
      case Moving =>
        requestSignalState()
      case WaitingSignal =>
        leavingLink()
      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))
      case _ => super.actSpontaneous(event)
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case _                  => super.actInteractWith(event)
    }

  override def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }

    MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
    state.eventCount += 1

    try {
      state.movableStatus = RouteWaiting
      GPSUtilWithCache.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None

          // Report route planned
//          report(
//            data = Map(
//              "event_type" -> "route_planned",
//              "car_id" -> getEntityId,
//              "origin" -> state.origin,
//              "destination" -> state.destination,
//              "route_cost" -> cost,
//              "route_length" -> pathQueue.size,
//              "route_links" -> pathQueue.map(_._1).mkString(","),
//              "route_nodes" -> pathQueue.map(_._2).mkString(","),
//              "tick" -> currentTick
//            ),
//            label = "route_planned"
//          )
          state.eventCount += 1

          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            // Car already at destination
            val duration = currentTick - state.startTick
            MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
            MovableMetrics.journeySuccesses.labels(getClass.getSimpleName).inc()
            MovableMetrics.journeyCompletedReason.labels("car", "already_at_destination", "true").inc()
            if (duration > 0) MovableMetrics.journeyDurationTicks.labels(getClass.getSimpleName).observe(duration.toDouble)
            MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
            state.eventCount += 1

//            report(
//              data = Map(
//                "event_type" -> "vehicle_event_count",
//                "car_id" -> getEntityId,
//                "total_events" -> state.eventCount,
//                "tick" -> currentTick
//              ),
//              label = "vehicle_event_count"
//            )

            state.movableStatus = Finished
            onFinishSpontaneous()
          }
        case None =>
          logError(
            s"Failed to calculate route from ${state.origin} to ${state.destination} for car ${getEntityId}."
          )
          GPSMetrics.gpsCannotFindRoute.labels("car").inc()
          MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
          MovableMetrics.journeyFailures.labels(getClass.getSimpleName, "route_calculation_failed").inc()
          MovableMetrics.journeyCompletedReason.labels("car", "route_calculation_failed", "false").inc()
          MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
          state.eventCount += 1

//          report(
//            data = Map(
//              "event_type" -> "vehicle_event_count",
//              "car_id" -> getEntityId,
//              "total_events" -> state.eventCount,
//              "tick" -> currentTick
//            ),
//            label = "vehicle_event_count"
//          )

          state.movableStatus = Finished
          onFinishSpontaneous()
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during route request for ${getEntityId}: ${e.getMessage}", e)
        MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
        MovableMetrics.journeyFailures.labels(getClass.getSimpleName, "exception_during_route_request").inc()
        MovableMetrics.journeyCompletedReason.labels("car", "exception_during_route_request", "false").inc()
        MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
        state.eventCount += 1

//        report(
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        state.movableStatus = Finished
        onFinishSpontaneous()
    }
  }

  private def requestSignalState(): Unit =
    if (
      state.destination == state.currentPath
        .map(
          p => p._2.actorId
        )
        .orNull || state.movableBestRoute.isEmpty
    ) {
      val currentNodeId = getCurrentNode
      if (currentNodeId != null) {
//        report(
          val duration = currentTick - state.startTick
          val reached = state.destination == currentNodeId
          MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
          if (reached) MovableMetrics.journeySuccesses.labels(getClass.getSimpleName).inc()
          else MovableMetrics.journeyFailures.labels(getClass.getSimpleName, "end_of_route").inc()
          MovableMetrics.journeyCompletedReason.labels("car", "reached_destination_or_end_of_route", s"$reached").inc()
          if (duration > 0) MovableMetrics.journeyDurationTicks.labels(getClass.getSimpleName).observe(duration.toDouble)
          MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
          state.eventCount += 1
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        state.movableStatus = Finished
        onFinishSpontaneous()
      } else {
        state.movableStatus = Finished
        MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
        MovableMetrics.journeyFailures.labels(getClass.getSimpleName, "no_current_node").inc()
        MovableMetrics.journeyCompletedReason.labels("car", "no_current_node", "false").inc()
        MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
        state.eventCount += 1

//        report(
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        onFinishSpontaneous()
      }
      selfDestruct()
    } else {
      state.movableStatus = WaitingSignalState
      getCurrentNode match
        case nodeId =>
          CityMapUtil.nodesById.get(nodeId) match
            case Some(node) =>
              getNextLink match
                case linkId =>
                  sendMessageTo(
                    entityId = node.id,
                    shardId = node.classType,
                    RequestSignalStateData(
                      targetLinkId = linkId
                    ),
                    EventTypeEnum.RequestSignalState.toString
                  )
                case null =>
            case None =>
        case null =>
    }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    // Guard against stale/duplicate SignalStateData due to retry race condition
    if (state.movableStatus != WaitingSignalState) {
      return
    }
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }
  }

  override def leavingLink(): Unit = {
    state.movableStatus = Ready
    super.leavingLink()
  }

  override protected def onFinish(nodeId: String): Unit = {
//    report(
    val duration = currentTick - state.startTick
    val reached = state.destination == nodeId
    MovableMetrics.journeysCompleted.labels(getClass.getSimpleName).inc()
    if (reached) MovableMetrics.journeySuccesses.labels(getClass.getSimpleName).inc()
    else MovableMetrics.journeyFailures.labels(getClass.getSimpleName, "destination_not_reached").inc()
    MovableMetrics.journeyCompletedReason.labels("car", "on_finish", s"$reached").inc()
    if (duration > 0) MovableMetrics.journeyDurationTicks.labels(getClass.getSimpleName).observe(duration.toDouble)
    MovableMetrics.journeyDistanceMeters.labels(getClass.getSimpleName).observe(state.distance)
    state.eventCount += 1

//    report(
//      data = Map(
//        "event_type" -> "vehicle_event_count",
//        "car_id" -> getEntityId,
//        "total_events" -> state.eventCount,
//        "tick" -> currentTick
//      ),
//      label = "vehicle_event_count"
//    )

    // Implement parent class logic without calling super
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
    } else {
      state.movableStatus = Finished
    }

    // Finish actor
    onFinishSpontaneous()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength

//    report(
//      data = Map(
//        "event_type" -> "leave_link",
//        "car_id" -> getEntityId,
//        "link_id" -> event.actorRefId,
//        "link_length" -> data.linkLength,
//        "total_distance" -> state.distance,
//        "tick" -> currentTick
//      ),
//      label = "leave_link"
//    )
    state.eventCount += 1

    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val speed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )

    val time = data.linkLength / speed
    state.movableStatus = Moving

//    report(
//      data = Map(
//        "event_type" -> "enter_link",
//        "car_id" -> getEntityId,
//        "link_id" -> event.actorRefId,
//        "link_length" -> data.linkLength,
//        "link_capacity" -> data.linkCapacity,
//        "cars_in_link" -> data.linkNumberOfCars,
//        "free_speed" -> data.linkFreeSpeed,
//        "calculated_speed" -> speed,
//        "travel_time" -> time,
//        "lanes" -> data.linkLanes,
//        "tick" -> currentTick
//      ),
//      label = "enter_link"
//    )
    state.eventCount += 1

    if (time.isNaN || time.isInfinite || time < 0) {
      logError(
        s"Invalid time calculated for link ${data} with length ${data.linkLength} and velocity $speed, current tick $currentTick. ${(currentTick + Math.ceil(time).toLong)}"
      )
    }
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
}
