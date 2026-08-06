package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.types.Tick
import core.entity.event.SpontaneousEvent

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import core.entity.event.EntityEnvelopeEvent
import core.util.{ IdUtil, StringPool, StringUtil }
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.model.{ DynamicLinkCost, LinkRegister, VehicleInLane }
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.util.DynamicWeightCache
import org.interscity.htc.model.hybrid.micro.strategy.{ DefaultMicroSimulationStrategy, LaneChangeStrategy, MicroSimulationStrategy, NoLaneChangeStrategy }
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.event.data.VehicleLinkFlowData
import org.interscity.htc.core.metrics.model.hybrid.LinkMetrics
import org.interscity.htc.model.hybrid.entity.event.data.link.{ LinkSignalStateData, RegisterLinkCapacityData }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import org.interscity.htc.model.hybrid.support.link.{
  LinkMetricsReporter, LinkMicroSimulationHandler, LinkVehicleFlowHandler
}

import scala.collection.mutable

/** Hybrid link actor representing a road segment that can operate in MESO or MICRO simulation mode.
  *
  * <h2>Overview</h2> The Link actor manages vehicle flow through a road segment, supporting both:
  * <ul> <li><b>MESO mode</b>: Aggregate flow calculations using speed-density relationships</li>
  * <li><b>MICRO mode</b>: Individual vehicle dynamics with car-following and lane management</li>
  * </ul>
  *
  * <h2>Responsibilities</h2> <ul> <li>Vehicle entry/exit management</li> <li>Mode-specific traffic
  * simulation (delegated to strategies)</li> <li>Dynamic cost calculation for routing</li>
  * <li>Performance metrics and reporting</li> <li>Inter-actor communication (vehicles, nodes,
  * signals)</li> </ul>
  *
  * <h2>Micro Simulation</h2> In MICRO mode, the link: <ul> <li>Maintains per-lane vehicle
  * queues</li> <li>Executes sub-tick simulations (typically 10 sub-ticks per global tick)</li>
  * <li>Delegates car-following logic to [[MicroSimulationStrategy]]</li> <li>Delegates lane-change
  * logic to [[LaneChangeStrategy]]</li> <li>Publishes dynamic costs to Kafka for routing</li> </ul>
  *
  * <h2>Configuration</h2> Link behavior is configured via: <ul>
  * <li>`htc.routing.link-cost.publish-interval`: Cost publish frequency (ticks)</li>
  * <li>`htc.routing.link-cost.cache-ttl`: Cost cache TTL (ticks)</li> </ul>
  *
  * @param properties
  *   Actor properties including entity ID, shard configuration
  * @see
  *   LinkState for link state model
  * @see
  *   MicroSimulationStrategy for micro simulation algorithms
  * @see
  *   LaneChangeStrategy for lane change behavior
  */
