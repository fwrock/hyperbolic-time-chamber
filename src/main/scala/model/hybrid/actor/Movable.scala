package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import model.hybrid.entity.state.MovableState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import model.hybrid.entity.state.enumeration.EventTypeEnum.{ ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo }
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Finished, Ready, Start, Waiting }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.event.data.{ EnterLinkData, LeaveLinkData }
import model.hybrid.entity.state.enumeration.EventTypeEnum
import model.hybrid.util.{ CityMapUtil, GPSUtil }

abstract class Movable[T <: MovableState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends SimulationBaseActor[T](
      properties = properties
    ) {

  private var waitingTicksCounter: Int = 0
  private val MaxWaitingTicks: Int = 100

  protected def requestRoute(): Unit = {
    logDebug(s"Requesting route from ${state.origin} to ${state.destination}")
    try
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          logDebug(s"Route calculated successfully: cost=$cost, pathLength=${pathQueue.size}")
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            state.movableStatus = Finished
            logWarn("No path available between origin and destination, finishing.")
            onFinish(state.origin)
          }
        case None =>
          logError(
            s"Failed to calculate route from ${state.origin} to ${state.destination} for ${getEntityId}."
          )
          state.movableStatus = Finished
          onFinish(state.origin)
      }
    catch {
      case e: Exception =>
        logError(s"Exception during route request for ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
        onFinish(state.origin)
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match {
      case Start =>
        logDebug(s"Starting route request from ${state.origin} to ${state.destination}")
        requestRoute()

      case Ready =>
        enterLink()

      case Waiting =>
        waitingTicksCounter += 1
        if (waitingTicksCounter > MaxWaitingTicks) {
          logWarn(
            s"${getEntityId} stuck in Waiting for $waitingTicksCounter ticks at tick $currentTick (Link not responding). Recovering by skipping to next route segment."
          )
          waitingTicksCounter = 0
          state.movableCurrentPath = None
          state.movableStatus = Ready
          onFinishSpontaneous(Some(currentTick + 1))
        } else {
          onFinishSpontaneous(Some(currentTick + 1))
        }

      case Finished =>
        onFinishSpontaneous(None)
        selfDestruct()

      case _ =>
        logWarn(s"Movable status not explicitly handled in base class: ${state.movableStatus}")
        onFinishSpontaneous(Some(currentTick + 1))
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    if (state == null) {
      logWarn(
        s"${getEntityId} received interaction event while state is null, ignoring: ${event.eventType}"
      )
      return
    }
    event.data match {
      case d: LinkInfoData => handleLinkInfo(event, d)
      case _               => logWarn(s"Movable Event not handled: $event")
    }
  }

  private def handleLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit = {
    waitingTicksCounter = 0
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event, data)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event, data)
      case _                    => logWarn(s"Event not handled: $event with data: $data")
    }
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
    onFinishSpontaneous(None)
    selfDestruct()
  }

  protected def enterLink(): Unit =
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, _)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            state.movableStatus = Waiting
            waitingTicksCounter = 0
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
            onFinishSpontaneous(Some(currentTick + 1))

          case None =>
            state.movableStatus = Finished
            logWarn("No edge label found for link, finishing.")
            val currentNode = Option(getCurrentNode).getOrElse(state.origin)
            onFinish(currentNode)
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
              state.movableCurrentPath = None
              onFinish(nextNodeId)
              return
            }
            state.movableCurrentPath = None
            onFinishSpontaneous(Some(currentTick + 1))
          case _ =>
            logWarn("Path item not handled")
            onFinishSpontaneous(Some(currentTick + 1))
        }
      case None =>
        if (state.movableBestRoute.forall(_.isEmpty)) {
          state.movableStatus = Finished
          onFinish(state.destination)
        } else {
          getNextPath match {
            case Some(nextPath) =>
              state.movableCurrentPath = Some(nextPath)
              state.movableStatus = Ready
              enterLink()
            case None =>
              state.movableStatus = Finished
              onFinish(state.destination)
          }
        }
    }

  protected def getNextPath: Option[(String, String)] =
    if (state == null) None
    else {
      state.movableBestRoute match {
        case Some(path) if path.nonEmpty => Some(path.dequeue())
        case Some(_)                     => None
        case None                        => None
      }
    }

  protected def viewNextPath: Option[(String, String)] =
    if (state == null) None
    else {
      state.movableBestRoute match {
        case Some(path) if path.nonEmpty => Some(path.head)
        case Some(_)                     => None
        case None                        => None
      }
    }

  protected def getCurrentNode: String =
    if (state == null) null
    else state.movableCurrentPath.map(_._2).orNull

  protected def getNextLink: String =
    if (state == null) null
    else viewNextPath.map(_._1).orNull
}
