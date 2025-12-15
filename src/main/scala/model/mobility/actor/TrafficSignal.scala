package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor

import model.mobility.entity.state.TrafficSignalState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.signal.{PhaseChangeData, SignalStatePredictionData, TrafficSignalChangeStatusData}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{PhaseChange, RequestSignalPrediction, TrafficSignalChangeStatus}
import org.interscity.htc.model.mobility.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.mobility.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable
import scala.collection.mutable.PriorityQueue

class TrafficSignal(
  private val properties: Properties
) extends BaseActor[TrafficSignalState](
      properties = properties
    ) {

  // Event-driven: Pre-schedule all phase transitions during initialization
  private val scheduledTransitions = mutable.PriorityQueue.empty[(Tick, PhaseChangeData)](
    Ordering.by[(Tick, PhaseChangeData), Tick](_._1).reverse
  )
  
  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    
    // Pre-calculate all phase transitions for entire simulation
    scheduleAllPhaseTransitions()
  }
  
  /** Event-driven: Schedule all phase transitions upfront (O(1) per transition) */
  private def scheduleAllPhaseTransitions(): Unit = {
    // Get simulation duration from config
    val simulationEnd = config.getLong("htc.simulation.duration")
    var cycleTick = 0L
    
    logInfo(s"Pre-scheduling phase transitions for signal ${getEntityId} until tick $simulationEnd")
    
    while (cycleTick < simulationEnd) {
      state.phases.foreach { phase =>
        val currentCycleTick = cycleTick % state.cycleDuration
        
        // Schedule green start
        val greenStartTick = cycleTick + phase.greenStart
        if (greenStartTick < simulationEnd) {
          val greenEndTick = greenStartTick + phase.greenDuration
          scheduledTransitions.enqueue((greenStartTick, PhaseChangeData(
            phaseId = s"${phase.origin}_green",
            phaseOrigin = phase.origin,
            newState = Green,
            validUntil = greenEndTick
          )))
        }
        
        // Schedule green end (red start)
        val redStartTick = cycleTick + phase.greenStart + phase.greenDuration
        if (redStartTick < simulationEnd) {
          val nextCycleTick = cycleTick + state.cycleDuration
          val nextGreenStart = nextCycleTick + phase.greenStart
          scheduledTransitions.enqueue((redStartTick, PhaseChangeData(
            phaseId = s"${phase.origin}_red",
            phaseOrigin = phase.origin,
            newState = Red,
            validUntil = if (nextGreenStart < simulationEnd) nextGreenStart else simulationEnd
          )))
        }
      }
      
      cycleTick += state.cycleDuration
    }
    
    logInfo(s"Scheduled ${scheduledTransitions.size} phase transitions")
    
    // Schedule first transition
    if (scheduledTransitions.nonEmpty) {
      val (firstTick, _) = scheduledTransitions.head
      onFinishSpontaneous(Some(firstTick))
    }
  }

  /** Event-driven: Execute scheduled phase transition (not continuous checking) */
  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (scheduledTransitions.isEmpty) {
      logDebug(s"No more phase transitions for signal ${getEntityId}")
      onFinishSpontaneous()
      return
    }
    
    val (transitionTick, phaseChange) = scheduledTransitions.dequeue()
    
    if (transitionTick != event.tick) {
      logWarn(s"Phase transition tick mismatch: expected $transitionTick, got ${event.tick}")
    }
    
    executePhaseTransition(transitionTick, phaseChange)
    
    // Schedule next transition
    if (scheduledTransitions.nonEmpty) {
      val (nextTick, _) = scheduledTransitions.head
      onFinishSpontaneous(Some(nextTick))
    } else {
      onFinishSpontaneous()
    }
  }
  
  /** Execute single phase transition and notify nodes */
  private def executePhaseTransition(currentTick: Tick, phaseChange: PhaseChangeData): Unit = {
    // Update internal state
    state.signalStates.get(phaseChange.phaseOrigin).foreach { signalState =>
      val oldState = signalState.state
      signalState.state = phaseChange.newState
      signalState.remainingTime = phaseChange.validUntil - currentTick
      
      // Only notify if state actually changed
      if (oldState != phaseChange.newState) {
        logDebug(s"Phase transition: ${phaseChange.phaseOrigin} $oldState -> ${phaseChange.newState} @ $currentTick")
        
        notifyNodes(
          SignalState(
            state = phaseChange.newState,
            remainingTime = signalState.remainingTime,
            nextTick = phaseChange.validUntil
          ),
          state.nodes,
          phaseChange.phaseOrigin,
          phaseChange.validUntil
        )
      }
    }
  }
  
  /** Handle signal prediction requests from vehicles (event-driven API) */
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    EventTypeEnum.valueOf(event.eventType) match {
      case RequestSignalPrediction =>
        handleSignalPredictionRequest(event)
      case _ =>
        logWarn(s"Unhandled event type: ${event.eventType}")
    }
  }
  
  /** Provide time-bounded signal state prediction for vehicle planning */
  private def handleSignalPredictionRequest(event: ActorInteractionEvent): Unit = {
    // Extract request parameters (would come from event data)
    val requestor = event.toIdentity
    val queryTick = event.tick
    val horizonTick = queryTick + 100 // Default horizon, should come from request
    
    // Get all phases and find next transitions
    state.phases.foreach { phase =>
      val prediction = getSignalStatePrediction(phase.origin, queryTick, horizonTick)
      
      sendMessageTo(
        requestor.id,
        requestor.classType,
        data = prediction,
        eventType = EventTypeEnum.SignalPrediction.toString
      )
    }
  }
  
  /** Calculate signal state prediction for given phase and time horizon */
  def getSignalStatePrediction(
    phaseOrigin: String,
    queryTick: Tick,
    horizonTick: Tick
  ): SignalStatePredictionData = {
    
    val currentState = state.signalStates.get(phaseOrigin)
      .map(_.state)
      .getOrElse(Red)
    
    // Find next transition within horizon
    val nextTransition = scheduledTransitions.find { case (tick, change) =>
      change.phaseOrigin == phaseOrigin && tick > queryTick && tick <= horizonTick
    }
    
    SignalStatePredictionData(
      currentState = currentState,
      validUntil = nextTransition.map(_._1).getOrElse(horizonTick),
      nextState = nextTransition.map(_._2.newState),
      nextTransitionTick = nextTransition.map(_._1)
    )
  }
  
  // Legacy tick-driven method (kept for backward compatibility during migration)
  private def handlePhaseTransitionLegacy(currentTick: Tick): Unit =
    state.phases.foreach {
      phase =>
        val currentCycleTick = (currentTick + state.offset) % state.cycleDuration
        val newState = calcNewState(currentCycleTick, phase)
        val nextTickTime =
          currentCycleTick + state.cycleDuration - (currentCycleTick % state.cycleDuration)

        val changedOrigins = mutable.Set[String]()

        state.signalStates.get(phase.origin).foreach {
          signalState =>
            signalState.remainingTime = phase.greenStart + phase.greenDuration - currentCycleTick
            if (signalState.state != newState) {
              notifyNodes(
                SignalState(
                  state = newState,
                  remainingTime = signalState.remainingTime,
                  nextTick = nextTickTime
                ),
                state.nodes,
                phase.origin,
                nextTickTime
              )
              changedOrigins.add(phase.origin)
            }
            signalState.state = newState
        }
        onFinishSpontaneous(Some(nextTickTime))
    }

  private def notifyNodes(
    signalState: SignalState,
    nodes: List[String],
    phaseOrigin: String,
    nextTick: Tick
  ): Unit =
    nodes.foreach {
      node =>
        val data = TrafficSignalChangeStatusData(
          signalState = signalState,
          phaseOrigin = phaseOrigin,
          nextTick = nextTick
        )
        val dependency = getDependency(node)
        sendMessageTo(dependency.id, dependency.classType, data, TrafficSignalChangeStatus.toString)
    }

  private def calcNewState(currentCycleTick: Tick, phase: Phase): TrafficSignalPhaseStateEnum =
    if (
      currentCycleTick >= phase.greenStart && currentCycleTick < phase.greenStart + phase.greenDuration
    ) {
      Green
    } else {
      Red
    }
}
