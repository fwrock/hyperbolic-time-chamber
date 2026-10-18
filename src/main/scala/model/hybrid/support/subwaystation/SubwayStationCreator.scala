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

/** A dequeued-and-prepared subway (state built, route validated, `report()` already emitted)
  * whose irrevocable [[SubwayStationCreator.spawnPreparedSubway]] step has not yet run. See
  * `SubwayStationCreator.prepareSubwaysFrom`.
  */
case class PreparedSubway(subway: SubwayInformation, line: String, stateJson: String)

class SubwayStationCreator(
  getStateFn:          () => SubwayStationState,
  entityIdFn:          () => String,
  currentTickFn:       () => Tick,
  reportFn:            (Map[String, Any], String) => Unit,
  spawnDynamicActorFn: (String, Long, String) => Unit,
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
              addDependencyFn(subway.actorId.toString, ShardActorId(subway.actorId, "hybrid.actor.Subway"))
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

  /** Dequeues and prepares (route + state-object + `report()`, all pure/GVT-buffer-safe — see
    * `SimulationBaseActor.reportBuffer`) at most one subway per line in `lines`, without ever
    * calling the irrevocable `spawnDynamicActorFn`. The dequeue itself is safe to run eagerly
    * under Time Warp because `state.subways`/`state.lines` are checkpointed and restored
    * correctly by rollback; only the actual spawn is deferred by the caller.
    */
  def prepareSubwaysFrom(lines: mutable.Map[String, SubwayLineInformation]): Seq[PreparedSubway] = {
    val prepared = mutable.ListBuffer.empty[PreparedSubway]
    lines.keys.foreach { line =>
      state.subways.get(line) match
        case Some(subwayQueue) =>
          if (subwayQueue.nonEmpty && state.garage) {
            val subway = subwayQueue.dequeue()
            val subwayStartTick = currentTickFn() + lines(line).interval
            try {
              val stateJson = prepareSubway(subway, line, subwayStartTick)
              prepared += PreparedSubway(subway, line, stateJson)
              lines(line).nextTick = subwayStartTick
            } catch {
              case e: IllegalStateException =>
                logErrorFn(s"Failed to prepare subway ${subway.actorId} for line $line: ${e.getMessage}")
                subwayQueue.enqueue(subway)
              case e: Exception =>
                logErrorFn(s"Unexpected error preparing subway ${subway.actorId} for line $line: ${e.getMessage}")
                subwayQueue.enqueue(subway)
            }
          }
        case None =>
          logWarnFn(s"Subway not found for line $line")
    }
    prepared.toSeq
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

  /** Everything about creating a subway that is pure/idempotent and safe to run eagerly under
    * Time Warp: route lookup, state-object construction, and `report()` (already GVT-buffered —
    * see `SimulationBaseActor.reportBuffer`). Deliberately excludes the one irrevocable step,
    * `spawnDynamicActorFn`, which the caller defers separately under Time Warp; see
    * `docs/TIME_WARP_DESIGN.md`'s dynamic-actor-spawn entry.
    */
  def prepareSubway(subway: SubwayInformation, line: String, startTick: Long): String = {
    val route = convertLineRouteToPath(line)
    if (route.isEmpty) {
      logWarnFn(s"Cannot create subway ${subway.actorId} for line $line - no valid route available")
      throw new IllegalStateException(s"No route available for subway line $line")
    }

    val subwayStations = convertLineToSubwayStations(line)
    if (subwayStations.isEmpty) {
      logWarnFn(s"Cannot create subway ${subway.actorId} for line $line - no subway stations available")
      throw new IllegalStateException(s"No subway stations available for line $line")
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

    toJson(subwayState)
  }

  /** The one irrevocable step of subway creation. Under Time Warp the caller only invokes this
    * once GVT has passed the tick `prepareSubway` ran at, since a shard-region `StartEntity` for
    * an already-live entity id cannot be undone by an anti-message.
    */
  def spawnPreparedSubway(subway: SubwayInformation, stateJson: String): Unit = {
    SubwayStationMetrics.subwaysCreated.labels(subway.line).inc()
    spawnDynamicActorFn("hybrid.actor.Subway", subway.actorId, stateJson)
  }

  private def createSubway(subway: SubwayInformation, startTick: Long): Unit =
    spawnPreparedSubway(subway, prepareSubway(subway, subway.line, startTick))

  private def convertLineToSubwayStations(line: String): mutable.Map[Long, Long] = {
    val lineRoute     = state.linesRoute(line)
    val subwayStations = mutable.Map[Long, Long]()
    for (i <- lineRoute.indices)
      subwayStations.put(lineRoute(i).stationNode.stationId, lineRoute(i).stationNode.nodeId)
    subwayStations
  }

  private def convertLineRouteToPath(line: String): mutable.Queue[(Long, Long)] = {
    val route     = mutable.Queue[(Long, Long)]()
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
