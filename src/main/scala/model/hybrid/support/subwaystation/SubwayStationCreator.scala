package org.interscity.htc
package model.hybrid.support.subwaystation

import org.interscity.htc.model.hybrid.entity.state.{ SubwayState, SubwayStationState }
import org.interscity.htc.model.hybrid.entity.state.model.{ RoutePathItem, SubwayInformation, SubwayLineInformation }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.IdUtil
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.metrics.model.hybrid.SubwayStationMetrics
import core.actor.trace.ActorTrace
import scala.collection.mutable

class SubwayStationCreator(
  getStateFn:          () => SubwayStationState,
  entityIdFn:          () => String,
  currentTickFn:       () => Tick,
  reportFn:            (Map[String, Any], String) => Unit,
  spawnDynamicActorFn: (String, String, String) => Unit,
  addDependencyFn:     (String, ShardActorId) => Unit,
  logWarnFn:           String => Unit,
  logDebugFn:          String => Unit,
  logErrorFn:          String => Unit
) {

  private def state: SubwayStationState = getStateFn()

  def createSubwayFrom(lines: mutable.Map[String, SubwayLineInformation]): Int = {
    var spawned = 0
    lines.keys.foreach { line =>
      state.subways.get(line) match
        case Some(subwayQueue) =>
          if (subwayQueue.nonEmpty && state.garage) {
            val subway = subwayQueue.dequeue()
            val subwayStartTick = currentTickFn() + lines(line).interval
            try {
              createSubway(subway, subwayStartTick)
              spawned += 1
              // ActorTrace.trace(entityIdFn(), currentTickFn(), "subway_station_train_created", // #actor-trace
              //   s"trainId=${subway.actorId} line=$line nextTick=$subwayStartTick") // #actor-trace
              addDependencyFn(subway.actorId, ShardActorId(subway.actorId, "hybrid.actor.Subway"))
              lines(line).nextTick = subwayStartTick
            } catch {
              case e: IllegalStateException =>
                logErrorFn(s"Failed to create subway ${subway.actorId} for line $line: ${e.getMessage}")
                subwayQueue.enqueue(subway)
              case e: Exception =>
                logErrorFn(s"Unexpected error creating subway ${subway.actorId} for line $line: ${e.getMessage}")
                subwayQueue.enqueue(subway)
            }
          }
        case None =>
          logWarnFn(s"Subway not found for line $line")
    }
    spawned
  }

  def filterLinesByNextTick(): mutable.Map[String, SubwayLineInformation] =
    state.lines.filter { case (_, line) => line.nextTick <= currentTickFn() }

  def computeNextTick(simulationEnd: Tick): Option[Tick] =
    state.lines.values.map { line =>
      if (line.nextTick <= currentTickFn()) {
        line.nextTick = currentTickFn() + line.interval
      }
      line.nextTick
    }
      .filter(_ < simulationEnd)
      .toList
      .sorted
      .headOption

  private def createSubway(subway: SubwayInformation, startTick: Long): Unit = {
    val route = convertLineRouteToPath(subway.line)
    if (route.isEmpty) {
      logWarnFn(s"Cannot create subway ${subway.actorId} for line ${subway.line} - no valid route available")
      throw new IllegalStateException(s"No route available for subway line ${subway.line}")
    }

    val subwayStations = convertLineToSubwayStations(subway.line)
    if (subwayStations.isEmpty) {
      logWarnFn(s"Cannot create subway ${subway.actorId} for line ${subway.line} - no subway stations available")
      throw new IllegalStateException(s"No subway stations available for line ${subway.line}")
    }

    val subwayState = {
      val s = SubwayState(
        startTick    = startTick,
        capacity     = subway.capacity,
        numberOfPorts = subway.numberOfPorts,
        velocity     = subway.velocity,
        stopTime     = subway.stopTime,
        line         = subway.line,
        subwayStations = subwayStations,
        origin       = state.nodeId,
        destination  = subwayStations.values.last,
        speedFactor  = subway.speedFactor
      )
      s.bestRoute    = Some(route)
      s.movableStatus = MovableStatusEnum.Start
      s
    }

    reportFn(
      Map(
        "event_type"        -> "subway_created",
        "station_id"        -> entityIdFn(),
        "subway_id"         -> subway.actorId,
        "line"              -> subway.line,
        "capacity"          -> subway.capacity,
        "velocity"          -> subway.velocity,
        "stop_time"         -> subway.stopTime,
        "route_length"      -> route.size,
        "number_of_stations"-> subwayStations.size,
        "tick"              -> currentTickFn()
      ),
      "subway_created"
    )

    SubwayStationMetrics.subwaysCreated.labels(subway.line).inc()
    spawnDynamicActorFn("hybrid.actor.Subway", IdUtil.format(subway.actorId), toJson(subwayState))
  }

  private def convertLineToSubwayStations(line: String): mutable.Map[String, String] = {
    val lineRoute     = state.linesRoute(line)
    val subwayStations = mutable.Map[String, String]()
    for (i <- lineRoute.indices)
      subwayStations.put(lineRoute(i)._1.stationId, lineRoute(i)._1.nodeId)
    subwayStations
  }

  private def convertLineRouteToPath(line: String): mutable.Queue[(String, String)] = {
    val route     = mutable.Queue[(String, String)]()
    val lineRoute = state.linesRoute.get(line)
    lineRoute match {
      case Some(routeQueue) =>
        routeQueue.foreach { routeEntry =>
          route.enqueue((routeEntry.railLinkId, routeEntry.stationNode.nodeId))
        }
        logDebugFn(s"Built route for line $line: ${route.size} segments using RAIL LINKS")
      case None =>
        logWarnFn(s"No route found for line $line")
    }
    route
  }
}
