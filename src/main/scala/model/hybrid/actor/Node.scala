package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.util.StringPool
import org.interscity.htc.model.hybrid.entity.state.*

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent

import scala.collection.mutable
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.hybrid.support.node.NodeEventHandler
import org.interscity.htc.model.hybrid.util.CityMapUtil
import org.interscity.htc.core.entity.actor.ShardActorId

class Node(
  private val properties: Properties
) extends SimulationBaseActor[NodeState](
      properties = properties
    ) {

  override protected def internStateStrings(s: NodeState): NodeState = {
    val internedConnections = mutable.Map.from(
      s.connections.map { case (k, v) => StringPool.intern(k) -> v }
    )
    val internedSignals = mutable.Map.from(
      s.signals.map { case (k, v) => StringPool.intern(k) -> v }
    )
    s.copy(
      links       = s.links.map(StringPool.intern),
      connections = internedConnections,
      signals     = internedSignals
    )
  }

  private val pendingSignals
    : mutable.Map[String, _root_.org.interscity.htc.model.hybrid.entity.state.model.SignalState] =
    mutable.Map.empty

  private lazy val nodeHandler = new NodeEventHandler(
    getStateFn          = () => state,
    entityIdFn          = () => getEntityId,
    currentTickFn       = () => currentTick,
    pendingSignals      = pendingSignals,
    reportFn            = (data, label) => report(data, label),
    sendMessageFn       = (eid, shardId, data, eventType) =>
      sendMessageTo(eid, shardId, data, eventType, LoadBalancedDistributed),
    getLinkDependencyFn = linkId =>
      CityMapUtil.edgeLabelsById.get(linkId)
        .map(e => ShardActorId(entityId = e.id, classType = e.classType, shardBucket = e.resourceId)),
    logWarnFn           = logWarn,
    logDebugFn          = logDebug
  )

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)

    if (state != null) {
      if (pendingSignals.nonEmpty) {
        pendingSignals.foreach {
          case (phaseOrigin, signalState) =>
            state.signals.put(phaseOrigin, signalState)
        }
        pendingSignals.clear()
      }
    }
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    onFinishSpontaneous(None)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterBusStopData       => nodeHandler.handleRegisterBusStop(event, d)
      case d: RegisterSubwayStationData => nodeHandler.handleRegisterSubwayStation(event, d)
      case d: RequestSignalStateData    => nodeHandler.handleRequestSignalState(event, d)
      case d: TrafficSignalChangeStatusData =>
        nodeHandler.handleReceiveSignalChangeStatus(event, d)
      case _ =>
        logWarn("Event not handled")
    }
}
