package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.{SubwayState, SubwayStationState}

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.SpontaneousEvent
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActor
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ActorCreatorUtil, JsonUtil}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum.{Start, Working}
import org.interscity.htc.model.interscsimulator.entity.state.model.{RoutePathItem, SubwayInformation, SubwayLineInformation}

import scala.collection.mutable

class SubwayStation(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[SubwayStationState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Working
        createSubwayFrom(state.lines)
      case Working =>
        createSubwayFrom(filterLinesByNextTick())
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")

  private def filterLinesByNextTick(): mutable.Map[String, SubwayLineInformation] =
    state.lines.filter { case (_, line) => line.nextTick <= currentTick }

  private def createSubwayFrom(lines: mutable.Map[String, SubwayLineInformation]): Unit = {
    lines.keys.foreach { line =>
     state.subways.get(line) match
        case Some(subways) =>
          if (subways.nonEmpty) {
            val subway = subways.dequeue()
            val actorRef = createSubway(subway)
            dependencies(subway.actorId) = actorRef
            lines(line).nextTick = currentTick + lines(line).interval
            onFinishSpontaneous(Some(lines(line).nextTick))
          }
        case None =>
          logEvent(s"Subway not found for line $line")
    }
  }

  private def createSubway(subway: SubwayInformation): ActorRef =
    createShardedActor(
      system = context.system,
      actorClass = classOf[Subway],
      entityId = subway.actorId,
      getTimeManager,
      toJson(
        SubwayState(
          startTick = currentTick,
          capacity = subway.capacity,
          numberOfPorts = subway.numberOfPorts,
          velocity = subway.velocity,
          stopTime = subway.stopTime,
          line = subway.line,
          bestRoute = Some(convertLineRouteToPath(subway.line))
        )
      ),
      dependencies
    )

  private def convertLineRouteToPath(
    line: String
  ): mutable.Queue[(RoutePathItem, RoutePathItem)] = {
    val route = mutable.Queue[(RoutePathItem, RoutePathItem)]()
    val lineRoute = state.linesRoute(line)
    for (i <- 0 until lineRoute.size - 1)
      route.enqueue(
        (
          RoutePathItem(
            actorId = lineRoute(i)._1,
            actorRef = dependencies(lineRoute(i)._1)
          ),
          RoutePathItem(
            actorId = lineRoute(i)._2,
            actorRef = dependencies(lineRoute(i)._2)
          )
        )
      )
    route
  }
}
