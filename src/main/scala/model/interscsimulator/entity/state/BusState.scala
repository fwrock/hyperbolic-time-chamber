package org.interscity.htc
package model.interscsimulator.entity.state

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.ActorTypeEnum.Bus
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{ RouteWaiting, Start }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

case class BusState(
  override val startTick: Long,
  capacity: Int,
  var busStops: List[String] = List(),
  people: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef](),
  override var bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  override var currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  var currentPathPosition: Int = 0,
  override val origin: String,
  override val destination: String,
  override var bestCost: Double = Double.MaxValue,
  override var status: MovableStatusEnum = Start,
  override var reachedDestination: Boolean = false,
  override val actorType: ActorTypeEnum = Bus,
  override val size: Double
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
