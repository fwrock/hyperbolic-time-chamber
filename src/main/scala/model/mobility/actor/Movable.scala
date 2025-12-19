package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.MovableState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.{EnterLinkConfirmData, ArriveAtNodeData}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo, EnterLinkConfirm, ArriveAtNode }
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Finished, Ready, Start, Moving, RouteWaiting }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{ EnterLinkData, LeaveLinkData }
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.{ CityMapUtil, GPSUtil }

import scala.collection.mutable

abstract class Movable[T <: MovableState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends BaseActor[T](
      properties = properties
    ) {

  protected def updateStatus(newStatus: org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum): Unit = {
    if (state.movableStatus != newStatus) {
      logDebug(s"Vehicle ${getEntityId}: ${state.movableStatus} -> $newStatus at tick $currentTick")
      state.movableStatus = newStatus
      state.movableLastStateChange = currentTick
    }
  }

  protected def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }
    try
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.movableBestRoute = Some(pathQueue)
          updateStatus(Ready)
          state.movableCurrentPath = None
          if (pathQueue.nonEmpty) {
            enterLinkEventDriven()  // Event-driven
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

  /** Event-driven base implementation - no generic tick scheduling */
  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        requestRoute()

      case Ready =>
        logInfo(s"Vehicle ${getEntityId} ready to enter link (event-driven)")
        enterLinkEventDriven()
        
      case RouteWaiting =>
        val waitingTime = currentTick - state.movableLastStateChange
        if (waitingTime > 100) {  // 100 ticks timeout
          logError(s"Vehicle ${getEntityId} stuck in RouteWaiting for $waitingTime ticks at tick $currentTick! Link may have failed to respond. Resetting...")
          logError(s"  Current path: ${state.movableCurrentPath}")
          logError(s"  Route: ${state.movableBestRoute.map(_.size).getOrElse(0)} segments remaining")
          state.movableStatus = Ready
          enterLinkEventDriven()
        } else {
          logDebug(s"Vehicle ${getEntityId} in RouteWaiting (waiting ${waitingTime} ticks for EnterLinkConfirm)")
          onFinishSpontaneous()
        }
        
      case Moving =>
        val nodeId = getCurrentNode
        if (nodeId != null) {
          logDebug(s"Vehicle ${getEntityId} arrived at node $nodeId at tick $currentTick")
          handleArriveAtNode(currentTick, nodeId)
        } else {
          logError(s"Vehicle ${getEntityId} in Moving state but has no current node!")
          state.movableStatus = Finished
          onFinishSpontaneous()
        }
        
      case Finished =>
        onFinishSpontaneous()
        
      case _ =>
        logWarn(s"Event current status not handled ${state.movableStatus}")
        onFinishSpontaneous(Some(currentTick + 1))

  /** Execute with lookahead: process status-dependent logic without external dependencies
    * This allows vehicles to advance multiple ticks when in predictable states
    */
  override def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
    val safeHorizon = event.effectiveSafeHorizon
    val lookaheadTicks = safeHorizon - event.tick
    
    state.movableStatus match {
      case Start | Finished =>
        actSpontaneous(event)
        
      case Ready =>
        actSpontaneous(event)
        
      case _ =>
        actSpontaneous(event)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkConfirmData => handleEnterLinkConfirm(event, d)  // Event-driven
      case d: LinkInfoData => handleLinkInfo(event, d)  // Legacy tick-driven
      case _ =>
        logWarn(s"Movable Event not handled: $event")
    }
  
  /** Event-driven: Handle link entry confirmation with travel time */
  protected def handleEnterLinkConfirm(
    event: ActorInteractionEvent,
    data: EnterLinkConfirmData
  ): Unit = {
    logDebug(s"Vehicle ${getEntityId} received EnterLinkConfirm for link ${data.linkId}, travel time = ${data.baseTravelTime}")
    
    // Update state
    updateStatus(Moving)
    state.movableCurrentLink = data.linkId

    val arrivalTick = currentTick + data.baseTravelTime
    
    logDebug(s"Vehicle ${getEntityId} entered link ${data.linkId}, will arrive at ${data.destinationNode} at tick $arrivalTick (current=$currentTick, travel=${data.baseTravelTime})")
    
    // Report enter_link event (event-driven model)
//    report(
//      data = Map(
//        "event_type" -> "enter_link",
//        "car_id" -> getEntityId,
//        "link_id" -> data.linkId,
//        "travel_time" -> data.baseTravelTime,
//        "arrival_tick" -> arrivalTick,
//        "tick" -> currentTick
//      ),
//      label = "enter_link"
//    )

    onFinishSpontaneous(Some(arrivalTick))
    
    state.movableNextScheduledTick = Some(arrivalTick)
  }
  
  /** Event-driven: Handle arrival at node (scheduled event) */
  protected def handleArriveAtNode(currentTick: Tick, nodeId: String): Unit = {
    logDebug(s"Vehicle ${getEntityId} arrived at node $nodeId at tick $currentTick")
    
    // Leave current link
    leavingLink()
    
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      onFinish(nodeId)
      return
    }
    
    getNextPath match {
      case Some(nextPath) =>
        state.movableCurrentPath = Some(nextPath)
        updateStatus(Ready)
        enterLinkEventDriven()  // Event-driven entry
      case None =>
        onFinish(nodeId)
    }
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
  
  /** Event-driven: Request link entry with travel time calculation */
  protected def enterLinkEventDriven(): Unit =
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            logDebug(s"Vehicle ${getEntityId} requesting entry to link ${edgeLabel.id} for node $nextNodeId")
            sendMessageTo(
              entityId = edgeLabel.id,
              shardId = edgeLabel.classType,
              data = model.mobility.entity.event.data.vehicle.RequestEnterLinkData(
                vehicleId = getEntityId,
                entryTick = currentTick,
                destinationNode = nextNodeId
              ),
              eventType = org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.RequestEnterLink.toString,
              actorType = LoadBalancedDistributed
            )
            updateStatus(RouteWaiting)
            logDebug(s"Vehicle ${getEntityId} now waiting for EnterLinkConfirm")
            onFinishSpontaneous()
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
            enterLinkEventDriven()
          case None =>
            state.movableStatus = Finished
            onFinishSpontaneous()
        }
    }

  /** Legacy tick-driven entry (kept for backward compatibility) */
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
