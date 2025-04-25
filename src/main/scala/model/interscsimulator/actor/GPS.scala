package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.GPSState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.ActorSimulation
import org.interscity.htc.core.util.{ JsonUtil, SimulationUtil }

class GPS(
  private var id: String = null,
  private val timeManager: ActorRef = null
) extends BaseActor[GPSState](
      actorId = id,
      timeManager = timeManager
    ) {

  override def onStart(): Unit = {
    val simulation = SimulationUtil.loadSimulationConfig(state.simulationPath)

    simulation.actorsDataSources
      .filter(
        s => s.classType == classOf[Node].getName
      )
      .foreach {
        source =>
          val content = JsonUtil.readJsonFile(source.dataSource.info("path").asInstanceOf[String])

          var actors = JsonUtil.fromJsonList[ActorSimulation](content)
      }

  }

}
