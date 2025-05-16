package org.interscity.htc
package model.mobility.entity.state

import core.entity.state.BaseState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum.Subway
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.mobility.entity.state.model.{ RoutePathItem, SubwayNodeState }

import scala.collection.mutable

case class SubwayState(
  override val startTick: Tick = 0,
  capacity: Int,
  numberOfPorts: Int,
  velocity: Double,
  var distance: Double = 0.0,
  boardingTimeByPassenger: Double = 1.5,
  stopTime: Tick,
  subwayStations: mutable.Map[String, String] = mutable.Map.empty,
  var countUnloadPassenger: Int = 0,
  var countUnloadReceived: Int = 0,
  override val origin: String,
  override val destination: String,
  nodeState: SubwayNodeState = SubwayNodeState(),
  passengers: mutable.Map[String, Identify] = mutable.Map.empty,
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var currentPath: Option[(String, String)] = None,
  var currentPathPosition: Int = 0,
  var bestCost: Double = Double.MaxValue,
  line: String,
  override val actorType: ActorTypeEnum = Subway,
  var status: MovableStatusEnum = Start
) extends MovableState(
      startTick = startTick,
      movableBestRoute = bestRoute,
      movableCurrentPath = currentPath,
      movableCurrentNode = null,
      origin = origin,
      destination = destination,
      movableBestCost = bestCost,
      movableStatus = status,
      movableReachedDestination = false,
      actorType = actorType,
      size = -1
    )
