package org.interscity.htc.model.mobility.collections

import com.fasterxml.jackson.databind.{DeserializationFeature, JavaType, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.interscity.htc.model.mobility.collections.graph.{Edge, EdgeInfo}


import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import scala.reflect.ClassTag
import scala.collection.immutable.{Map, Queue, Set}
import scala.collection.mutable
import scala.util.{Try, Using}
import scala.annotation.tailrec
import scala.math.Numeric

/** Estrutura para uma aresta no JSON, usando IDs de referência. */
private case class JsonEdgeRefFormat[ID, W, L](
                                                source_id: ID, // Referência ao ID do nó fonte
                                                target_id: ID, // Referência ao ID do nó destino
                                                weight: Option[W],
                                                label: L // O objeto label completo (EdgeGraph no seu caso)
                                              )

/** Estrutura que representa o formato JSON completo com referências. */
private case class JsonGraphRefFormat[V, ID, W, L](
                                                    nodes: List[V], // Lista de nós completos
                                                    edges: List[JsonEdgeRefFormat[ID, W, L]], // Lista de arestas com labels completos
                                                    directed: Boolean
                                                  )

/**
 * Estrutura para encapsular o grafo carregado e mapas de consulta rápida.
 * @param graph O objeto Graph.
 * @param nodesById Mapa de ID do nó para o objeto nó (V).
 * @param edgeLabelsById Mapa de ID do label da aresta para o objeto label (L).
 * @tparam V Tipo do vértice (NodeGraph).
 * @tparam ID Tipo do identificador usado como chave nos mapas (String).
 * @tparam W Tipo do peso da aresta (Double).
 * @tparam L Tipo do label da aresta (EdgeGraph).
 */
case class LoadedGraphData[V, ID, W, L](
                                         graph: Graph[V, W, L],
                                         nodesById: Map[ID, V],
                                         edgeLabelsById: Map[ID, L]
                                       )

/** Classe principal do Grafo.
 * @param adjacencyList Mapa: Vértice -> (Vizinho -> EdgeInfo(Peso, Label))
 * @tparam V Tipo do identificador do vértice (NodeGraph).
 * @tparam W Tipo do peso da aresta (Double).
 * @tparam L Tipo do objeto "label" da aresta (EdgeGraph).
 */
case class Graph[V, W, L] private (
                                    private val adjacencyList: Map[V, Map[V, EdgeInfo[W, L]]]
                                  ) {

  // --- Operações Básicas ---
  val vertices: Set[V] = adjacencyList.keySet

  def addVertex(vertex: V): Graph[V, W, L] =
    if (adjacencyList.contains(vertex)) this
    else Graph(adjacencyList + (vertex -> Map.empty[V, EdgeInfo[W, L]]))

  def addEdge(source: V, target: V, weight: W, label: L): Graph[V, W, L] = {
    val graphWithVertices = addVertex(source).addVertex(target)
    val edgeInfo = EdgeInfo(weight, label)
    val sourceNeighbors = graphWithVertices.adjacencyList.getOrElse(source, Map.empty)
    val updatedNeighbors = sourceNeighbors + (target -> edgeInfo)
    Graph(graphWithVertices.adjacencyList + (source -> updatedNeighbors))
  }

  def addUndirectedEdge(v1: V, v2: V, weight: W, label: L): Graph[V, W, L] =
    addEdge(v1, v2, weight, label).addEdge(v2, v1, weight, label)

  def neighbors(vertex: V): Map[V, EdgeInfo[W, L]] =
    adjacencyList.getOrElse(vertex, Map.empty)

  def edgeInfo(source: V, target: V): Option[EdgeInfo[W, L]] =
    adjacencyList.get(source).flatMap(_.get(target))

  def weight(source: V, target: V): Option[W] = edgeInfo(source, target).map(_.weight)
  def label(source: V, target: V): Option[L] = edgeInfo(source, target).map(_.label)

  def edges: Set[Edge[V, W, L]] =
    adjacencyList.flatMap { case (source, neighborsMap) =>
      neighborsMap.map { case (target, info) =>
        Edge(source, target, info.weight, info.label)
      }
    }.toSet

  def contains(vertex: V): Boolean = adjacencyList.contains(vertex)

  // --- Algoritmos (BFS, DFS - sem alterações significativas na lógica central) ---
  def bfs(startNode: V): List[V] = {
    if (!contains(startNode)) return List.empty
    @tailrec
    def bfsRecursive(queue: Queue[V], visited: Set[V], result: List[V]): List[V] =
      queue.dequeueOption match {
        case None => result.reverse
        case Some((current, remainingQueue)) =>
          if (visited.contains(current)) {
            bfsRecursive(remainingQueue, visited, result)
          } else {
            val newVisited = visited + current
            val currentNeighbors = neighbors(current).keys.filterNot(newVisited.contains)
            val newQueue = remainingQueue.enqueueAll(currentNeighbors)
            bfsRecursive(newQueue, newVisited, current :: result)
          }
      }
    bfsRecursive(Queue(startNode), Set.empty, List.empty)
  }

  def dfs(startNode: V): List[V] = {
    if (!contains(startNode)) return List.empty
    @tailrec
    def dfsRecursive(stack: List[V], visited: Set[V], result: List[V]): List[V] =
      stack match {
        case Nil => result.reverse
        case current :: remainingStack =>
          if (visited.contains(current)) {
            dfsRecursive(remainingStack, visited, result)
          } else {
            val newVisited = visited + current
            val currentNeighbors = neighbors(current).keys.filterNot(newVisited.contains).toList
            dfsRecursive(currentNeighbors ::: remainingStack, newVisited, current :: result)
          }
      }
    dfsRecursive(List(startNode), Set.empty, List.empty)
  }

  // --- A* e Dijkstra (lógica interna permanece a mesma, mas os métodos de reconstrução são importantes) ---

  /** A* que retorna o caminho como uma lista de tuplas (Aresta Completa, Nó Destino da Aresta no Caminho). */
  def aStarEdgeTargets(startNode: V, goalNode: V, heuristic: (V, V) => Double)(implicit
                                                                               num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)
    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]() // Nó -> Predecessor
    val openSet = mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    gScore(startNode) = 0.0
    fScore(startNode) = heuristic(startNode, goalNode)
    openSet.enqueue((fScore(startNode), startNode))

    var foundGoalScore: Option[Double] = None

    while (openSet.nonEmpty && foundGoalScore.isEmpty) {
      val (_, current) = openSet.dequeue()
      if (current == goalNode) {
        foundGoalScore = Some(gScore(goalNode))
      } else if (gScore(current) < Double.PositiveInfinity) {
        neighbors(current).foreach { case (neighbor, edgeInfoObj) =>
          val tentativeGScore = gScore(current) + weightToDouble(edgeInfoObj.weight)
          if (tentativeGScore < gScore(neighbor)) {
            cameFrom(neighbor) = current
            gScore(neighbor) = tentativeGScore
            fScore(neighbor) = tentativeGScore + heuristic(neighbor, goalNode)
            openSet.enqueue((fScore(neighbor), neighbor))
          }
        }
      }
    }
    foundGoalScore.flatMap { cost =>
      reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(path => (cost, path))
    }
  }

  // --- Métodos de Reconstrução de Caminho ---
  private def reconstructEdgeTargetTuplePath(
                                              cameFrom: mutable.Map[V, V],
                                              startNode: V,
                                              endNode: V
                                            ): Option[List[(Edge[V, W, L], V)]] = {
    if (startNode == endNode) return Some(List.empty)
    @tailrec
    def loop(curr: V, acc: List[(Edge[V, W, L], V)]): Option[List[(Edge[V, W, L], V)]] = {
      cameFrom.get(curr) match {
        case Some(prev) =>
          edgeInfo(prev, curr) match {
            case Some(info) =>
              val edge = Edge(prev, curr, info.weight, info.label)
              val tuple = (edge, curr) // Aresta (prev->curr) e o nó destino 'curr'
              if (prev == startNode) Some(tuple :: acc)
              else loop(prev, tuple :: acc)
            case None => None // Inconsistência: aresta deveria existir
          }
        case None => if (curr == startNode) Some(acc) else None // Chegou ao início ou erro
      }
    }
    loop(endNode, Nil)
  }

  // Outros métodos de A* e Dijkstra e reconstrução podem ser mantidos ou adaptados similarmente.
  // Por brevidade, focarei no aStarEdgeTargets que é usado no GPSUtil.
}

