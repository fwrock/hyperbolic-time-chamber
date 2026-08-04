package org.interscity.htc
package model.hybrid.support.busstation

import org.interscity.htc.model.hybrid.entity.state.{ BusState, BusStationState }
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.IdUtil
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.metrics.model.hybrid.BusStationMetrics
import core.actor.trace.ActorTrace
import scala.collection.mutable

class BusStationBusCreator(
  getStateFn:          () => BusStationState,
  entityIdFn:          () => String,
  currentTickFn:       () => Tick,
  reportFn:            (Map[String, Any], String) => Unit,
  spawnDynamicActorFn: (String, String, String) => Unit,
  routeCalculator:     BusStationRouteCalculator,
  logInfoFn:           String => Unit,
  logWarnFn:           String => Unit,
  logDebugFn:          String => Unit
) {

  private def state: BusStationState = getStateFn()

  def createBus(bus: BusInformation): Unit = {
    val route = calcBusBestRoute()
    if (route.isEmpty) {
      logWarnFn(s"Cannot create bus ${bus.actorId} - no valid route available")
      throw new IllegalStateException(s"No route available for bus ${bus.actorId}")
    }

    val busStartTick = currentTickFn() + state.interval
    val busState = {
      val s = BusState(
        startTick       = busStartTick,
        busStops        = state.busStops.toMap,
        capacity        = bus.capacity,
        size            = bus.size,
        origin          = state.origin,
        destination     = state.destination,
        numberOfPorts   = bus.numberOfPorts,
        label           = bus.label,
        storedBestRoute = Some(route.toList),
        speedFactor     = bus.speedFactor
      )
      s.bestRoute = Some(route.clone())
      s.status    = MovableStatusEnum.Start
      s
    }

    logDebugFn(s"Creating bus ${bus.actorId} at tick $busStartTick (route size=${route.size})")
    // ActorTrace.trace(entityIdFn(), currentTickFn(), "bus_station_create_bus", // #actor-trace
    //   s"busId=${bus.actorId} label=${bus.label} capacity=${bus.capacity} route=${route.size} startTick=$busStartTick") // #actor-trace

    reportFn(
      Map(
        "event_type"      -> "bus_created",
        "station_id"      -> entityIdFn(),
        "bus_id"          -> bus.actorId,
        "capacity"        -> bus.capacity,
        "route_length"    -> route.size,
        "number_of_ports" -> bus.numberOfPorts,
        "label"           -> bus.label,
        "start_tick"      -> busStartTick,
        "tick"            -> currentTickFn()
      ),
      "bus_created"
    )

    BusStationMetrics.busesCreated.labels(bus.label).inc()
    spawnDynamicActorFn("hybrid.actor.Bus", IdUtil.format(bus.actorId), toJson(busState))
  }

  private def calcBusBestRoute(): mutable.Queue[(String, String)] = {
    val bestRoute = mutable.Queue[(String, String)]()

    if (state.goingRoute.isDefined && state.goingRoute.get.nonEmpty) {
      val goingRouteData = getTotalRoute(state.goingRoute.get, routeCalculator.orderedBusStopIds)
      bestRoute ++= goingRouteData.map(pair => (pair._1.id, pair._2.id))
    } else {
      logWarnFn("No going route defined for bus station")
    }

    if (state.returningRoute.isDefined && state.returningRoute.get.nonEmpty) {
      val returningRouteData = getTotalRoute(state.returningRoute.get, routeCalculator.orderedBusStopIds.reverse)
      bestRoute ++= returningRouteData.map(pair => (pair._1.id, pair._2.id))
    } else {
      logWarnFn("No returning route defined for bus station")
    }

    if (bestRoute.isEmpty)
      logWarnFn("Bus route calculation resulted in empty route - this may cause buses to terminate immediately")

    bestRoute
  }

  private def getTotalRoute(
    route:        mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]],
    orderedStops: List[String]
  ): mutable.Queue[(Identify, Identify)] = {
    val totalRoute = mutable.Queue[(Identify, Identify)]()
    for (pair <- orderedStops.sliding(2)) {
      val key = SubRoutePair(pair.head, pair.last)
      route.get(key) match {
        case Some(pathPart) =>
          totalRoute ++= pathPart
          logDebugFn(s"Added route segment: ${pair.head} -> ${pair.last} (${pathPart.size} links)")
        case None =>
          logWarnFn(s"Missing route segment: ${pair.head} -> ${pair.last}")
          logWarnFn(s"Available route keys: ${route.keys.mkString(", ")}")
      }
    }
    totalRoute
  }
}
