package org.interscity.htc
package model.hybrid.support.busstation

import org.interscity.htc.model.hybrid.entity.state.BusStationState
import org.interscity.htc.model.hybrid.entity.state.model.SubRoutePair
import org.interscity.htc.model.hybrid.util.GPSUtil
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import core.actor.trace.ActorTrace
import scala.collection.mutable
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

class BusStationRouteCalculator(
  getStateFn:      () => BusStationState,
  entityIdFn:      () => String,
  currentTickFn:   () => Tick,
  getBlockingEcFn: () => ExecutionContext,
  logInfoFn:       String => Unit,
  logWarnFn:       String => Unit,
  logDebugFn:      String => Unit
) {

  private def state: BusStationState = getStateFn()

  def orderedBusStopIds: List[String] = {
    def suffixNumber(id: String): Option[Long] = {
      val digits = id.reverse.takeWhile(_.isDigit).reverse
      if (digits.nonEmpty) Some(digits.toLong) else None
    }
    state.busStops.keys.toList.sortBy { id =>
      suffixNumber(id) match {
        case Some(num) => (0L, num, id)
        case None      => (1L, Long.MaxValue, id)
      }
    }
  }

  private def mkRouteFutures(stops: List[String], going: Boolean)(implicit
    ec: ExecutionContext
  ): List[Future[Option[(SubRoutePair, mutable.Queue[(Identify, Identify)])]]] =
    stops.sliding(2).toList.map { pair =>
      val originStop = pair.head
      val destStop   = pair.last
      (state.busStops.get(originStop), state.busStops.get(destStop)) match {
        case (Some(originNode), Some(destNode)) =>
          Future {
            // Bounded expansion budget: enough for city-block inter-stop routes without
            // risking dirty-array overflow on fully disconnected subgraphs.
            GPSUtil.calcRouteCompact(originId = originNode, destinationId = destNode, maxExpansions = 500_000) match {
              case Some((_, pathQueue)) =>
                val identifyPath = pathQueue.map { case (f, t) => (Identify(id = f), Identify(id = t)) }
                Some(SubRoutePair(originStop, destStop) -> identifyPath)
              case None =>
                logWarnFn(s"No route found: $originStop -> $destStop (going=$going) — segment will be skipped")
                None
            }
          }
        case (originOpt, destOpt) =>
          if (originOpt.isEmpty) logWarnFn(s"Origin bus stop $originStop has no node mapping")
          if (destOpt.isEmpty)   logWarnFn(s"Destination bus stop $destStop has no node mapping")
          Future.successful(None)
      }
    }

  def calculateRoutes(): Unit = {
    logDebugFn(s"BusStation ${entityIdFn()} calculating routes in parallel")

    implicit val blockingEc: ExecutionContext = getBlockingEcFn()

    val goingFutures  = mkRouteFutures(orderedBusStopIds, going = true)
    val returnFutures = mkRouteFutures(orderedBusStopIds.reverse, going = false)

    val (goingResults, returnResults) = scala.concurrent.blocking {
      (
        Await.result(Future.sequence(goingFutures), 30.minutes).flatten,
        Await.result(Future.sequence(returnFutures), 30.minutes).flatten
      )
    }

    goingResults.foreach  { case (pair, path) => state.goingRoute.foreach(_.put(pair, path))     }
    returnResults.foreach { case (pair, path) => state.returningRoute.foreach(_.put(pair, path)) }

    val tick = currentTickFn()
    if (isCalculateRoutingComplete) {
      logInfoFn(
        s"BusStation ${entityIdFn()} route calculation complete " +
          s"(${orderedBusStopIds.size - 1} going + ${orderedBusStopIds.size - 1} returning segments)"
      )
      // ActorTrace.trace(entityIdFn(), tick, "bus_station_route_ok", // #actor-trace
      //   s"stops=${orderedBusStopIds.size} going=${state.goingRoute.map(_.size).getOrElse(0)} returning=${state.returningRoute.map(_.size).getOrElse(0)}") // #actor-trace
    } else {
      logWarnFn(s"BusStation ${entityIdFn()} route calculation incomplete after calculateRoutes()")
      // ActorTrace.trace(entityIdFn(), tick, "bus_station_route_incomplete", // #actor-trace
      //   s"goingRoute=${state.goingRoute.map(_.size).getOrElse(0)} returningRoute=${state.returningRoute.map(_.size).getOrElse(0)}") // #actor-trace
    }
  }

  def isCalculateRoutingComplete: Boolean =
    isRouteComplete(state.goingRoute) && isRouteComplete(state.returningRoute)

  def hasAnyRouteSegment: Boolean =
    state.goingRoute.exists(_.values.exists(_.nonEmpty)) &&
      state.returningRoute.exists(_.values.exists(_.nonEmpty))

  private def isRouteComplete(
    route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]]
  ): Boolean =
    route match
      case Some(r) =>
        if (state.busStops.size <= 1) false
        else {
          val expectedSize = state.busStops.size - 1
          val actualSize   = r.keys.size
          val allNonEmpty  = r.values.forall(_.nonEmpty)
          logDebugFn(s"Route completion check: actual=$actualSize, expected=$expectedSize, allNonEmpty=$allNonEmpty")
          actualSize == expectedSize && allNonEmpty
        }
      case None => false
}
