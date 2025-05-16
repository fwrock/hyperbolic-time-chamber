package org.interscity.htc
package model.mobility.entity.state

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum.Bus
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ RouteWaiting, Start }
import org.interscity.htc.model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.mobility.entity.state.model.{ BusNodeState, RoutePathItem }

import scala.collection.mutable

class BusState(
  override val startTick: Long,
  val label: String,
  val capacity: Int,
  var distance: Double = 0.0,
  var countUnloadPassenger: Int = 0,
  var countUnloadReceived: Int = 0,
  var busStops: Map[String, String],
  val numberOfPorts: Int,
  val nodeState: BusNodeState = BusNodeState(),
  val people: mutable.Map[String, Identify] = mutable.Map[String, Identify](),
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var currentPath: Option[(String, String)] = None,
  var currentPathPosition: Int = 0,
  override val origin: String,
  override val destination: String,
  var bestCost: Double = Double.MaxValue,
  var status: MovableStatusEnum = Start,
  var reachedDestination: Boolean = false,
  override val actorType: ActorTypeEnum = Bus,
  override val size: Double
) extends MovableState(
      startTick = startTick,
      movableBestRoute = bestRoute,
      movableCurrentPath = currentPath,
      movableCurrentNode = null,
      origin = origin,
      destination = destination,
      movableBestCost = bestCost,
      movableStatus = status,
      movableReachedDestination = reachedDestination,
      actorType = actorType,
      size = size
    )
