package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.MovableState

import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.model.mobility.entity.model.model.Route
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, Start}
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{EnterLinkData, LeaveLinkData, ReceiveRoute}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.CityMapUtil
import org.interscity.htc.system.database.redis.RedisClientManager

import scala.collection.mutable

abstract class Movable[T <: MovableState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends BaseActor[T](
      properties = properties
    ) {

  protected def requestRoute(): Unit = {}

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        logInfo("Starting Movable actor")
        requestRoute()
      case Ready =>
        logInfo("Movable actor is ready to enter link")
        enterLink()
      case _ =>
        logWarn(s"Event current status not handled ${state.movableStatus}")
        onFinishSpontaneous(Some(currentTick + 1))

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: ReceiveRoute => handleReceiveRoute(d)
      case d: LinkInfoData => handleLinkInfo(event, d)
      case _ =>
        logWarn(s"Movable Event not handled: $event")
    }

  private def handleReceiveRoute(data: ReceiveRoute): Unit = {
    val redisManager = RedisClientManager()
    redisManager.load(data.routeId).map(Route.parseFrom) match {
      case Some(route) =>
        val updatedCost = route.cost
        state.movableBestRoute = Some(
          mutable.Queue()
        )
        state.movableStatus = Ready
      case None =>
        logError(s"Route not found in Redis: ${data.routeId}")
        onFinishSpontaneous()
    }
    enterLink()
  }

  private def handleLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit =
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event, data)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event, data)
      case _ =>
        logWarn(s"Event not handled: $event with data: $data")
    }

  protected def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def onFinish(nodeId: String): Unit = {
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
    } else {
      state.movableStatus = Finished
    }
    onFinishSpontaneous()
  }

  protected def enterLink(): Unit = {
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            sendMessageTo(
              entityId = edgeLabel.id,
              shardId = edgeLabel.classType,
              data = EnterLinkData(
                actorId = getEntityId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.EnterLink.toString,
              actorType = LoadBalancedDistributed
            )
            logInfo(s"Entering link $linkEdgeGraphId to node $nextNodeId")
          case None =>
            state.movableStatus = Finished
            logInfo("No edge label found for link, finishing.")
            onFinishSpontaneous()
            selfDestruct()
        }
      case None if state.movableBestRoute.isEmpty =>
        state.movableStatus = Finished
        logInfo("No current path and no best route available, finishing.")
        onFinishSpontaneous()
      case None =>
        logInfo("No current path, but best route available, entering link.")
        state.movableCurrentPath = getNextPath
        enterLink ()
    }
  }

  protected def leavingLink(): Unit =
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            sendMessageTo(
              entityId = edgeLabel.id,
              shardId = edgeLabel.classType,
              data = LeaveLinkData(
                actorId = getEntityId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.LeaveLink.toString,
              actorType = LoadBalancedDistributed
            )
            if (state.movableBestRoute.isEmpty) {
              logWarn("No best route available to continue")
              onFinish(nextNodeId)
            }
            state.movableCurrentPath = None
          case _ =>
            logWarn("Path item not handled")
        }
      case None =>
        logWarn("No link to leave")
    }

  protected def getNextPath: Option[(String, String)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.dequeue)
      case None =>
        logWarn("No path to follow")
        None

  protected def viewNextPath: Option[(String, String)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.head)
      case None =>
        logWarn("No path to follow")
        None

  protected def getCurrentNode: String =
    state.movableCurrentPath match
      case Some(item) =>
        item._2
      case None =>
        null

  protected def getNextLink: String =
    viewNextPath match
      case Some(item) =>
        item._1
      case None =>
        null
}
