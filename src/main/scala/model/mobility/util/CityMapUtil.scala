package org.interscity.htc.model.mobility.util

import org.interscity.htc.model.mobility.collections.{ Graph, LoadedGraphData }
// Importa NodeGraph e EdgeGraph do novo pacote em Model.scala
import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }
import scala.util.{ Failure, Success }

object CityMapUtil {

  // Extrator de ID para NodeGraph (V)
  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  // Extrator de ID para EdgeGraph (L - label da aresta)
  val edgeGraphIdExtractor: EdgeGraph => String = (edgeLabel: EdgeGraph) => edgeLabel.id

  // Carrega os dados do mapa da cidade uma vez, incluindo os mapas de consulta
  private val loadedCityData: LoadedGraphData[NodeGraph, String, Double, EdgeGraph] =
    Graph.loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
      // Obter o caminho do arquivo de uma variável de ambiente ou usar um padrão
      sys.env.get("HTC_MOBILITY_CITY_MAP_FILE").getOrElse("city_map.json"),
      nodeGraphIdExtractor,
      edgeGraphIdExtractor, // Passa o extrator de ID para EdgeGraph
      0.0 // Peso padrão para arestas sem peso especificado
    ) match {
      case Success(data) =>
        println("Mapa da cidade carregado com sucesso.")
        data
      case Failure(e) =>
        System.err.println(s"Falha crítica ao carregar o mapa da cidade: ${e.getMessage}")
        e.printStackTrace()
        // Em um sistema real, você pode querer parar a aplicação aqui
        throw new IllegalStateException("Não foi possível carregar os dados do mapa da cidade.", e)
    }

  // Expõe o grafo e os mapas de consulta para acesso rápido
  lazy val cityMap: Graph[NodeGraph, Double, EdgeGraph] = loadedCityData.graph
  lazy val nodesById: Map[String, NodeGraph] = loadedCityData.nodesById
  lazy val edgeLabelsById: Map[String, EdgeGraph] = loadedCityData.edgeLabelsById

  // Exemplo de como verificar se o mapa foi carregado (opcional)
  def printMapStats(): Unit = {
    println(s"Nós carregados: ${nodesById.size}")
    println(s"Labels de Arestas (EdgeGraphs) carregados: ${edgeLabelsById.size}")
    println(s"Total de arestas no grafo: ${cityMap.edges.size}")
    // Cuidado: cityMap.edges ainda reconstrói o conjunto, use com moderação fora da inicialização/debug.
  }
}
