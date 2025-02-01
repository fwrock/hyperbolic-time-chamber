package org.interscity.htc
package core.actor.manager

import core.entity.event.control.load.{ FinishLoadDataEvent, LoadDataEvent }
import core.actor.BaseActor
import core.entity.state.DefaultState
import core.entity.event.control.execution.{ RunSimulationEvent, StartSimulationEvent }

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.SimulationUtil.loadSimulationConfig

import core.entity.configuration.Simulation

import scala.compiletime.uninitialized

class SimulationManager extends BaseActor[DefaultState](actorId = "simulation-manager") {

  private var timeManager: ActorRef = uninitialized
  private var loadManager: ActorRef = uninitialized
  private var configuration: Simulation = uninitialized

  override def handleEvent: Receive = {
    case event: RunSimulationEvent  => runSimulation(event)
    case event: FinishLoadDataEvent => startSimulation()
  }

  private def startSimulation(): Unit = {
    logEvent("Start simulation")
    timeManager ! StartSimulationEvent()
  }

  private def runSimulation(event: RunSimulationEvent): Unit = {
    logEvent("Run simulation")
    configuration = loadSimulationConfig(event.configuration)
    timeManager = context.system.actorOf(
      Props(
        new TimeManager(
          simulationDuration = configuration.duration,
          None
        )
      ),
      "time-manager"
    )
    loadManager = context.actorOf(
      Props(
        new LoadDataManager(
          timeManager = timeManager,
          simulationManager = self
        )
      ),
      "load-manager"
    )
    loadManager ! LoadDataEvent(
      actorRef = self,
      actorsDataSources = configuration.actorsDataSources
    )
  }
}
