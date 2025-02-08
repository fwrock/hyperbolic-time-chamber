package org.interscity.htc
package model.interscsimulator.entity.state

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.ActorTypeEnum.Bus
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.RouteWaiting
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

case class BusState(
  startTick: Long,
  capacity: Int,
  people: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef](),
  bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  busStops: List[String],
  origin: String,
  destination: String,
  bestCost: Double = Double.MaxValue,
  status: MovableStatusEnum = RouteWaiting,
  reachedDestination: Boolean = false,
  actorType: ActorTypeEnum = Bus,
  size: Double
) extends MovableState(
      startTick = startTick,
      bestRoute = bestRoute,
      currentPath = currentPath,
      currentNode = null,
      origin = origin,
      destination = destination,
      bestCost = bestCost,
      status = status,
      reachedDestination = reachedDestination,
      actorType = actorType,
      size = size
    )
