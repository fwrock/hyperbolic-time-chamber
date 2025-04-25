package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.GPSState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.interscsimulator.collections.Graph
import org.interscity.htc.model.interscsimulator.entity.state.model.{EdgeGraph, NodeGraph}

import scala.util.{Failure, Success}

class GPS(
  private var id: String = null,
  private val timeManager: ActorRef = null
) extends BaseActor[GPSState](
      actorId = id,
      timeManager = timeManager
    ) {

  override def onStart(): Unit = {
    Graph.loadFromJsonFile[NodeGraph, Double, EdgeGraph](state.cityMapPath, 0.0) match {
      case Success(graph) =>
        state.cityMap = graph
        logInfo("City map loaded successfully")
        logInfo(s"Nodes amount: ${state.cityMap.vertices.size}")
      case Failure(e) =>
        logError(s"Error on load and process city map from json file: ${e.getMessage}")
        e.printStackTrace()
    }
  }

}