object Graph {
  def empty[V, W, L]: Graph[V, W, L] = Graph(Map.empty[V, Map[V, EdgeInfo[W, L]]])

  private object JacksonConfig {
    val mapper: ObjectMapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    def buildGraphRefFormatType[V, ID, W, L]()(implicit
                                               ctV: ClassTag[V], ctID: ClassTag[ID], ctW: ClassTag[W], ctL: ClassTag[L]
    ): JavaType = {
      val typeFactory = mapper.getTypeFactory
      typeFactory.constructParametricType(
        classOf[JsonGraphRefFormat[_, _, _, _]],
        ctV.runtimeClass, ctID.runtimeClass, ctW.runtimeClass, ctL.runtimeClass
      )
    }
  }

  /**
   * Carrega um grafo de um arquivo JSON.
   * @param filePath Caminho para o arquivo JSON.
   * @param nodeIdExtractor Função para extrair o ID de um objeto nó V.
   * @param edgeLabelIdExtractor Função para extrair o ID de um objeto label de aresta L.
   * @param defaultWeightForUnweighted Peso padrão se não especificado na aresta.
   * @return Try[LoadedGraphData] contendo o grafo e mapas de consulta.
   */
  def loadFromJsonFile[V, ID, W, L](
                                     filePath: String,
                                     nodeIdExtractor: V => ID,
                                     edgeLabelIdExtractor: L => ID, // Extrator para ID do label da aresta
                                     defaultWeightForUnweighted: W
                                   )(implicit
                                     vCtFile: ClassTag[V], idCtFile: ClassTag[ID],
                                     wCtFile: ClassTag[W], lCtFile: ClassTag[L]
                                   ): Try[LoadedGraphData[V, ID, W, L]] =
    Using(new BufferedInputStream(new FileInputStream(new File(filePath)))) { inputStream =>
      loadFromJson[V, ID, W, L](
        inputStream,
        nodeIdExtractor,
        edgeLabelIdExtractor, // Passa o extrator
        defaultWeightForUnweighted
      )(vCtFile, idCtFile, wCtFile, lCtFile)
    }.flatten

