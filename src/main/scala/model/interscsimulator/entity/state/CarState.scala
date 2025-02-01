package org.interscity.htc
package model.interscsimulator.entity.state

import core.types.CoreTypes.Tick

import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

class CarState(
  startTick: Tick,
  name: String,
  origin: String,
  destination: String = null,
  bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  bestCost: Double = Double.MaxValue,
  currentNode: String,
  currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  var lastNode: String,
  digitalRails: Boolean = false,
  var distance: Double = 0,
  actorType: ActorTypeEnum,
  actorSize: Double,
  status: MovableStatusEnum = Start
) extends MovableState(
      startTick = startTick,
      bestRoute = bestRoute,
      currentNode = currentNode,
      currentPath = currentPath,
      bestCost = bestCost,
      status = status,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = actorSize
    )
