package org.interscity.htc
package core.actor.manager

import core.actor.manager.base.BaseTimeManager
import core.entity.event.SpontaneousEvent
import core.types.Tick
import core.entity.control.LocalTimeManagerInfo

import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution._
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME}

import scala.collection.mutable

/**
 * Global Time Manager - Central coordinator for distributed time management.
 * 
 * The GlobalTimeManager serves as the master coordinator for all Local Time Managers
 * in the Hyperbolic Time Chamber simulation system. It manages the global simulation
 * clock, coordinates time advancement across different simulation paradigms, and
 * creates distributed pools of Local Time Managers using Apache Pekko clustering.
 * 
 * Key responsibilities include:
 * - Creating and managing pools of Local Time Managers by type (DES, TimeStepped, Optimistic)
 * - Coordinating global time advancement across all LTMs
 * - Broadcasting time synchronization events
 * - Collecting and aggregating time reports from LTMs
 * - Managing simulation lifecycle at the global level
 * 
 * The GlobalTimeManager uses native Pekko ClusterRouterPool for efficient distribution
 * of LTM instances across cluster nodes, providing both scalability and fault tolerance.
 * 
 * @param simulationDuration Maximum duration of the simulation in ticks
 * @param simulationManager Reference to the main simulation manager
 * 
 * @author Hyperbolic Time Chamber Team
 * @version 1.4.0
 * @since 1.0.0
 */
class GlobalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef
) extends BaseTimeManager(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  /**
   * Map of Local Time Manager pools organized by their type.
   * Each entry contains a ClusterRouterPool ActorRef for the specific LTM type.
   */
  private val localTimeManagerPools = mutable.Map[LocalTimeManagerTypeEnum, ActorRef]()
  
  /**
   * Map of registered Local Time Managers with their coordination information.
   * Contains ActorRef to LocalTimeManagerInfo mappings for active LTMs.
   */
  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerInfo] = mutable.Map()

  /**
   * Called when the Global Time Manager starts up.
   * 
   * Performs parent class initialization and creates Local Time Manager pools
   * for all supported simulation paradigms.
   */
  override def onStart(): Unit = {
    super.onStart()
    createLocalTimeManagerPools()
  }

  /**
   * Cria pools para cada tipo de Local Time Manager
   */
  private def createLocalTimeManagerPools(): Unit = {
    LocalTimeManagerTypeEnum.values.foreach { localTimeManagerType =>
      val totalInstances = 64
      val maxInstancesPerNode = Math.max(8, totalInstances / 8)
      
      val pool = context.actorOf(
        ClusterRouterPool(
          RoundRobinPool(0),
          ClusterRouterPoolSettings(
            totalInstances = totalInstances,
            maxInstancesPerNode = maxInstancesPerNode,
            allowLocalRoutees = true
          )
        ).props(createLocalTimeManagerProps(localTimeManagerType)),
        name = s"$POOL_TIME_MANAGER_ACTOR_NAME-${localTimeManagerType.toString}"
      )
      
      localTimeManagerPools.put(localTimeManagerType, pool)
      
      // Registrar pool com SimulationManager
      simulationManager ! TimeManagerRegisterEvent(
        actorRef = pool,
        localTimeManagerType = localTimeManagerType
      )
      
      logInfo(s"Pool created for ${localTimeManagerType}: ${pool.path}")
    }
  }

  /**
   * Creates Props for a specific Local Time Manager type.
   * 
   * This factory method creates the appropriate Props object for instantiating
   * Local Time Managers based on their type. Each LTM type has different
   * constructor parameters and capabilities.
   * 
   * @param ltmType The type of Local Time Manager to create Props for
   * @return Props object configured for the specific LTM implementation
   */
  private def createLocalTimeManagerProps(ltmType: LocalTimeManagerTypeEnum): Props = {
    ltmType match {
      case LocalTimeManagerTypeEnum.DiscreteEventSimulation =>
        DiscreteEventSimulationTimeManager.props(simulationDuration, simulationManager, self)
      case LocalTimeManagerTypeEnum.TimeStepped =>
        TimeSteppedTimeManager.props(simulationDuration, simulationManager, self)
      case LocalTimeManagerTypeEnum.OptimisticTimeWindow =>
        OptimisticTimeWindowTimeManager.props(simulationDuration, simulationManager, self)
    }
  }

  /**
   * Specific event receiver for Global Time Manager.
   * 
   * Handles events specific to global time coordination including:
   * - LTM registration events from newly created Local Time Managers
   * - Local time reports from LTMs for synchronization
   * 
   * @return Partial function for handling GTM-specific events
   */
  override protected def specificReceive: Receive = {
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerLocalTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled)
  }

  /**
   * Registers a Local Time Manager with the Global Time Manager.
   * 
   * Adds the LTM to the coordination registry and initializes its
   * tracking information for time synchronization.
   * 
   * @param event The registration event containing LTM details
   */
  private def registerLocalTimeManager(event: TimeManagerRegisterEvent): Unit = {
    localTimeManagers.put(
      event.actorRef,
      LocalTimeManagerInfo(
        tick = localTickOffset,
        localTimeManagerType = event.localTimeManagerType
      )
    )
    logInfo(s"Registered Local Time Manager: ${event.localTimeManagerType}")
  }

  /**
   * Handle para relatórios de Local Time Managers
   */
  private def handleLocalTimeReport(
    localManager: ActorRef,
    tick: Tick,
    hasScheduled: Boolean
  ): Unit = {
    localTimeManagers.get(localManager) match {
      case Some(info) =>
        localTimeManagers.update(
          localManager,
          info.copy(
            tick = tick,
            hasSchedule = hasScheduled,
            isProcessed = true
          )
        )
        
        // Se todos os LTMs processaram, calcular próximo tempo global
        if (localTimeManagers.values.forall(_.isProcessed)) {
          calculateAndBroadcastNextGlobalTick()
        }
        
      case None =>
        logWarn(s"A report received from Local Time Manager was not registered: $localManager")
    }
  }

  /**
   * Calculates the next global time for broadcast local time managers ticks
   */
  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val scheduledLTMs = localTimeManagers.values.filter(_.hasSchedule)
    val nextTick = if (scheduledLTMs.nonEmpty) {
      scheduledLTMs.map(_.tick).min
    } else {
      localTimeManagers.values.filter(_.isProcessed).map(_.tick).min
    }
    
    localTickOffset = nextTick
    tickOffset = nextTick - initialTick
    
    logDebug(s"Next global time: $localTickOffset")
    
    localTimeManagers.keys.foreach { ltm =>
      localTimeManagers.get(ltm).foreach { info =>
        localTimeManagers.update(ltm, info.copy(isProcessed = false))
      }
    }
    
    notifyLocalTimeManagers(UpdateGlobalTimeEvent(localTickOffset))
  }

  /**
   * Notifica todos os Local Time Managers
   */
  private def notifyLocalTimeManagers(event: Any): Unit = {
    localTimeManagers.keys.foreach { ltm =>
      ltm ! event
    }
  }

  override protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"Global Time Manager started at tick ${start.startTick}")
    notifyLocalTimeManagers(start)
  }

  override protected def onSimulationPause(): Unit = {
    notifyLocalTimeManagers(PauseSimulationEvent)
  }

  override protected def onSimulationResume(): Unit = {
    notifyLocalTimeManagers(ResumeSimulationEvent)
  }

  override protected def onSimulationStop(): Unit = {
    notifyLocalTimeManagers(StopSimulationEvent)
  }

  override protected def getLabel: String = "GlobalTimeManager"

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit = {
    if (isRunning) {
      logDebug(s"Spontaneous event received in Global Time Manager: ${spontaneous.tick}")
    }
  }

  /**
   * Returns statistics about the Global Time Manager.
   * 
   * Provides comprehensive metrics about the global time coordination state
   * including current time, registered LTMs, active pools, and running status.
   * 
   * @return Map containing key performance and state metrics
   */
  override def getStatistics: Map[String, Any] = {
    Map(
      "currentGlobalTime" -> localTickOffset,
      "registeredLTMs" -> localTimeManagers.size,
      "ltmPools" -> localTimeManagerPools.keys.map(_.toString).toList,
      "isRunning" -> isRunning
    )
  }
}

/**
 * Companion object for GlobalTimeManager containing factory methods.
 */
object GlobalTimeManager {
  
  /**
   * Creates Props for GlobalTimeManager actor instantiation.
   * 
   * @param simulationDuration Maximum duration of the simulation in ticks
   * @param simulationManager Reference to the main simulation manager
   * @return Props object for creating GlobalTimeManager actors
   */
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef
  ): Props =
    Props(classOf[GlobalTimeManager], simulationDuration, simulationManager)
}
