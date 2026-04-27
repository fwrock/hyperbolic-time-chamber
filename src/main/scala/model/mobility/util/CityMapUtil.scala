package org.interscity.htc.model.mobility.util

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.model.mobility.collections.{ Graph, LoadedGraphData }
// Importa NodeGraph e EdgeGraph do novo pacote em Model.scala
import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }
import scala.util.{ Failure, Success }

object CityMapUtil {

  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  val edgeGraphIdExtractor: EdgeGraph => String = (edgeLabel: EdgeGraph) => edgeLabel.id

  private lazy val loadedCityData: LoadedGraphData[NodeGraph, String, Double, EdgeGraph] =
    Graph.loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
      SimulatorSettingsRegistry
        .get("htc.mobility.city-map-file")
        .orElse(sys.env.get("HTC_MOBILITY_CITY_MAP_FILE"))
        .getOrElse("city_map.json"),
      nodeGraphIdExtractor,
      edgeGraphIdExtractor,
      0.0
    ) match {
      case Success(data) =>
        println("Mapa da cidade carregado com sucesso.")
        data
      case Failure(e) =>
        System.err.println(s"Falha crítica ao carregar o mapa da cidade: ${e.getMessage}")
        e.printStackTrace()
        throw new IllegalStateException("Não foi possível carregar os dados do mapa da cidade.", e)
    }

  lazy val cityMap: Graph[NodeGraph, Double, EdgeGraph] = loadedCityData.graph
  lazy val nodesById: Map[String, NodeGraph] = loadedCityData.nodesById
  lazy val edgeLabelsById: Map[String, EdgeGraph] = loadedCityData.edgeLabelsById

  def printMapStats(): Unit = {
    println(s"Nós carregados: ${nodesById.size}")
    println(s"Labels de Arestas (EdgeGraphs) carregados: ${edgeLabelsById.size}")
    println(s"Total de arestas no grafo: ${cityMap.edges.size}")
  }
}
