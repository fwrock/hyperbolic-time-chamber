package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import model.hybrid.entity.state.MovableState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import model.hybrid.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo}
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, Start}
import model.hybrid.entity.state.enumeration.MovableStatusEnum.Waiting
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.event.data.{EnterLinkData, LeaveLinkData}
import model.hybrid.entity.state.enumeration.EventTypeEnum
import model.hybrid.util.{CityMapUtil, GPSUtil}

import scala.collection.mutable

abstract class Movable[T <: MovableState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends SimulationBaseActor[T](
      properties = properties
    ) {

  /** Counter for consecutive ticks in Waiting state without receiving a Link response. */
  private var waitingTicksCounter: Int = 0

  /** Maximum ticks to wait for a Link response before recovering.
    * If a Link doesn't respond within this threshold, the vehicle is stuck
    * (e.g., Link actor not found, message lost, data inconsistency).
    * Recovery: skip current link and advance to next route segment.
    */
  private val MaxWaitingTicks: Int = 100

  protected def requestRoute(): Unit = {
    logInfo(s"Requesting route from ${state.origin} to ${state.destination}")
    try {
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          logInfo(s"Route calculated successfully: cost=$cost, pathLength=${pathQueue.size}")
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            state.movableStatus = Finished
            logInfo("No path available between origin and destination, finishing.")
            onFinishSpontaneous(destruct = true)
          }
        case None =>
          logError(s"Failed to calculate route from ${state.origin} to ${state.destination} for ${getEntityId}.")
          state.movableStatus = Finished
          onFinishSpontaneous(destruct = true)
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during route request for ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
        onFinishSpontaneous(destruct = true)
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    logDebug(s"actSpontaneous called with status=${state.movableStatus}, origin=${state.origin}, destination=${state.destination}")
    state.movableStatus match
      case Start =>
        logInfo(s"Starting route request from ${state.origin} to ${state.destination}")
        requestRoute()
      case Ready =>
        enterLink()
      case Waiting =>
        waitingTicksCounter += 1
        if (waitingTicksCounter > MaxWaitingTicks) {
          logWarn(s"${getEntityId} stuck in Waiting for $waitingTicksCounter ticks at tick $currentTick (Link not responding). Recovering by skipping to next route segment.")
          waitingTicksCounter = 0
          state.movableCurrentPath = None
          state.movableStatus = Ready
          onFinishSpontaneous(Some(currentTick + 1))
        } else {
          onFinishSpontaneous(Some(currentTick + 1))
        }
      case Finished =>
        onFinishSpontaneous()
        selfDestruct()
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
    waitingTicksCounter = 0  // Reset stuck counter on any link response
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
    selfDestruct()
  }

  protected def enterLink(): Unit = {
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            state.movableStatus = Waiting
            waitingTicksCounter = 0  // Reset for fresh wait
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
            // Schedule to wait for link response --- wrong, because generates race condition
//            onFinishSpontaneous(Some(currentTick + 1))
          case None =>
            state.movableStatus = Finished
            logWarn("No edge label found for link, finishing.")
            onFinishSpontaneous(destruct = true)
        }
      case None if state.movableBestRoute.forall(_.isEmpty) =>
        state.movableStatus = Finished
        logWarn("No current path and no best route available, finishing.")
        onFinish(state.destination)
      case None =>
        getNextPath match {
          case Some(nextPath) =>
            state.movableCurrentPath = Some(nextPath)
            enterLink()
          case None =>
            state.movableStatus = Finished
            onFinish(state.destination)
        }
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
            if (state.movableBestRoute.forall(_.isEmpty)) {
              logWarn("No best route available to continue")
              state.movableCurrentPath = None
              onFinish(nextNodeId)
              return // onFinish already calls onFinishSpontaneous + selfDestruct
            }
            state.movableCurrentPath = None
            // Schedule to wait for next action (node signal or route request)
            onFinishSpontaneous(Some(currentTick + 1))
          case _ =>
            logWarn("Path item not handled")
            onFinishSpontaneous(Some(currentTick + 1))
        }
      case None =>
        // No current link to leave - try to progress or finish
        if (state.movableBestRoute.forall(_.isEmpty)) {
          // Route depleted, finish journey
          logInfo(s"No link to leave and route depleted for ${getEntityId}, finishing.")
          state.movableStatus = Finished
          onFinish(state.destination)
        } else {
          // Route has more segments - get next path and enter link
          getNextPath match {
            case Some(nextPath) =>
              logDebug(s"No link to leave, advancing to next path segment for ${getEntityId}")
              state.movableCurrentPath = Some(nextPath)
              state.movableStatus = Ready
              enterLink()
            case None =>
              logInfo(s"No link to leave and no next path available for ${getEntityId}, finishing.")
              state.movableStatus = Finished
              onFinish(state.destination)
          }
        }
    }

  protected def getNextPath: Option[(String, String)] =
    if (state == null) {
      logDebug("State is null; no next path available")
      None
    } else {
      state.movableBestRoute match
        case Some(path) if path.nonEmpty =>
          Some(path.dequeue())
        case Some(_) =>
          logDebug("Path queue is empty, trajectory completed")
          None
        case None =>
          logWarn("No path to follow")
          None
    }

  protected def viewNextPath: Option[(String, String)] =
    if (state == null) {
      logDebug("State is null; cannot view next path")
      None
    } else {
      state.movableBestRoute match
        case Some(path) if path.nonEmpty =>
          Some(path.head)
        case Some(_) =>
          logDebug("Path queue is empty, no next path available")
          None
        case None =>
          logWarn("No path to follow")
          None
    }

  protected def getCurrentNode: String =
    if (state == null) {
      null
    } else {
      state.movableCurrentPath match
        case Some(item) =>
          item._2
        case None =>
          null
    }

  protected def getNextLink: String =
    if (state == null) {
      null
    } else {
      viewNextPath match
        case Some(item) =>
          item._1
        case None =>
          null
    }
}
