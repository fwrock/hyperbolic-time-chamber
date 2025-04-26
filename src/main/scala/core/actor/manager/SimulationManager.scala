package org.interscity.htc
package core.actor.manager

import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import core.util.SimulationUtil.loadSimulationConfig

import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, PrepareSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.entity.event.control.load.{FinishLoadDataEvent, LoadDataEvent}
import org.interscity.htc.core.entity.event.control.report.RegisterReportersEvent
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME, SIMULATION_MANAGER_ACTOR_NAME}

import scala.compiletime.uninitialized

class SimulationManager(
  val simulationPath: String = null
) extends BaseManager[DefaultState](
      actorId = SIMULATION_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  private var timeManager: ActorRef = uninitialized
  private var poolTimeManager: ActorRef = uninitialized
  private var loadManager: ActorRef = uninitialized
  private var reportManager: ActorRef = uninitialized
  private var configuration: Simulation = uninitialized
  private var selfProxy: ActorRef = null

  override def handleEvent: Receive = {
    case event: PrepareSimulationEvent   => prepareSimulation(event)
    case event: FinishLoadDataEvent      => startSimulation()
    case event: TimeManagerRegisterEvent => registerPoolTimeManager(event)
    case event: RegisterReportersEvent   => registerReporters(event)
  }

  override def onStart(): Unit =
    getSelfProxy ! PrepareSimulationEvent(
      configuration = simulationPath
    )

  private def startSimulation(): Unit = {
    loadManager ! DestructEvent(actorRef = getPath)
    logInfo("Start simulation")
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

  private def registerReporters(event: RegisterReportersEvent): Unit = {
    reporters = event.reporters
    startLoadData()
  }

  private def registerPoolTimeManager(event: TimeManagerRegisterEvent): Unit = {
    poolTimeManager = event.actorRef
    startLoadData()
  }

  private def startLoadData(): Unit =
    if (poolTimeManager != null && reporters != null) {
      loadManager = createSingletonLoadManager()
      createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! LoadDataEvent(
        actorRef = selfProxy,
        actorsDataSources = configuration.actorsDataSources
      )
    }

  private def prepareSimulation(event: PrepareSimulationEvent): Unit = {
    configuration = loadSimulationConfig(event.configuration)
    logInfo(s"Run simulation: \n$configuration")
    timeManager = createSingletonTimeManager()
    reportManager = createSingletonReportManager()
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

  private def createSingletonReportManager(): ActorRef =
    createSingletonManager(
      manager = ReportManager.props(
        simulationManager = getSelfProxy,
        timeManager = createSingletonProxy(LOAD_MANAGER_ACTOR_NAME)
      ),
      name = REPORT_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonLoadManager(): ActorRef =
    createSingletonManager(
      manager = LoadDataManager.props(
        timeManager = poolTimeManager,
        poolTimeManager = poolTimeManager,
        simulationManager = selfProxy,
        poolReporters = reporters
      ),
      name = LOAD_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )
}
