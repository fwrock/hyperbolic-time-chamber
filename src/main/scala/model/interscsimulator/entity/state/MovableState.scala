package org.interscity.htc
package model.interscsimulator.entity.state

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.RouteWaiting
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

class MovableState(
  val startTick: Long,
  var bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  var currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  var currentNode: String = null,
  var origin: String,
  var destination: String,
  var bestCost: Double = Double.MaxValue,
  var status: MovableStatusEnum = RouteWaiting,
  var reachedDestination: Boolean = false,
  val actorType: ActorTypeEnum,
  val size: Double
) extends BaseState(startTick = startTick)
