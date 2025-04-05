package org.interscity.htc
package core.actor.manager

import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import core.util.SimulationUtil.loadSimulationConfig

import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, PrepareSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent, TimeManagerRegisterEvent }
import org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData
import org.htc.protobuf.core.entity.event.control.load.FinishLoadDataEvent
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.entity.event.control.load.LoadDataEvent

import scala.collection.mutable
import scala.compiletime.uninitialized

class SimulationManager(
  val simulationPath: String = null
) extends BaseManager[DefaultState](
      actorId = "simulation-manager",
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
    createSingletonProxy("global-time-manager", s"-${System.nanoTime()}") ! StartSimulationTimeEvent(
      startTick = configuration.startTick,
      actorRef = getPath,
      data = Some(StartSimulationTimeData(startTime = System.currentTimeMillis()))
    )
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = context.system.actorOf(
        ClusterSingletonProxy.props(
          singletonManagerPath = "/user/simulation-manager",
          settings = ClusterSingletonProxySettings(context.system)
        ),
        name = "simulation-manager-proxy"
      )
      selfProxy
    } else {
      selfProxy
    }

  private def registerPoolTimeManager(event: TimeManagerRegisterEvent): Unit = {
    poolTimeManager = getActorRef(event.actorRef)
    loadManager = createSingletonLoadManager()

    createSingletonProxy("load-manager") ! LoadDataEvent(
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
      name = "global-time-manager",
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonLoadManager(): ActorRef =
    createSingletonManager(
      manager = LoadDataManager.props(
        timeManager = poolTimeManager,
        poolTimeManager = poolTimeManager,
        simulationManager = selfProxy
      ),
      name = "load-manager",
      terminateMessage = StopSimulationEvent()
    )
}
