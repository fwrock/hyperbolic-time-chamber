package org.interscity.htc
package model.mobility.collections

import org.interscity.htc.model.mobility.entity.state.model.NodeGraph

import scala.util.{ Failure, Success }

case class RoadInfo(roadType: String, name: Option[String], speedLimit: Option[Int])

object GraphUsageExample {

  // --- Criação Manual ---
  println("--- Grafo com Label nas Arestas (String, Double, RoadInfo) ---")

  var roadGraph = Graph.empty[String, Double, RoadInfo] // Especifica os 3 tipos

  val labelSP_RJ = RoadInfo("highway", Some("Presidente Dutra"), Some(110))
  val labelSP_CWB = RoadInfo("highway", Some("Régis Bittencourt"), Some(100))
  val labelRJ_BH = RoadInfo("highway", Some("BR-040"), Some(110))
  val pathLabel = RoadInfo("path", None, None) // Label para uma aresta sem peso

  roadGraph = roadGraph.addUndirectedEdge("São Paulo", "Rio de Janeiro", 450.0, labelSP_RJ)
  roadGraph = roadGraph.addUndirectedEdge("São Paulo", "Curitiba", 400.0, labelSP_CWB)
  roadGraph = roadGraph.addUndirectedEdge("Rio de Janeiro", "Belo Horizonte", 500.0, labelRJ_BH)
  roadGraph = roadGraph.addVertex("Brasília") // Vértice isolado

  println(s"Vértices: ${roadGraph.vertices}")
  println("\nInformações das Arestas:")
  roadGraph.edges.foreach {
    edge =>
      println(
        f"  De ${edge.source}%-15s para ${edge.target}%-15s Peso: ${edge.weight}%-5.1f Label: ${edge.label}"
      )
  }

  println(s"\nLabel da aresta SP -> RJ: ${roadGraph.label("São Paulo", "Rio de Janeiro")}")
  println(s"\nPeso da aresta SP -> CWB: ${roadGraph.weight("São Paulo", "Curitiba")}")
  println(s"\nInfo completa SP -> RJ: ${roadGraph.edgeInfo("São Paulo", "Rio de Janeiro")}")

  // Algoritmos ainda funcionam (usando apenas o peso)
  println("\nDijkstra a partir de 'São Paulo':")
  val shortestPaths = roadGraph.dijkstra("São Paulo") // Numeric[Double] é implícito
  shortestPaths.foreach {
    case (dest, (dist, pred)) =>
      println(f"  Para $dest: Distância $dist%.1f via ${pred.getOrElse("N/A")}")
  }

  // --- Carregamento via JSON ---
  println("\n--- Carregando Grafo com Label via JSON ---")

  val jsonWithRefs = """
  {
    "nodes": [
      {"id": "A", "shardId": "s1", "classType": "sensor", "latitude": 0.0, "longitude": 0.0},
      {"id": "B", "shardId": "s1", "classType": "actuator", "latitude": 1.0, "longitude": 5.0},
      {"id": "C", "shardId": "s2", "classType": "sensor", "latitude": 4.0, "longitude": 2.0},
      {"id": "D", "shardId": "s2", "classType": "storage", "latitude": 5.0, "longitude": 6.0}
    ],
    "edges": [
      {
        "source_id": "A",
        "target_id": "B",
        "weight": 6.0,
        "label": {"roadType": "local", "name": "Rua 1", "speedLimit": 50}
      },
      {
        "source_id": "A",
        "target_id": "C",
        "weight": 5.0,
        "label": {"roadType": "express", "name": "Via Rápida", "speedLimit": 80}
      },
      {
        "source_id": "B",
        "target_id": "D",
        "label": {"roadType": "local", "name": "Rua 2", "speedLimit": 50}
      },
      {
        "source_id": "C",
        "target_id": "D",
        "weight": 3.5,
        "label": {"roadType": "express", "name": "Av Principal", "speedLimit": 60}
      }
    ],
    "directed": false
  }
  """

  // Função para extrair o ID (String) de um NodeGraph
  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  // Chama loadFromJson com os tipos e a função extratora
  // V = NodeGraph, ID = String, W = Double, L = RoadInfo
  Graph.loadFromJson[NodeGraph, String, Double, RoadInfo](
    jsonWithRefs,
    nodeGraphIdExtractor, // Passa a função aqui
    0.0 // Default weight
  ) match {
    case Success(graph) =>
      println("Grafo com referências carregado com sucesso!")
      println(s"Vértices: ${graph.vertices}") // Deve mostrar objetos NodeGraph(...)
      println("Arestas:")
      graph.edges.foreach {
        edge =>
          // Note que source/target agora são os objetos NodeGraph completos
          println(
            f"  ${edge.source.id} -> ${edge.target.id} | W: ${edge.weight}%.1f | L: ${edge.label.roadType}"
          )
      }

      // Testar um algoritmo
      println("\nExecutando A* de A para D:")
      val heuristic: (NodeGraph, NodeGraph) => Double =
        (n1, n2) => n1.euclideanDistance(n2) // Supondo que euclideanDistance existe
      val startOpt = graph.vertices.find(_.id == "A")
      val goalOpt = graph.vertices.find(_.id == "D")

      (startOpt, goalOpt) match {
        case (Some(start), Some(goal)) =>
          graph.aStarEdges(start, goal, heuristic) match {
            case Some((cost, path)) =>
              println(f"  Caminho encontrado! Custo: $cost%.2f Arestas: ${path.size}")
            case None => println("  Caminho A* não encontrado.")
          }
        case _ => println("Nó inicial ou final não encontrado no grafo carregado.")
      }

    case Failure(e) =>
      println(s"Falha ao carregar grafo com referências do JSON: ${e.getMessage}")
      e.printStackTrace()
  }
}
