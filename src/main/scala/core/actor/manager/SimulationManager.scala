package org.interscity.htc
package core.actor.manager

import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import core.util.SimulationUtil.loadSimulationConfig

import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, PrepareSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData
import org.htc.protobuf.core.entity.event.control.load.FinishLoadDataEvent
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.entity.event.control.load.LoadDataEvent
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, SIMULATION_MANAGER_ACTOR_NAME}

import scala.collection.mutable
import scala.compiletime.uninitialized

class SimulationManager(
  val simulationPath: String = null
) extends BaseManager[DefaultState](
      actorId = SIMULATION_MANAGER_ACTOR_NAME,
      timeManager = null,
      data = null,
      dependencies = mutable.Map.empty
    ) {

  private var timeManager: ActorRef = uninitialized
  private var poolTimeManager: ActorRef = uninitialized
  private var loadManager: ActorRef = uninitialized
  private var configuration: Simulation = uninitialized
  private var selfProxy: ActorRef = null

  override def handleEvent: Receive = {
    case event: PrepareSimulationEvent   => prepareSimulation(event)
    case event: FinishLoadDataEvent      => startSimulation()
    case event: TimeManagerRegisterEvent => registerPoolTimeManager(event)
  }

  override def onStart(): Unit =
    getSelfProxy ! PrepareSimulationEvent(
      configuration = simulationPath
    )

  private def startSimulation(): Unit = {
    loadManager ! DestructEvent(actorRef = getPath)
    logEvent("Start simulation")
    createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME) ! StartSimulationTimeEvent(
      startTick = configuration.startTick,
      actorRef = getPath,
      data = Some(StartSimulationTimeData(startTime = System.currentTimeMillis()))
    )
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(SIMULATION_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }

  private def registerPoolTimeManager(event: TimeManagerRegisterEvent): Unit = {
    poolTimeManager = event.actorRef
    loadManager = createSingletonLoadManager()

    createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! LoadDataEvent(
      actorRef = selfProxy,
      actorsDataSources = configuration.actorsDataSources
    )
  }

  private def prepareSimulation(event: PrepareSimulationEvent): Unit = {
    configuration = loadSimulationConfig(event.configuration)
    logEvent(s"Run simulation: \n$configuration")
    timeManager = createSingletonTimeManager()
  }

  private def createSingletonTimeManager(): ActorRef =
    createSingletonManager(
      manager = TimeManager.props(
        simulationDuration = configuration.duration,
        simulationManager = getSelfProxy,
        parentManager = None
      ),
      name = GLOBAL_TIME_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonLoadManager(): ActorRef =
    createSingletonManager(
      manager = LoadDataManager.props(
        timeManager = poolTimeManager,
        poolTimeManager = poolTimeManager,
        simulationManager = selfProxy
      ),
      name = LOAD_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )
}
