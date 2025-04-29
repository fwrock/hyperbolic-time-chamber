package org.interscity.htc
package model.mobility.entity.state

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.{ActorTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.RouteWaiting
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable

abstract class MovableState(
  val startTick: Long,
  val reporterType: ReportTypeEnum = null,
  val scheduleOnTimeManager: Boolean = true,
  var movableBestRoute: Option[mutable.Queue[(Identify, Identify)]] = None,
  var movableCurrentPath: Option[(Identify, Identify)] = None,
  var movableCurrentNode: String = null,
  val origin: String,
  val destination: String,
  val gpsId: String = null,
  var movableBestCost: Double = Double.MaxValue,
  var movableStatus: MovableStatusEnum = RouteWaiting,
  var movableReachedDestination: Boolean = false,
  val actorType: ActorTypeEnum,
  val size: Double
) extends BaseState(startTick = startTick, reporterType = reporterType, scheduleOnTimeManager = scheduleOnTimeManager) {

  def getStatus: MovableStatusEnum = movableStatus

  def updateStatus(newStatus: MovableStatusEnum): Unit = movableStatus = newStatus

  def getBestRoute: Option[mutable.Queue[(Identify, Identify)]] = movableBestRoute

  def updateBestRoute(newBestRoute: Option[mutable.Queue[(Identify, Identify)]]): Unit =
    movableBestRoute = newBestRoute

  def getCurrentPath: Option[(Identify, Identify)] = movableCurrentPath

  def updateCurrentPath(newCurrentPath: Option[(Identify, Identify)]): Unit =
    movableCurrentPath = newCurrentPath

  def getCurrentNode: String = movableCurrentNode

  def updateCurrentNode(newCurrentNode: String): Unit = movableCurrentNode = newCurrentNode

  def getBestCost: Double = movableBestCost

  def updateBestCost(newBestCost: Double): Unit = movableBestCost = newBestCost

  def getReachedDestination: Boolean = movableReachedDestination

  def updateReachedDestination(newReachedDestination: Boolean): Unit = movableReachedDestination =
    newReachedDestination

  def getOrigin: String = origin

  def getDestination: String = destination

  def getActorType: ActorTypeEnum = actorType

  def getSize: Double = size

  def getGpsId: String = gpsId
}
