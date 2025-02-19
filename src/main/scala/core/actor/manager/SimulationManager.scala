package org.interscity.htc
package core.actor.manager

import core.entity.event.control.load.{ FinishLoadDataEvent, LoadDataEvent }
import core.entity.state.DefaultState
import core.entity.event.control.execution.{ PrepareSimulationEvent, StartSimulationEvent, StopSimulationEvent, TimeManagerRegisterEvent }

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.SimulationUtil.loadSimulationConfig
import core.entity.configuration.Simulation

import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }

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

  override def onStart(): Unit = {
    getSelfProxy ! PrepareSimulationEvent(
      configuration = simulationPath
    )
  }

  private def startSimulation(): Unit = {
    logEvent("Start simulation")
    timeManager ! StartSimulationEvent()
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
    poolTimeManager = event.actorRef
    loadManager = createSingletonLoadManager()

    createSingletonProxy("load-manager") ! LoadDataEvent(
      actorRef = self,
      actorsDataSources = configuration.actorsDataSources
    )
  }

  private def prepareSimulation(event: PrepareSimulationEvent): Unit = {
    logEvent("Run simulation")
    configuration = loadSimulationConfig(event.configuration)

    timeManager = createSingletonTimeManager()
  }

  private def createSingletonTimeManager(): ActorRef =
    createSingletonManager(
      manager = new TimeManager(
        simulationDuration = configuration.duration,
        simulationManager = getSelfProxy,
        parentManager = Some(poolTimeManager)
      ),
      name = "time-manager",
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonLoadManager(): ActorRef =
    createSingletonManager(
      manager = new LoadDataManager(
        timeManager = poolTimeManager,
        simulationManager = selfProxy
      ),
      name = "load-manager",
      terminateMessage = StopSimulationEvent()
    )
}
