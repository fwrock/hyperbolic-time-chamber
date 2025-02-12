package org.interscity.htc

import core.types.CoreTypes.Tick
import model.interscsimulator.entity.state.MovableState
import model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable
package model.interscsimulator.entity.state

case class PersonState(
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
