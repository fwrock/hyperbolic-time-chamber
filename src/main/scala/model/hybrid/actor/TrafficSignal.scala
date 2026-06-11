package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.entity.state.*

import org.interscity.htc.model.hybrid.entity.state.TrafficSignalState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.SpontaneousEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.SimulationUtil
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum.TrafficSignalChangeStatus
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.hybrid.entity.state.model.{ Phase, SignalState }
import org.interscity.htc.core.metrics.model.hybrid.TrafficSignalMetrics
import org.interscity.htc.model.hybrid.support.trafficsignal.TrafficSignalPhaseHandler

import scala.collection.mutable

class TrafficSignal(
  private val properties: Properties
) extends SimulationBaseActor[TrafficSignalState](
      properties = properties
    ) {

  private val simulationEnd: Tick = TrafficSignal.simulationEndTick

  private lazy val phaseHandler = new TrafficSignalPhaseHandler(
    getStateFn     = () => state,
    entityIdFn     = () => getEntityId,
    currentTickFn  = () => currentTick,
    simulationEnd  = simulationEnd,
    reportFn       = (data, label) => report(data, label),
    sendMessageFn  = (eid, shardId, data, eventType) => sendMessageTo(eid, shardId, data, eventType),
    getDependencyFn = node => getDependency(node),
    scheduleNextFn  = tick => onFinishSpontaneous(Some(tick)),
    finishFn        = () => onFinishSpontaneous(),
    logDebugFn      = logDebug
  )

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    logDebug(
      s"TrafficSignal ${getEntityId} initialized. First tick: ${state.startTick + state.offset}, cycleDuration: ${state.cycleDuration}, offset: ${state.offset}"
    )
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    phaseHandler.handlePhaseTransition(event.tick)
}

object TrafficSignal {
  lazy val simulationEndTick: Tick = {
    val simulationConfig = SimulationUtil.loadSimulationConfig()
    if (simulationConfig.extendSimulationIfPendingEventsAfterEnd) Long.MaxValue
    else simulationConfig.duration
  }
}
