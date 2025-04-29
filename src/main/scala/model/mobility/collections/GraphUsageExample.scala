package org.interscity.htc
package model.mobility.collections

import org.interscity.htc.model.mobility.entity.state.model.{EdgeGraph, NodeGraph}

import scala.util.{Failure, Success}

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
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N1",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "0.0",
            "longitude": "0.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "100.0",
            "longitude": "0.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "200.0",
            "longitude": "0.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "0.0",
            "longitude": "100.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "100.0",
            "longitude": "100.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "200.0",
            "longitude": "100.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N7",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "0.0",
            "longitude": "200.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "100.0",
            "longitude": "200.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "200.0",
            "longitude": "200.0"
        },
        {
            "id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "classType": "org.interscity.htc.model.mobility.actor.Node",
            "shardId": "dtmi:org:interscity:model:mobility:resource:node;1",
            "latitude": "300.0",
            "longitude": "100.0"
        }
    ],
    "edges": [
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N1",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L1_2",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N1",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L2_1",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L2_3",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L3_2",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L4_5",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L5_4",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L5_6",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L6_5",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N7",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L7_8",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N7",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L8_7",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L8_9",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L9_8",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N1",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L1_4",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N1",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L4_1",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L2_5",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N2",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L5_2",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L3_6",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L6_3",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N7",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L4_7",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N7",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N4",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L7_4",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L5_8",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N8",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N5",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L8_5",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L6_9",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L9_6",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L6_10",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N6",
            "weight": 100.0,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L10_6",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 100.0
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "weight": 141.4,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L9_10",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 141.4
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N9",
            "weight": 141.4,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L10_9",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 141.4
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "weight": 141.4,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L3_10",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 141.4
            }
        },
        {
            "source_id": "dtmi:org:interscity:model:mobility:actor:node;N10",
            "target_id": "dtmi:org:interscity:model:mobility:actor:node;N3",
            "weight": 141.4,
            "label": {
                "id": "dtmi:org:interscity:model:mobility:actor:link;L10_3",
                "shardId": "dtmi:org:interscity:model:mobility:resource:link;1",
                "length": 141.4
            }
        }
    ],
    "directed": false
}
  """

  // Função para extrair o ID (String) de um NodeGraph
  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  // Chama loadFromJson com os tipos e a função extratora
  // V = NodeGraph, ID = String, W = Double, L = RoadInfo
  Graph.loadFromJson[NodeGraph, String, Double, EdgeGraph](
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
            f"  ${edge.source.id} -> ${edge.target.id} | W: ${edge.weight}%.1f | L: ${edge.label.id}"
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
