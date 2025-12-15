package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.LinkState

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ForwardRoute, RequestEnterLink, EnterLinkConfirm}
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import model.mobility.entity.event.data.{ EnterLinkData, ForwardRouteData, LeaveLinkData, RequestRouteData }
import model.mobility.entity.event.data.vehicle.{RequestEnterLinkData, EnterLinkConfirmData}
import model.mobility.util.SpeedUtil

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.util.IdentifyUtil
import org.interscity.htc.model.mobility.entity.event.data.link.{ LinkConnectionsData, LinkInfoData }

class Link(
  private val properties: Properties
) extends BaseActor[LinkState](
      properties = properties
    ) {

  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  override def onInitialize(event: InitializeEvent): Unit =
    super.onStart()
//    sendConnections(state.to, IdentifyUtil.fromDependency(getDependency(state.to)))
//    sendConnections(state.from, IdentifyUtil.fromDependency(getDependency(state.from)))

  private def sendConnections(actorId: String, identify: Identify): Unit =
    sendMessageTo(
      identify.id,
      identify.classType,
      LinkConnectionsData(
        to = IdentifyUtil.fromDependency(getDependency(state.to)),
        from = IdentifyUtil.fromDependency(getDependency(state.from))
      ),
      EventTypeEnum.RequestRoute.toString
    )

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    // Only log important events at INFO level
    if (event.eventType.contains("RequestEnterLink")) {
      logDebug(s"Link ${getEntityId} received event: ${event.eventType} with data type: ${event.data.getClass.getSimpleName}")
    }
    
    event.data match {
      case d: RequestEnterLinkData => handleRequestEnterLink(event, d)  // Event-driven
      case d: EnterLinkData => handleEnterLink(event, d)  // Legacy tick-driven
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.eventType}")
    }
  }
  
  /** Event-driven: Calculate travel time and confirm entry with prediction */
  private def handleRequestEnterLink(event: ActorInteractionEvent, data: RequestEnterLinkData): Unit = {
    val vehicle = event.toIdentity
    
    logDebug(s"Link ${getEntityId} processing RequestEnterLink from ${vehicle.id}")
    
    // Calculate speed based on current occupancy (mesoscopic model)
    val currentSpeed = SpeedUtil.linkDensitySpeed(
      length = state.length,
      capacity = state.capacity,
      numberOfCars = state.registered.size,
      freeSpeed = state.freeSpeed,
      lanes = state.lanes
    )
    
    // Calculate travel time in ticks (assuming 1 tick = 1 second)
    // travelTime = distance / speed (convert km/h to m/s)
    val speedMs = currentSpeed / 3.6  // km/h to m/s
    val travelTimeSeconds = if (speedMs > 0) state.length / speedMs else Double.MaxValue
    val travelTimeTicks = Math.max(1L, travelTimeSeconds.toLong)
    
    logDebug(s"Link ${getEntityId}: cars=${state.registered.size}, speed=$currentSpeed km/h, travelTime=$travelTimeTicks ticks")
    
    // Register vehicle with expected leave time
    state.registered.add(
      LinkRegister(
        actorId = vehicle.id,
        shardId = event.shardRefId,
        actorType = org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum.Car,
        actorSize = 1.0,  // Default size
        actorCreationType = CreationTypeEnum.LoadBalancedDistributed
      )
    )
    
    // Get signal prediction if signal exists
    val signalPrediction = None  // TODO: Request from traffic signal
    
    // Confirm entry with calculated travel time
    sendMessageTo(
      vehicle.id,
      vehicle.classType,
      data = EnterLinkConfirmData(
        linkId = getEntityId,
        entryTick = data.entryTick,
        baseTravelTime = travelTimeTicks,
        destinationNode = data.destinationNode,
        signalState = signalPrediction
      ),
      eventType = EventTypeEnum.EnterLinkConfirm.toString
    )
  }

  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    val dataLink = if (state == null) {
      LinkInfoData(
        linkCapacity = Int.MaxValue,
        linkFreeSpeed = 50,
        linkLanes = 1
      )
    } else {
      LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes
      )
    }
    if (state != null) {
      state.registered.add(
        LinkRegister(
          actorId = data.actorId,
          shardId = data.shardId,
          actorType = data.actorType,
          actorSize = data.actorSize,
          actorCreationType = data.actorCreationType
        )
      )
    }
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = dataLink,
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }

  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    state.registered.filterInPlace(_.actorId != data.actorId)
    val dataLink = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = dataLink,
      eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }
}
