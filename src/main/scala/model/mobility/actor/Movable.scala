package org.interscity.htc
package model.mobility.actor

import core.actor.{BaseActor, SimulationBaseActor}
import model.mobility.entity.state.MovableState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.{Properties, SimulationBaseProperties}
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, Start}
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{EnterLinkData, LeaveLinkData}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.{CityMapUtil, GPSUtil}

import scala.collection.mutable

abstract class Movable[T <: MovableState](
  private val properties: SimulationBaseProperties
)(implicit m: Manifest[T])
    extends SimulationBaseActor[T](
      properties = properties
    ) {

  protected def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }
    try
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            state.movableStatus = Finished
            logInfo("No path available between origin and destination, finishing.")
            onFinishSpontaneous()
          }
        case None =>
          logError(
            s"Failed to calculate route from ${state.origin} to ${state.destination} for ${getEntityId}."
          )
          state.movableStatus = Finished
          onFinishSpontaneous()
      }
    catch {
      case e: Exception =>
        logError(s"Exception during route request for ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
        onFinishSpontaneous()
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        requestRoute()
      case Ready =>
        enterLink()
      case Finished =>
        onFinishSpontaneous()
      case _ =>
        logWarn(s"Event current status not handled ${state.movableStatus}")
        onFinishSpontaneous(Some(currentTick + 1))

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: LinkInfoData => handleLinkInfo(event, d)
      case _ =>
        logWarn(s"Movable Event not handled: $event")
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

  protected def enterLink(): Unit =
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
          case None =>
            state.movableStatus = Finished
            logWarn("No edge label found for link, finishing.")
            onFinishSpontaneous()
            selfDestruct()
        }
      case None if state.movableBestRoute.isEmpty =>
        state.movableStatus = Finished
        logWarn("No current path and no best route available, finishing.")
        onFinishSpontaneous()
      case None =>
        getNextPath match {
          case Some(nextPath) =>
            state.movableCurrentPath = Some(nextPath)
            enterLink()
          case None =>
            state.movableStatus = Finished
            onFinishSpontaneous()
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
      case Some(path) if path.nonEmpty =>
        Some(path.dequeue())
      case Some(_) =>
        logDebug("Path queue is empty, trajectory completed")
        None
      case None =>
        logWarn("No path to follow")
        None

  protected def viewNextPath: Option[(String, String)] =
    state.movableBestRoute match
      case Some(path) if path.nonEmpty =>
        Some(path.head)
      case Some(_) =>
        logDebug("Path queue is empty, no next path available")
        None
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
