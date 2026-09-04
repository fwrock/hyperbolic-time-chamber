package org.interscity.htc
package model.hybrid.entity.state

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.RouteWaiting

import scala.collection.mutable

abstract class MovableState(
  val startTick: Long,
  val reporterType: ReportTypeEnum = null,
  val scheduleOnTimeManager: Boolean = true,
  var movableBestRoute: Option[mutable.Queue[(Long, Long)]] = None,
  var movableCurrentPath: Option[(Long, Long)] = None,
  var movableCurrentNode: Long = 0L,
  val origin: Long,
  val destination: Long,
  var movableBestCost: Double = Double.MaxValue,
  var movableStatus: MovableStatusEnum = RouteWaiting,
  var movableReachedDestination: Boolean = false,
  val actorType: ActorTypeEnum,
  val size: Double
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {

  def getStatus: MovableStatusEnum = movableStatus

  def updateStatus(newStatus: MovableStatusEnum): Unit = movableStatus = newStatus

  def getBestRoute: Option[mutable.Queue[(Long, Long)]] = movableBestRoute

  def updateBestRoute(newBestRoute: Option[mutable.Queue[(Long, Long)]]): Unit =
    movableBestRoute = newBestRoute

  def getCurrentPath: Option[(Long, Long)] = movableCurrentPath

  def updateCurrentPath(newCurrentPath: Option[(Long, Long)]): Unit =
    movableCurrentPath = newCurrentPath

  def getCurrentNode: Long = movableCurrentNode

  def updateCurrentNode(newCurrentNode: Long): Unit = movableCurrentNode = newCurrentNode

  def getBestCost: Double = movableBestCost

  def updateBestCost(newBestCost: Double): Unit = movableBestCost = newBestCost

  def getReachedDestination: Boolean = movableReachedDestination

  def updateReachedDestination(newReachedDestination: Boolean): Unit = movableReachedDestination =
    newReachedDestination

  def getOrigin: Long = origin

  def getDestination: Long = destination

  def getActorType: ActorTypeEnum = actorType

  def getSize: Double = size
}
