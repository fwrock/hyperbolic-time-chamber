package org.interscity.htc
package model.hybrid.support.link

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.event.data.{EnterLinkData, LeaveLinkData}
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.{ActorTypeEnum, EventTypeEnum}
import model.hybrid.entity.state.model.*
import model.hybrid.util.SpeedUtil

/** Independent physical storage and shared exit/origin-end flow, retaining native density speed. */
class LinkMesoCapacityHandler(
  stateFn: () => LinkState,
  setStateFn: LinkState => Unit,
  tickFn: () => Tick,
  linkIdFn: () => Long,
  sendFn: (Long, String, AnyRef, String) => Unit,
  scheduleFn: Tick => Unit,
  reportFn: (Map[String, Any], String) => Unit,
  enteredFn: Long => Unit,
  leftFn: Long => Unit
) {
  private def state = stateFn()
  private def queue = state.mesoCapacityState
  private def config = state.mesoCapacity.get
  private def rate = config.flowVehiclesPerHour / 3600.0
  private val epsilon = 1e-9

  private def refresh(): Unit = {
    val now = tickFn()
    val ceiling = rate
    queue.creditTick match {
      case None => queue.credit = rate
      case Some(previous) if now > previous && queue.credit < ceiling =>
        queue.credit = math.min(ceiling, queue.credit + (now - previous).toDouble * rate)
      case _ => ()
    }
    queue.creditTick = Some(now)
  }

  private def physicalCount: Int = state.registered.size - queue.originVehicles.size
  private def updateDensity(): Unit = {
    val speed = SpeedUtil.linkDensitySpeed(state.length,state.capacity,physicalCount.toLong,state.freeSpeed,state.lanes)
    val current = state
    current.currentSpeed = speed
    current.congestionFactor = SpeedUtil.bprCongestionFactor(physicalCount.toDouble,current.capacity)
  }

  private def info(seq: Long, origin: Boolean, entryWait: Double = 0, exitWait: Double = 0): LinkInfoData =
    LinkInfoData(if (origin) 0.0 else state.length,state.capacity,physicalCount,state.freeSpeed,state.lanes,seq,
      mesoOriginAtEnd=origin,mesoEntryWaitSeconds=entryWait,mesoExitWaitSeconds=exitWait)

  private def replyEntry(id: Long, shard: String, seq: Long, wait: Double): Unit =
    sendFn(id,shard,info(seq,queue.originVehicles.contains(id),entryWait=wait),EventTypeEnum.ReceiveEnterLinkInfo.toString)

  def enter(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    require(data.actorType == ActorTypeEnum.Car, "separate MESO flow/storage currently supports Car only")
    if (state.registered.containsActor(event.actorRefId)) {
      replyEntry(event.actorRefId,event.shardRefId,event.seq,0)
      return
    }
    val pending = PendingMesoEntry(event.actorRefId,event.shardRefId,event.tick,event.seq,data)
    val index = queue.entries.indexWhere(_.actorId == event.actorRefId)
    if (index < 0) queue.entries = queue.entries :+ pending
    else if (event.seq >= queue.entries(index).requestSeq)
      queue.entries = queue.entries.updated(index,pending.copy(requestedTick=queue.entries(index).requestedTick))
    drain()
    if (queue.entries.exists(_.actorId == event.actorRefId))
      reportFn(Map("vehicle_id" -> event.actorRefId,"link_id" -> linkIdFn(),"tick" -> tickFn(),
        "origin_at_end" -> data.mesoOriginAtEnd),"meso_entry_queued")
    scheduleWake()
  }

  def leave(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    queue.entries = queue.entries.filterNot(_.actorId == event.actorRefId)
    if (!state.registered.containsActor(event.actorRefId)) {
      sendFn(event.actorRefId,event.shardRefId,info(event.seq,false),EventTypeEnum.ReceiveLeaveLinkInfo.toString)
      return
    }
    val pending = PendingMesoExit(event.actorRefId,event.shardRefId,event.tick,event.seq,data)
    val index = queue.exits.indexWhere(_.actorId == event.actorRefId)
    if (index < 0) queue.exits = queue.exits :+ pending
    else if (event.seq >= queue.exits(index).requestSeq)
      queue.exits = queue.exits.updated(index,pending.copy(requestedTick=queue.exits(index).requestedTick))
    drain()
    scheduleWake()
  }

  private def release(p: PendingMesoExit, origin: Boolean, destination: Boolean = false): Unit = {
    queue.exits = queue.exits.filterNot(_.actorId == p.actorId)
    state.registered.removeActor(p.actorId)
    if (origin) {
      queue.originVehicles = queue.originVehicles.filterNot(_ == p.actorId)
    } else if (!destination) queue.credit -= 1.0
    queue.destinationVehicles = queue.destinationVehicles.filterNot(_ == p.actorId)
    leftFn(p.actorId)
    updateDensity()
    reportCapacity(p.actorId,origin,!origin && !destination,"meso_capacity_released",destination)
    sendFn(p.actorId,p.shardId,info(p.requestSeq,origin,exitWait=(tickFn()-p.requestedTick).toDouble),
      EventTypeEnum.ReceiveLeaveLinkInfo.toString)
  }

  private def admit(p: PendingMesoEntry): Unit = {
    queue.entries = queue.entries.filterNot(_.actorId == p.actorId)
    if (p.data.mesoOriginAtEnd) {
      queue.credit -= 1.0
      queue.originVehicles = queue.originVehicles :+ p.actorId
    }
    if (!p.data.mesoOriginAtEnd && p.data.nextLinkId == 0L)
      queue.destinationVehicles = queue.destinationVehicles :+ p.actorId
    state.registered.add(LinkRegister(p.actorId,p.shardId,p.data.actorType,p.data.actorCreationType,p.data.actorSize))
    enteredFn(p.actorId)
    updateDensity()
    reportCapacity(p.actorId,p.data.mesoOriginAtEnd,p.data.mesoOriginAtEnd,"meso_capacity_admitted")
    replyEntry(p.actorId,p.shardId,p.requestSeq,(tickFn()-p.requestedTick).toDouble)
  }

  private def reportCapacity(id: Long, origin: Boolean, debit: Boolean, label: String, destination: Boolean = false): Unit =
    reportFn(Map("vehicle_id" -> id,"link_id" -> linkIdFn(),"tick" -> tickFn(),
      "physical_occupancy" -> physicalCount,"storage_vehicles" -> config.storageVehicles,
      "flow_credit" -> queue.credit,"flow_debit" -> debit,"origin_at_end" -> origin,
      "destination_arrival" -> destination),label)

  private def drain(): Unit = {
    refresh()
    // Completing a leg does not traverse an outgoing movement. Origin ends were already charged on entry.
    queue.exits.filter(p => queue.originVehicles.contains(p.actorId) || queue.destinationVehicles.contains(p.actorId))
      .foreach(p => release(p,queue.originVehicles.contains(p.actorId),queue.destinationVehicles.contains(p.actorId)))
    refresh()
    while (queue.exits.nonEmpty && queue.credit > epsilon) release(queue.exits.head,false)
    var physical = queue.entries.find(p => !p.data.mesoOriginAtEnd)
    while (physical.nonEmpty && physicalCount < config.storageVehicles) {
      admit(physical.get)
      physical = queue.entries.find(p => !p.data.mesoOriginAtEnd)
    }
    var origin = queue.entries.find(_.data.mesoOriginAtEnd)
    while (origin.nonEmpty && queue.credit > epsilon && originBufferHasSpace) {
      admit(origin.get)
      origin = queue.entries.find(_.data.mesoOriginAtEnd)
    }
  }

  private def originBufferHasSpace: Boolean = queue.originVehicles.size.toDouble < math.ceil(rate)

  private def nextWake: Option[Tick] = {
    val needsFlow = queue.exits.nonEmpty || (originBufferHasSpace && queue.entries.exists(_.data.mesoOriginAtEnd))
    // A full origin buffer can only unblock when its owner receives downstream admission.
    if (!needsFlow) None
    else Some(tickFn() + math.max(1L, math.floor(math.max(0.0,-queue.credit)/rate + epsilon).toLong+1L))
  }

  private def scheduleWake(): Unit = nextWake.foreach { next =>
    if (queue.wakeTick.forall(_ > next)) {
      queue.wakeTick = Some(next)
      scheduleFn(next)
    }
  }

  def onTick(): Option[Tick] = {
    queue.wakeTick = None
    drain()
    val next = nextWake
    queue.wakeTick = next
    next
  }
}
