package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.entity.state.RailLinkState
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.model.LinkRegister
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData

/** Rail link actor for dedicated subway/metro infrastructure.
  *
  * Rail links form a SEPARATE network from road links. Only subway/metro vehicles can use rail
  * links. This prevents:
  *   - Cars/buses trying to drive on train tracks
  *   - Subways competing with road traffic
  *   - Unrealistic mixed-mode scenarios
  *
  * Rail links have different physics than road links:
  *   - Higher speeds (60-100 km/h typical)
  *   - No lane changes
  *   - Gradient and curvature affect speed
  *   - No congestion (dedicated tracks)
  *
  * @param properties
  *   Actor properties
  */
class RailLink(
  private val properties: Properties
) extends SimulationBaseActor[RailLinkState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
  }
  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }

  /** Handle vehicle entering rail link.
    *
    * VALIDATION: Only subway vehicles can enter rail links. If a non-subway vehicle tries to enter,
    * reject it and log an error.
    */
  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    // VALIDATION: Check vehicle type
    val vehicleType = data.actorType.toString
    if (!state.canAcceptVehicle(vehicleType)) {
      logError(
        s"RAIL SAFETY VIOLATION: Vehicle type '${vehicleType}' attempted to enter rail link!"
      )
      logError(s"  Rail type: ${state.railType}")
      logError(s"  Rail link: ${state.from} -> ${state.to}")
      logError(s"  Subway line: ${state.subwayLine}")
      logError(s"Rejecting vehicle ${data.actorId}")

      val errorInfo = LinkInfoData(
        linkLength = 0,
        linkCapacity = 0,
        linkNumberOfCars = 0,
        linkFreeSpeed = 0,
        linkLanes = 0
      )

      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = errorInfo,
        eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
        actorType = LoadBalancedDistributed
      )

      return
    }

    state.registered.add(
      LinkRegister(
        actorId = data.actorId,
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
    )

    val effectiveSpeed = state.effectiveSpeed

    logDebug(s"Subway ${data.actorId} entering rail link")
    logDebug(s"  Effective speed: $effectiveSpeed km/h")

    val linkInfo = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = effectiveSpeed,
      linkLanes = state.lanes
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = linkInfo,
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }

  /** Handle vehicle leaving rail link.
    */
  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    state.registered.find(_.actorId == event.actorRefId).foreach {
      reg =>
        state.registered.remove(reg)
        logDebug(s"Subway ${event.actorRefId} left rail link")
    }

    val linkInfo = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.effectiveSpeed,
      linkLanes = state.lanes
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = linkInfo,
      eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }
}
