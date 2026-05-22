package org.interscity.htc
package model.hybrid.entity.state

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.ActorTypeEnum.Subway
import org.interscity.htc.model.hybrid.entity.state.model.SubwayNodeState

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
  var currentPathPosition: Int = 0,
  line: String,
  override val actorType: ActorTypeEnum = Subway,
  /** Speed factor applied to the velocity when calculating travel time.
    * Values < 1.0 = conservative (slower); > 1.0 = aggressive (faster).
    * Clamped to [0.5, 1.5] on use. Default: 1.0.
    */
  val speedFactor: Double = 1.0
) extends MovableState(
      startTick = startTick,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = -1
    ) {

  def bestRoute: Option[mutable.Queue[(String, String)]] = movableBestRoute
  def bestRoute_=(v: Option[mutable.Queue[(String, String)]]): Unit = movableBestRoute = v

  def currentPath: Option[(String, String)] = movableCurrentPath
  def currentPath_=(v: Option[(String, String)]): Unit = movableCurrentPath = v

  def bestCost: Double = movableBestCost
  def bestCost_=(v: Double): Unit = movableBestCost = v

  def status: MovableStatusEnum = movableStatus
  def status_=(v: MovableStatusEnum): Unit = movableStatus = v
}
