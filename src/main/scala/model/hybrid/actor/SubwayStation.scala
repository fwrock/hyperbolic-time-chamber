package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.actor.Subway
import org.interscity.htc.model.hybrid.entity.state.*
import org.interscity.htc.model.hybrid.entity.state.{ SubwayState, SubwayStationState }

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.{ IdUtil, IdentifyUtil, JsonUtil }
import org.interscity.htc.core.util.SimulationUtil
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ RegisterSubwayPassengerData, RegisterSubwayStationData, SubwayLoadPassengerData, SubwayRequestPassengerData }
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, MovableStatusEnum, SubwayStationStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.SubwayStationStateEnum.{ Start, Working }
import org.interscity.htc.model.hybrid.entity.state.model.{ RoutePathItem, SubwayInformation, SubwayLineInformation }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.model.hybrid.SubwayStationMetrics
import org.interscity.htc.model.hybrid.support.subwaystation.{ SubwayPassengerManager, SubwayStationCreator }
import core.actor.trace.ActorTrace
import scala.collection.mutable

class SubwayStation(
  private val properties: Properties
) extends SimulationBaseActor[SubwayStationState](
      properties = properties
    ) {

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration
  private var pendingSubwayAckCount: Int  = 0
  private var pendingSubwayNextTick: Option[Tick] = None
  private val dynamicInitPollIntervalTicks: Tick = 1L

  private lazy val subwayCreator = new SubwayStationCreator(
    getStateFn          = () => state,
    entityIdFn          = () => getEntityId,
    currentTickFn       = () => currentTick,
    reportFn            = (data, label) => report(data, label),
    spawnDynamicActorFn = (ct, eid, sd) => spawnDynamicActor(classType = ct, entityId = eid, stateData = sd),
    addDependencyFn     = (id, ref) => { dependencies(id) = ref },
    logWarnFn           = logWarn,
    logDebugFn          = logDebug,
    logErrorFn          = logError
  )

  private lazy val passengerManager = new SubwayPassengerManager(
    getStateFn    = () => state,
    entityIdFn    = () => getEntityId,
    currentTickFn = () => currentTick,
    reportFn      = (data, label) => report(data, label),
    sendMessageFn = (entityId, shardId, data) => sendMessageTo(entityId, shardId, data)
  )

  override def onInitialize(event: InitializeEvent): Unit =
    super.onInitialize(event)

  override def requiresPostLoadRegistration: Boolean = true

  override def handlePostLoadRegistration(): Unit = {
    val nodeOpt = getDependencyOption(state.nodeId).orElse(
      relationships.values.find(
        d => d.classType != null && d.classType.endsWith("Node")
      )
    )
    nodeOpt match {
      case Some(node) =>
        sendMessageTo(
          node.id,
          node.classType,
          RegisterSubwayStationData(
            lines = state.lines.keys.toList
          ),
          "RegisterSubwayStation",
          LoadBalancedDistributed
        )
        logDebug(s"SubwayStation ${getEntityId} registered with node ${node.id}")
      case None if state.nodeId != null && state.nodeId.nonEmpty =>
        logWarn(
          s"SubwayStation ${getEntityId}: relationships map empty (available keys: [${relationships.keys
              .mkString(", ")}]). " +
            s"Registering with node ${state.nodeId} via direct routing."
        )
        sendMessageTo(
          state.nodeId,
          "hybrid.actor.Node",
          RegisterSubwayStationData(
            lines = state.lines.keys.toList
          ),
          "RegisterSubwayStation",
          LoadBalancedDistributed
        )
      case None =>
        logWarn(
          s"SubwayStation ${getEntityId}: could not find node dependency and nodeId is null. Registration skipped."
        )
    }
  }

  override protected def onDynamicActorInitialized(entityId: String, classType: String): Unit = {
    pendingSubwayAckCount -= 1
    if (pendingSubwayAckCount < 0) pendingSubwayAckCount = 0
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    if (currentTick >= simulationEnd) {
      logDebug(
        s"SubwayStation ${getEntityId} reached simulation end tick=$simulationEnd, stopping scheduling"
      )
      onFinishSpontaneous(None)
    } else if (pendingSubwayAckCount > 0) {
      onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
    } else if (pendingSubwayNextTick.exists(currentTick < _)) {
      onFinishSpontaneous(pendingSubwayNextTick)
    } else
      state.status match
        case Start =>
          state.status = Working
          pendingSubwayAckCount = subwayCreator.createSubwayFrom(state.lines)
          scheduleNextTick()
        case Working =>
          pendingSubwayAckCount = subwayCreator.createSubwayFrom(subwayCreator.filterLinesByNextTick())
          scheduleNextTick()
        case _ =>
          logWarn(s"Event current status not handled ${state.status}")
          onFinishSpontaneous(None)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterSubwayPassengerData => passengerManager.handleRegisterPassenger(event, d.line)
      case d: SubwayRequestPassengerData  => passengerManager.handleSubwayRequestPassenger(event, d)
      case _                              => logWarn("Event not handled")
    }

  private def scheduleNextTick(): Unit = {
    val nextTickOpt = subwayCreator.computeNextTick(simulationEnd)
    pendingSubwayNextTick = nextTickOpt
    if (pendingSubwayAckCount > 0)
      onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
    else
      onFinishSpontaneous(nextTickOpt, destruct = false)
  }
}
