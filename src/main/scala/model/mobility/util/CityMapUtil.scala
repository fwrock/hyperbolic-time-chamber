package org.interscity.htc
package model.mobility.util

import model.mobility.collections.Graph
import model.mobility.entity.state.model.{EdgeGraph, NodeGraph}

import scala.util.{Failure, Success}

object CityMapUtil {

  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  lazy val cityMap: Graph[NodeGraph, Double, EdgeGraph]  = {
    Graph.loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
      sys.env.get("HTC_MOBILITY_CITY_MAP_FILE").getOrElse("city_map.json"),
      nodeGraphIdExtractor,
      0.0
    ) match {
      case Success(graph) =>
        graph
      case Failure(e) =>
        null
    }
  }
}
