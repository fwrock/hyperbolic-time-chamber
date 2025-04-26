package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable

case class CarState(
  override val startTick: Tick,
  name: String,
  override val origin: String,
  override val destination: String = null,
  var bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  var bestCost: Double = Double.MaxValue,
  var currentNode: String,
  var currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  var lastNode: String,
  var digitalRails: Boolean = false,
  var distance: Double = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var status: MovableStatusEnum = Start
) extends MovableState(
      startTick = startTick,
      movableBestCost = bestCost,
      movableStatus = status,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    )