  /**
   * Carrega um grafo de um InputStream JSON.
   */
  def loadFromJson[V, ID, W, L](
                                 jsonStream: InputStream,
                                 nodeIdExtractor: V => ID,
                                 edgeLabelIdExtractor: L => ID, // Extrator para ID do label da aresta
                                 defaultWeightForUnweighted: W
                               )(implicit
                                 vCt: ClassTag[V], idCt: ClassTag[ID],
                                 wCt: ClassTag[W], lCt: ClassTag[L]
                               ): Try[LoadedGraphData[V, ID, W, L]] =
    Try {
      val graphFormatType: JavaType =
        JacksonConfig.buildGraphRefFormatType[V, ID, W, L]()(vCt, idCt, wCt, lCt)

      val jsonGraph: JsonGraphRefFormat[V, ID, W, L] = JacksonConfig.mapper
        .readValue(jsonStream, graphFormatType)
        .asInstanceOf[JsonGraphRefFormat[V, ID, W, L]]

      // Constrói mapa de nós por ID
      val nodeMapBuilder = Map.newBuilder[ID, V]
      val seenNodeIds = mutable.Set[ID]()
      jsonGraph.nodes.foreach { node =>
        val nodeId = nodeIdExtractor(node)
        if (seenNodeIds.contains(nodeId)) {
          throw new IllegalArgumentException(s"ID de nó duplicado no JSON: $nodeId")
        }
        seenNodeIds.add(nodeId)
        nodeMapBuilder += (nodeId -> node)
      }
      val nodesByIdMap: Map[ID, V] = nodeMapBuilder.result()

      var graph = Graph.empty[V, W, L]
      val edgeLabelMapBuilder = Map.newBuilder[ID, L] // Mapa para labels de arestas
      val seenEdgeLabelIds = mutable.Set[ID]()

      jsonGraph.edges.foreach { jsonEdge =>
        val sourceNodeOpt = nodesByIdMap.get(jsonEdge.source_id)
        val targetNodeOpt = nodesByIdMap.get(jsonEdge.target_id)

        (sourceNodeOpt, targetNodeOpt) match {
          case (Some(sourceNode), Some(targetNode)) =>
            val weight = jsonEdge.weight.getOrElse(defaultWeightForUnweighted)
            val edgeLabelObject: L = jsonEdge.label // Este é o EdgeGraph completo

            // Adiciona o label da aresta ao mapa de consulta de labels
            val currentEdgeLabelId = edgeLabelIdExtractor(edgeLabelObject)
            if (!seenEdgeLabelIds.contains(currentEdgeLabelId)) {
              edgeLabelMapBuilder += (currentEdgeLabelId -> edgeLabelObject)
              seenEdgeLabelIds.add(currentEdgeLabelId)
            } else {
              // Opcional: verificar se o objeto label é o mesmo se o ID for repetido.
              // Se os IDs de EdgeGraph são únicos, não precisa se preocupar.
              val existingLabel = edgeLabelMapBuilder.result().get(currentEdgeLabelId)
              if (existingLabel.isDefined && existingLabel.get != edgeLabelObject) {
                // Poderia ser um aviso ou erro dependendo da sua lógica de dados
                System.err.println(s"AVISO: ID de label de aresta '$currentEdgeLabelId' duplicado com objetos diferentes no JSON. Usando o primeiro encontrado.")
              }
            }

            if (jsonGraph.directed) {
              graph = graph.addEdge(sourceNode, targetNode, weight, edgeLabelObject)
            } else {
              graph = graph.addUndirectedEdge(sourceNode, targetNode, weight, edgeLabelObject)
            }
          case (None, _) =>
            throw new NoSuchElementException(s"Nó de origem com ID '${jsonEdge.source_id}' não encontrado.")
          case (_, None) =>
            throw new NoSuchElementException(s"Nó de destino com ID '${jsonEdge.target_id}' não encontrado.")
        }
      }
      LoadedGraphData(graph, nodesByIdMap, edgeLabelMapBuilder.result())
    }.recover {
      case e: com.fasterxml.jackson.core.JsonProcessingException =>
        throw new Exception(s"Erro no parsing do JSON (Jackson): ${e.getMessage}", e)
      case e @ (_: IllegalArgumentException | _: NoSuchElementException) =>
        throw e // Re-lança exceções específicas
      case e: Exception =>
        throw new Exception(s"Erro ao processar o JSON ou construir o grafo: ${e.getMessage}", e)
    }
}