class Link(
  private val properties: Properties
) extends SimulationBaseActor[LinkState](
      properties = properties
    ) {

  override protected def internStateStrings(s: LinkState): LinkState =
    s.copy(
      from = StringPool.intern(s.from),
      to   = StringPool.intern(s.to)
    )

  /** Tracks when each vehicle entered the link (for travel time calculation) */
  private val vehicleEntryTick: mutable.Map[String, Tick] = mutable.Map.empty

  /** Accumulated waiting time per vehicle (seconds) */
  private val vehicleWaitingSeconds: mutable.Map[String, Double] = mutable.Map.empty

  /** Flag indicating if micro-tick simulation is scheduled */
  private var microTickScheduled: Boolean = false

  /** Current signal phase at the exit node of this link; None when uncontrolled. */
  private var signalAtExit: Option[TrafficSignalPhaseStateEnum] = None

  /** Grace-period counter for MICRO links: stays alive for MICRO_GRACE_TICKS extra ticks when empty
    * to avoid race conditions with late-arriving vehicles from previous batches.
    */
  private var emptyGraceTick: Int = 0
  private val MICRO_GRACE_TICKS: Int = 5

  /** Configuration: interval between dynamic cost publications (ticks) */
  private val costPublishInterval: Int =
    try com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.publish-interval")
    catch { case _: Exception => 10 }

  /** Configuration: TTL for cached dynamic costs (ticks) */
  private val cacheTtl: Int =
    try com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.cache-ttl")
    catch { case _: Exception => 60 }

  private lazy val metricsReporter = new LinkMetricsReporter(
    reportFn                   = (data, label) => report(data = data, label = label),
    entityIdFn                 = () => getEntityId,
    currentTickFn              = () => currentTick,
    cacheTtl                   = cacheTtl,
    costPublishInterval        = costPublishInterval,
    getLinkStateFn             = () => state,
    getVehicleEntryTickFn      = id => vehicleEntryTick.get(id),
    getVehicleWaitingSecondsFn = id => vehicleWaitingSeconds.getOrElse(id, 0.0),
    getRegisteredVehicleIdsFn  = () => state.registered.iterator.map(_.actorId).toIterable,
    logWarnFn                  = msg => logWarn(msg)
  )

  private lazy val microSimHandler = new LinkMicroSimulationHandler(
    entityIdFn                    = () => getEntityId,
    currentTickFn                 = () => currentTick,
    sendMessageFn                 = (id, shard, d, et) => sendMessageTo(entityId = id, shardId = shard, data = d, eventType = et, actorType = LoadBalancedDistributed),
    getLinkStateFn                = () => state,
    setLinkStateFn                = s => state = s,
    getVehicleEntryTickFn         = id => vehicleEntryTick.get(id),
    removeVehicleEntryTickFn      = id => vehicleEntryTick.remove(id),
    getVehicleWaitingSecondsFn    = id => vehicleWaitingSeconds.getOrElse(id, 0.0),
    removeVehicleWaitingSecondsFn = id => vehicleWaitingSeconds.remove(id),
    vehicleWaitingSeconds         = vehicleWaitingSeconds,
    isMicroScheduledFn            = () => microTickScheduled,
    setMicroScheduledFn           = v => microTickScheduled = v,
    getSignalAtExitFn             = () => signalAtExit,
    metricsReporter               = metricsReporter,
    logDebugFn                    = msg => logDebug(msg)
  )

  private lazy val vehicleFlowHandler = new LinkVehicleFlowHandler(
    entityIdFn                  = () => getEntityId,
    currentTickFn               = () => currentTick,
    sendMessageFn               = (id, shard, d, et) => sendMessageTo(entityId = id, shardId = shard, data = d, eventType = et, actorType = LoadBalancedDistributed),
    scheduleEventFn             = tick => scheduleEvent(tick),
    getLinkStateFn              = () => state,
    setLinkStateFn              = s => state = s,
    putVehicleEntryTickFn       = (id, t) => vehicleEntryTick.put(id, t),
    getVehicleEntryTickFn       = id => vehicleEntryTick.get(id),
    removeVehicleEntryTickFn    = id => vehicleEntryTick.remove(id),
    getOrUpdateVehicleWaitingFn = id => vehicleWaitingSeconds.getOrElseUpdate(id, 0.0),
    removeVehicleWaitingFn      = id => vehicleWaitingSeconds.remove(id),
    getVehicleWaitingSecondsFn  = id => vehicleWaitingSeconds.getOrElse(id, 0.0),
    isMicroScheduledFn          = () => microTickScheduled,
    setMicroScheduledFn         = v => microTickScheduled = v,
    metricsReporter             = metricsReporter,
    findVehicleLaneFn           = id => microSimHandler.findVehicleLane(id),
    findLeastOccupiedLaneFn     = () => microSimHandler.findLeastOccupiedLane(),
    logDebugFn                  = msg => logDebug(msg)
  )

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    if (state.isMicroMode) microSimHandler.initializeMicroMode()
    metricsReporter.publishDynamicCost()
    // One-time report so the entry Node can seed its availableCapacity counter before any
    // vehicle ever requests access to this link. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    sendMessageTo(
      entityId  = state.from,
      shardId   = "hybrid.actor.Node",
      data      = RegisterLinkCapacityData(linkId = getEntityId, capacity = state.capacity.toInt),
      eventType = EventTypeEnum.RegisterLinkCapacity.toString,
      actorType = LoadBalancedDistributed
    )
    logDebug(s"Link initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkData        => handleEnterLink(event, d)
      case d: LeaveLinkData        => handleLeaveLink(event, d)
      case d: LinkSignalStateData  => signalAtExit = Some(d.phase)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }

  /** Handles spontaneous (time-triggered) events for MICRO mode simulation.
    *
    * In MICRO mode:
    *   - Executes sub-tick simulation for all vehicles
    *   - Schedules next tick if vehicles remain
    *   - Stops scheduling when link is empty
    *
    * In MESO mode: No-op (vehicles manage their own timing)
    *
    * @param event
    *   Spontaneous event from time manager
    */
  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    // logInfo(s"[LINK] actSpontaneous tick=$currentTick id=$getEntityId =${state.isMicroMode} vehicles=${if (state.isMicroMode) state.totalVehiclesInMicro else 0}")
    if (!state.isMicroMode) {
      microTickScheduled = false
      onFinishSpontaneous(None)
      return
    }

    val vehicleCount = state.totalVehiclesInMicro
    val hasVehicles = vehicleCount > 0

    if (!hasVehicles) {
      emptyGraceTick += 1
      if (emptyGraceTick <= MICRO_GRACE_TICKS) {
        onFinishSpontaneous(Some(currentTick + 1))
      } else {
        emptyGraceTick = 0
        microTickScheduled = false
        onFinishSpontaneous(None)
      }
      return
    }

    emptyGraceTick = 0
    microTickScheduled = true
    microSimHandler.handleGlobalTick(currentTick)

    val hasVehiclesAfterTick = state.totalVehiclesInMicro > 0
    if (hasVehiclesAfterTick) {
      emptyGraceTick = 0
      onFinishSpontaneous(Some(currentTick + 1))
    } else {
      emptyGraceTick += 1
      if (emptyGraceTick <= MICRO_GRACE_TICKS) {
        onFinishSpontaneous(Some(currentTick + 1))
      } else {
        emptyGraceTick = 0
        microTickScheduled = false
        onFinishSpontaneous(None)
      }
    }
  }

  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    metricsReporter.ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${event.actorRefId} entering link (mode=${state.simulationMode})")
    reportToSpecificReporter(
      ReportTypeEnum.clickhouse,
      VehicleLinkFlowData(
        linkId = getEntityId, eventType = "enter", vehicleId = event.actorRefId,
        actorType = data.actorType.toString, actorCreationType = data.actorCreationType.toString,
        vehicleCountOnLink = state.registered.size
      ),
      "vehicle_link_flow"
    )
    if (state.isMicroMode) vehicleFlowHandler.handleEnterLinkMicro(event, data)
    else vehicleFlowHandler.handleEnterLinkMeso(event, data)
  }

  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    metricsReporter.ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${event.actorRefId} leaving link")
    val wasRegistered     = state.registered.exists(_.actorId == event.actorRefId)
    val vehiclesRemaining = math.max(0, state.registered.size - (if (wasRegistered) 1 else 0))
    reportToSpecificReporter(
      ReportTypeEnum.clickhouse,
      VehicleLinkFlowData(
        linkId = getEntityId, eventType = "leave", vehicleId = event.actorRefId,
        actorType = data.actorType.toString, actorCreationType = data.actorCreationType.toString,
        vehicleCountOnLink = vehiclesRemaining
      ),
      "vehicle_link_flow"
    )
    vehicleFlowHandler.handleLeaveLink(event, data, wasRegistered)
  }
  /** Handles link destruction on simulation termination.
    *
    * Forwards DestructEvent to all registered vehicles to ensure proper cleanup. This is critical
    * for MICRO mode where vehicles may have stopped scheduling their own events and rely on the
    * link for lifecycle management.
    *
    * @param event
    *   DestructEvent from the system
    */
  override def onDestruct(event: DestructEvent): Unit =
    state.registered.foreach {
      reg =>
        val shardRef = getShardRef(IdUtil.format(StringUtil.getModelClassName(reg.shardId)))
        shardRef ! EntityEnvelopeEvent(
          IdUtil.format(reg.actorId),
          DestructEvent(actorRef = self.path.toString)
        )
    }
}

object Link {
  def apply(properties: Properties): Link =
    new Link(properties)
}
