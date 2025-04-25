package org.interscity.htc
package model.interscsimulator.collections

import scala.util.{ Failure, Success }

case class RoadInfo(roadType: String, name: Option[String], speedLimit: Option[Int])

object GraphUsageExample extends App {

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

  val jsonWithLabels = """
  {
    "vertices": ["A", "B", "C", "D"],
    "edges": [
      {
        "source": "A", "target": "B", "weight": 10.5,
        "label": {"roadType": "local", "name": "Rua Principal", "speedLimit": 50}
      },
      {
        "source": "A", "target": "C", "weight": 5.2,
        "label": {"roadType": "path", "name": null, "speedLimit": null}
      },
      {
        "source": "B", "target": "D",
        "label": {"roadType": "service", "name": "Travessa B", "speedLimit": 30}
      },
      {
        "source": "C", "target": "D", "weight": 8.0,
        "label": {"roadType": "local", "name": "Av. Lateral", "speedLimit": 40}
      }
    ],
    "directed": false
  }
  """

  // ClassTags para String, Double, RoadInfo são necessários (implícitos aqui)
  Graph.loadFromJson[String, Double, RoadInfo](jsonWithLabels, 0.0) match { // 0.0 = default weight
    case Success(graph) =>
      println("Grafo com labels carregado com sucesso via JSON!")
      println(s"Vértices: ${graph.vertices}")
      graph.edges.foreach {
        edge =>
          println(
            f"  ${edge.source} -> ${edge.target} | W: ${edge.weight}%-5.1f | L: ${edge.label}"
          )
      }
      println(s"\nLabel A -> C: ${graph.label("A", "C")}")
      println(s"Peso B -> D: ${graph.weight("B", "D")}") // Deverá ser 0.0 (default)
    case Failure(e) =>
      println(s"Falha ao carregar grafo com labels do JSON: ${e.getMessage}")
      e.printStackTrace()
  }
}
