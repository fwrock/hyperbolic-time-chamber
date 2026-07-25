package org.interscity.htc.model.hybrid.collections

import com.fasterxml.jackson.databind.{ DeserializationFeature, JavaType, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.interscity.htc.model.hybrid.collections.graph.{ Edge, EdgeInfo }
import org.interscity.htc.model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }

import java.io.{ BufferedInputStream, File, FileInputStream, InputStream }
import java.sql.DriverManager
import scala.reflect.ClassTag
import scala.collection.immutable.{ Map, Queue, Set }
import scala.collection.mutable
import scala.util.{ Try, Using }
import scala.annotation.tailrec
import scala.math.Numeric

/** Estrutura para uma aresta no JSON, usando IDs de referência. */
private case class JsonEdgeRefFormat[ID, W, L](
  source_id: ID,
  target_id: ID,
  weight: Option[W],
  label: L
)

/** Estrutura que representa o formato JSON completo com referências. */
private case class JsonGraphRefFormat[V, ID, W, L](
  nodes: List[V],
  edges: List[JsonEdgeRefFormat[ID, W, L]],
  directed: Boolean
)

/** Estrutura para encapsular o grafo carregado e mapas de consulta rápida.
  * @param graph
  *   O objeto Graph.
  * @param nodesById
  *   Mapa de ID do nó para o objeto nó (V).
  * @param edgeLabelsById
  *   Mapa de ID do label da aresta para o objeto label (L).
  * @tparam V
  *   Tipo do vértice (NodeGraph).
  * @tparam ID
  *   Tipo do identificador usado como chave nos mapas (String).
  * @tparam W
  *   Tipo do peso da aresta (Double).
  * @tparam L
  *   Tipo do label da aresta (EdgeGraph).
  */
case class LoadedGraphData[V, ID, W, L](
  graph: Graph[V, W, L],
  nodesById: Map[ID, V],
  edgeLabelsById: Map[ID, L]
)

/** Classe principal do Grafo.
  * @param adjacencyList
  *   Mapa: Vértice -> (Vizinho -> EdgeInfo(Peso, Label))
  * @tparam V
  *   Tipo do identificador do vértice (NodeGraph).
  * @tparam W
  *   Tipo do peso da aresta (Double).
  * @tparam L
  *   Tipo do objeto "label" da aresta (EdgeGraph).
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
    adjacencyList.flatMap {
      case (source, neighborsMap) =>
        neighborsMap.map {
          case (target, info) =>
            Edge(source, target, info.weight, info.label)
        }
    }.toSet

  def contains(vertex: V): Boolean = adjacencyList.contains(vertex)

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

  /** A* que retorna o caminho como uma lista de tuplas (Aresta Completa, Nó Destino da Aresta no
    * Caminho).
    */
  def aStarEdgeTargets(startNode: V, goalNode: V, heuristic: (V, V) => Double)(implicit
    num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)
    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]() // Nó -> Predecessor
    val openSet =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    gScore(startNode) = 0.0
    fScore(startNode) = heuristic(startNode, goalNode)
    openSet.enqueue((fScore(startNode), startNode))

    var foundGoalScore: Option[Double] = None

    while (openSet.nonEmpty && foundGoalScore.isEmpty) {
      val (_, current) = openSet.dequeue()
      if (current == goalNode) {
        foundGoalScore = Some(gScore(goalNode))
      } else if (gScore(current) < Double.PositiveInfinity) {
        neighbors(current).foreach {
          case (neighbor, edgeInfoObj) =>
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
    foundGoalScore.flatMap {
      cost =>
        reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(
          path => (cost, path)
        )
    }
  }

  def aStarEdgeTargetsOptimized(
    startNode: V,
    goalNode: V,
    heuristic: (V, V) => Double,
    maxExpansions: Int = Int.MaxValue
  )(implicit
    num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)

    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]()
    val openSet =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
    val closedSet = mutable.Set[V]() // Conjunto fechado

    gScore(startNode) = 0.0
    fScore(startNode) = heuristic(startNode, goalNode)
    openSet.enqueue((fScore(startNode), startNode))

    var expansions = 0
    while (openSet.nonEmpty) {
      val (currentFScore, current) = openSet.dequeue() // Pega fScore real da fila

      if (currentFScore > fScore(current)) {
        // Continue para a próxima iteração (em Scala, isso pode ser feito não fazendo nada aqui
        // e deixando o loop continuar, ou usando uma estrutura de loop que suporte `continue`)
        // Para este while, podemos apenas deixar o if não ter um else e o loop continua.
      } else if (current == goalNode) {
        // Objetivo alcançado!
        return reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(
          path => (gScore(goalNode), path)
        )
      } else {
        if (!closedSet.contains(current)) {
          closedSet.add(current)
          expansions += 1
          if (expansions >= maxExpansions) return None

          neighbors(current).foreach {
            case (neighbor, edgeInfoObj) =>
              val tentativeGScore = gScore(current) + weightToDouble(edgeInfoObj.weight)
              if (tentativeGScore < gScore(neighbor)) {
                cameFrom(neighbor) = current
                gScore(neighbor) = tentativeGScore
                val newFScoreForNeighbor = tentativeGScore + heuristic(neighbor, goalNode)
                fScore(neighbor) = newFScoreForNeighbor // Atualiza o fScore conhecido
                openSet.enqueue((newFScoreForNeighbor, neighbor))
              }
          }
        }
      }
    }
    None
  }

  /** Dijkstra que retorna o caminho como uma lista de tuplas (Aresta Completa, Nó Destino da Aresta
    * no Caminho).
    */
  def dijkstraEdgeTargets(startNode: V, goalNode: V)(implicit
    num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)

    val distances = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]()
    val priorityQueue =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
    val visited = mutable.Set[V]()

    distances(startNode) = 0.0
    priorityQueue.enqueue((0.0, startNode))

    while (priorityQueue.nonEmpty) {
      val (currentDistance, current) = priorityQueue.dequeue()

      if (!visited.contains(current)) {
        visited.add(current)

        if (current == goalNode) {
          return reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(
            path => (distances(goalNode), path)
          )
        }

        neighbors(current).foreach {
          case (neighbor, edgeInfoObj) =>
            if (!visited.contains(neighbor)) {
              val newDistance = currentDistance + weightToDouble(edgeInfoObj.weight)
              if (newDistance < distances(neighbor)) {
                distances(neighbor) = newDistance
                cameFrom(neighbor) = current
                priorityQueue.enqueue((newDistance, neighbor))
              }
            }
        }
      }
    }

    None
  }

  /** Dijkstra otimizado para encontrar o caminho mais curto entre dois nós. */
  def dijkstraEdgeTargetsOptimized(startNode: V, goalNode: V)(implicit
    num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)

    val distances = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]()
    val priorityQueue =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
    val visited = mutable.Set[V]()

    distances(startNode) = 0.0
    priorityQueue.enqueue((0.0, startNode))

    while (priorityQueue.nonEmpty) {
      val (currentDistance, current) = priorityQueue.dequeue()

      if (currentDistance > distances(current)) {
        // Continue para próxima iteração
      } else if (current == goalNode) {
        return reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(
          path => (distances(goalNode), path)
        )
      } else if (!visited.contains(current)) {
        visited.add(current)

        neighbors(current).foreach {
          case (neighbor, edgeInfoObj) =>
            if (!visited.contains(neighbor)) {
              val newDistance = currentDistance + weightToDouble(edgeInfoObj.weight)
              if (newDistance < distances(neighbor)) {
                distances(neighbor) = newDistance
                cameFrom(neighbor) = current
                priorityQueue.enqueue((newDistance, neighbor))
              }
            }
        }
      }
    }

    None
  }

  /** Encontra o caminho com menor número de arestas/links entre dois nós usando BFS. Similar à
    * função digraph:get_short_path/3 do Erlang. Retorna o número de hops e o caminho como lista de
    * tuplas (Aresta, Nó destino).
    */
  def shortestPathByHops(startNode: V, goalNode: V): Option[(Int, List[(Edge[V, W, L], V)])] = {
    if (!contains(startNode) || !contains(goalNode)) return None
    if (startNode == goalNode) return Some((0, List.empty))

    val queue = mutable.Queue[(V, Int)]()
    val visited = mutable.Set[V]()
    val cameFrom = mutable.Map[V, V]()

    queue.enqueue((startNode, 0))
    visited.add(startNode)

    while (queue.nonEmpty) {
      val (current, hops) = queue.dequeue()

      neighbors(current).foreach {
        case (neighbor, _) =>
          if (!visited.contains(neighbor)) {
            visited.add(neighbor)
            cameFrom(neighbor) = current

            if (neighbor == goalNode) {
              return reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode)
                .map(
                  path => (hops + 1, path)
                )
            }

            queue.enqueue((neighbor, hops + 1))
          }
      }
    }

    None
  }

  /** Dijkstra para encontrar as distâncias mais curtas de um nó para todos os outros. */
  def dijkstraAllDistances(startNode: V)(implicit
    num: Numeric[W]
  ): Map[V, Double] = {
    if (!contains(startNode)) return Map.empty
    val weightToDouble: W => Double = num.toDouble(_)

    val distances = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val priorityQueue =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
    val visited = mutable.Set[V]()

    distances(startNode) = 0.0
    priorityQueue.enqueue((0.0, startNode))

    while (priorityQueue.nonEmpty) {
      val (currentDistance, current) = priorityQueue.dequeue()

      if (!visited.contains(current) && currentDistance <= distances(current)) {
        visited.add(current)

        neighbors(current).foreach {
          case (neighbor, edgeInfoObj) =>
            if (!visited.contains(neighbor)) {
              val newDistance = currentDistance + weightToDouble(edgeInfoObj.weight)
              if (newDistance < distances(neighbor)) {
                distances(neighbor) = newDistance
                priorityQueue.enqueue((newDistance, neighbor))
              }
            }
        }
      }
    }

    distances.toMap
  }

  /** Returns a new graph with all edge directions reversed.
    *
    * Used by [[graph.LandmarkIndex]] to compute backward distances (distances ''to'' a landmark)
    * via a forward Dijkstra on the reversed graph.
    */
  def reversed: Graph[V, W, L] = {
    val newAdj = mutable.Map[V, mutable.Map[V, EdgeInfo[W, L]]]()
    adjacencyList.keys.foreach { v => newAdj.getOrElseUpdate(v, mutable.Map.empty) }
    adjacencyList.foreach {
      case (source, nbrs) =>
        nbrs.foreach {
          case (target, info) =>
            newAdj.getOrElseUpdate(target, mutable.Map.empty)(source) = info
        }
    }
    Graph(newAdj.view.mapValues(_.toMap).toMap)
  }

  private def reconstructEdgeTargetTuplePath(
    cameFrom: mutable.Map[V, V],
    startNode: V,
    endNode: V
  ): Option[List[(Edge[V, W, L], V)]] = {
    if (startNode == endNode) return Some(List.empty)
    @tailrec
    def loop(curr: V, acc: List[(Edge[V, W, L], V)]): Option[List[(Edge[V, W, L], V)]] =
      cameFrom.get(curr) match {
        case Some(prev) =>
          edgeInfo(prev, curr) match {
            case Some(info) =>
              val edge = Edge(prev, curr, info.weight, info.label)
              val tuple = (edge, curr)
              if (prev == startNode) Some(tuple :: acc)
              else loop(prev, tuple :: acc)
            case None => None
          }
        case None => if (curr == startNode) Some(acc) else None // Chegou ao início ou erro
      }
    loop(endNode, Nil)
  }

  /** Builds a [[ContractionHierarchiesIndex]] for this graph.
    *
    * Preprocessing contracts nodes in order of their *edge-difference* importance (|shortcuts| −
    * \|incoming + outgoing edges|). Shortcuts are added to preserve shortest-path distances through
    * the contracted node. The resulting index supports extremely fast bidirectional Dijkstra
    * queries via [[ContractionHierarchiesIndex.query]].
    *
    * Complexity: O(n · k²) worst case, where k is the average degree. In practice road-network
    * graphs have small k, so preprocessing is fast.
    *
    * @return
    *   A pre-computed [[ContractionHierarchiesIndex]] ready for queries.
    */
  def buildContractionHierarchies(implicit
    num: Numeric[W]
  ): graph.ContractionHierarchiesIndex[V, W, L] = {
    val weightToDouble: W => Double = num.toDouble(_)

    val fwd = mutable.Map[V, mutable.Map[V, Double]]()
    val bwd = mutable.Map[V, mutable.Map[V, Double]]()

    val origLabels = mutable.Map[(V, V), (Double, L)]()

    adjacencyList.foreach {
      case (u, nbrs) =>
        val fm = fwd.getOrElseUpdate(u, mutable.Map.empty)
        nbrs.foreach {
          case (v, info) =>
            val w = weightToDouble(info.weight)
            fm(v) = w
            bwd.getOrElseUpdate(v, mutable.Map.empty)(u) = w
            origLabels((u, v)) = (w, info.label)
        }
    }

    vertices.foreach {
      v =>
        fwd.getOrElseUpdate(v, mutable.Map.empty)
        bwd.getOrElseUpdate(v, mutable.Map.empty)
    }

    val shortcuts = mutable.Map[(V, V), V]()

    val rank = mutable.Map[V, Int]()

    val remaining = mutable.Set[V]() ++= vertices

    /** Witness search: find shortest path from `src` to `tgt` in the graph, but *skipping* node
      * `skip`, up to distance `maxDist`. Uses a small Dijkstra limited to `maxSettled` settlements
      * for speed.
      */
    def witnessExists(src: V, tgt: V, skip: V, maxDist: Double, maxSettled: Int): Boolean = {
      val dist = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
      val pq = mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
      dist(src) = 0.0
      pq.enqueue((0.0, src))
      var settled = 0
      while (pq.nonEmpty && settled < maxSettled) {
        val (d, u) = pq.dequeue()
        if (d <= dist(u)) {
          if (u == tgt && d <= maxDist) return true
          if (u != skip) {
            settled += 1
            fwd.getOrElse(u, mutable.Map.empty).foreach {
              case (nb, w) =>
                val nd = d + w
                if (nd < dist(nb)) {
                  dist(nb) = nd; pq.enqueue((nd, nb))
                }
            }
          }
        }
      }
      dist(tgt) <= maxDist
    }

    /** Compute edge-difference for `v` (heuristic node importance). */
    def edgeDifference(v: V): Int = {
      val preds = bwd.getOrElse(v, mutable.Map.empty).keys.filter(remaining.contains).toSeq
      val succs = fwd.getOrElse(v, mutable.Map.empty).keys.filter(remaining.contains).toSeq
      var shortcutCount = 0
      preds.foreach {
        u =>
          val duv = fwd(u)(v)
          succs.foreach {
            w =>
              if (u != w) {
                val maxDist = duv + fwd(v)(w)
                if (!witnessExists(u, w, v, maxDist, 200))
                  shortcutCount += 1
              }
          }
      }
      shortcutCount - (preds.size + succs.size)
    }

    val importancePQ =
      mutable.PriorityQueue[(Int, V)]()(Ordering.by[(Int, V), Int](_._1).reverse)
    vertices.foreach {
      v => importancePQ.enqueue((edgeDifference(v), v))
    }

    var orderIdx = 0

    while (remaining.nonEmpty && importancePQ.nonEmpty) {
      var contracted = false
      while (!contracted && importancePQ.nonEmpty) {
        val (_, v) = importancePQ.dequeue()
        if (!remaining.contains(v)) {} else {
          val currentImportance = edgeDifference(v)
          if (importancePQ.nonEmpty && currentImportance > importancePQ.head._1) {
            importancePQ.enqueue((currentImportance, v))
          } else {
            rank(v) = orderIdx
            orderIdx += 1
            remaining.remove(v)

            val preds = bwd.getOrElse(v, mutable.Map.empty).keys.filter(remaining.contains).toSeq
            val succs = fwd.getOrElse(v, mutable.Map.empty).keys.filter(remaining.contains).toSeq

            preds.foreach {
              u =>
                val duv = fwd(u)(v)
                succs.foreach {
                  w =>
                    if (u != w) {
                      val maxDist = duv + fwd(v)(w)
                      if (!witnessExists(u, w, v, maxDist, 200)) {
                        val scWeight = duv + fwd(v)(w)
                        if (
                          scWeight < fwd
                            .getOrElse(u, mutable.Map.empty)
                            .getOrElse(w, Double.PositiveInfinity)
                        ) {
                          fwd.getOrElseUpdate(u, mutable.Map.empty)(w) = scWeight
                          bwd.getOrElseUpdate(w, mutable.Map.empty)(u) = scWeight
                          shortcuts((u, w)) = v
                        }
                      }
                    }
                }
            }
            contracted = true
          }
        }
      }
    }

    remaining.foreach {
      v =>
        rank(v) = orderIdx; orderIdx += 1
    }

    val upward = mutable.Map[V, mutable.Map[V, (Double, Option[L])]]()
    val downward = mutable.Map[V, mutable.Map[V, (Double, Option[L])]]()

    vertices.foreach {
      v =>
        upward.getOrElseUpdate(v, mutable.Map.empty)
        downward.getOrElseUpdate(v, mutable.Map.empty)
    }

    fwd.foreach {
      case (u, nbrs) =>
        val rankU = rank.getOrElse(u, 0)
        nbrs.foreach {
          case (v, w) =>
            val rankV = rank.getOrElse(v, 0)
            val label: Option[L] = origLabels.get((u, v)).map(_._2)
            if (rankV > rankU) {
              upward(u)(v) = (w, label)
              downward(v)(u) = (w, label)
            }
        }
    }

    graph.ContractionHierarchiesIndex(
      upward.view.mapValues(_.toMap).toMap,
      downward.view.mapValues(_.toMap).toMap,
      shortcuts.toMap,
      rank.toMap,
      origLabels.view.mapValues(identity).toMap
    )
  }
}

object Graph {
  def empty[V, W, L]: Graph[V, W, L] = Graph(Map.empty[V, Map[V, EdgeInfo[W, L]]])

  private object JacksonConfig {
    val mapper: ObjectMapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    def buildGraphRefFormatType[V, ID, W, L]()(implicit
      ctV: ClassTag[V],
      ctID: ClassTag[ID],
      ctW: ClassTag[W],
      ctL: ClassTag[L]
    ): JavaType = {
      val typeFactory = mapper.getTypeFactory
      typeFactory.constructParametricType(
        classOf[JsonGraphRefFormat[_, _, _, _]],
        ctV.runtimeClass,
        ctID.runtimeClass,
        ctW.runtimeClass,
        ctL.runtimeClass
      )
    }
  }

  /** Carrega um grafo de um arquivo JSON.
    * @param filePath
    *   Caminho para o arquivo JSON.
    * @param nodeIdExtractor
    *   Função para extrair o ID de um objeto nó V.
    * @param edgeLabelIdExtractor
    *   Função para extrair o ID de um objeto label de aresta L.
    * @param defaultWeightForUnweighted
    *   Peso padrão se não especificado na aresta.
    * @return
    *   Try[LoadedGraphData] contendo o grafo e mapas de consulta.
    */
  def loadFromJsonFile[V, ID, W, L](
    filePath: String,
    nodeIdExtractor: V => ID,
    edgeLabelIdExtractor: L => ID,
    defaultWeightForUnweighted: W
  )(implicit
    vCtFile: ClassTag[V],
    idCtFile: ClassTag[ID],
    wCtFile: ClassTag[W],
    lCtFile: ClassTag[L]
  ): Try[LoadedGraphData[V, ID, W, L]] =
    Using(new BufferedInputStream(new FileInputStream(new File(filePath)))) {
      inputStream =>
        loadFromJson[V, ID, W, L](
          inputStream,
          nodeIdExtractor,
          edgeLabelIdExtractor,
          defaultWeightForUnweighted
        )(vCtFile, idCtFile, wCtFile, lCtFile)
    }.flatten

  /** Carrega um grafo de um InputStream JSON.
    */
  def loadFromJson[V, ID, W, L](
    jsonStream: InputStream,
    nodeIdExtractor: V => ID,
    edgeLabelIdExtractor: L => ID,
    defaultWeightForUnweighted: W
  )(implicit
    vCt: ClassTag[V],
    idCt: ClassTag[ID],
    wCt: ClassTag[W],
    lCt: ClassTag[L]
  ): Try[LoadedGraphData[V, ID, W, L]] =
    Try {
      val graphFormatType: JavaType =
        JacksonConfig.buildGraphRefFormatType[V, ID, W, L]()(vCt, idCt, wCt, lCt)

      val jsonGraph: JsonGraphRefFormat[V, ID, W, L] = JacksonConfig.mapper
        .readValue(jsonStream, graphFormatType)
        .asInstanceOf[JsonGraphRefFormat[V, ID, W, L]]

      val nodeMapBuilder = Map.newBuilder[ID, V]
      val seenNodeIds = mutable.Set[ID]()
      jsonGraph.nodes.foreach {
        node =>
          val nodeId = nodeIdExtractor(node)
          if (seenNodeIds.contains(nodeId)) {
            throw new IllegalArgumentException(s"ID de nó duplicado no JSON: $nodeId")
          }
          seenNodeIds.add(nodeId)
          nodeMapBuilder += (nodeId -> node)
      }
      val nodesByIdMap: Map[ID, V] = nodeMapBuilder.result()

      var graph = Graph.empty[V, W, L]
      val edgeLabelMapBuilder = Map.newBuilder[ID, L]
      val seenEdgeLabelIds = mutable.Set[ID]()

      jsonGraph.edges.foreach {
        jsonEdge =>
          val sourceNodeOpt = nodesByIdMap.get(jsonEdge.source_id)
          val targetNodeOpt = nodesByIdMap.get(jsonEdge.target_id)

          (sourceNodeOpt, targetNodeOpt) match {
            case (Some(sourceNode), Some(targetNode)) =>
              val weight = jsonEdge.weight.getOrElse(defaultWeightForUnweighted)
              val edgeLabelObject: L = jsonEdge.label

              if (edgeLabelObject == null) {
                System.err.println(
                  s"AVISO: Aresta ${jsonEdge.source_id} -> ${jsonEdge.target_id} ignorada: label é null no JSON."
                )
              } else {

                val currentEdgeLabelId = edgeLabelIdExtractor(edgeLabelObject)
                if (!seenEdgeLabelIds.contains(currentEdgeLabelId)) {
                  edgeLabelMapBuilder += (currentEdgeLabelId -> edgeLabelObject)
                  seenEdgeLabelIds.add(currentEdgeLabelId)
                } else {
                  val existingLabel = edgeLabelMapBuilder.result().get(currentEdgeLabelId)
                  if (existingLabel.isDefined && existingLabel.get != edgeLabelObject) {
                    System.err.println(
                      s"AVISO: ID de label de aresta '$currentEdgeLabelId' duplicado com objetos diferentes no JSON. Usando o primeiro encontrado."
                    )
                  }
                }

                if (jsonGraph.directed) {
                  graph = graph.addEdge(sourceNode, targetNode, weight, edgeLabelObject)
                } else {
                  graph = graph.addUndirectedEdge(sourceNode, targetNode, weight, edgeLabelObject)
                }
              }
            case (None, _) =>
              throw new NoSuchElementException(
                s"Nó de origem com ID '${jsonEdge.source_id}' não encontrado."
              )
            case (_, None) =>
              throw new NoSuchElementException(
                s"Nó de destino com ID '${jsonEdge.target_id}' não encontrado."
              )
          }
      }
      LoadedGraphData(graph, nodesByIdMap, edgeLabelMapBuilder.result())
    }.recover {
      case e: com.fasterxml.jackson.core.JsonProcessingException =>
        throw new Exception(s"Erro no parsing do JSON (Jackson): ${e.getMessage}", e)
      case e @ (_: IllegalArgumentException | _: NoSuchElementException) =>
        throw e
      case e: Exception =>
        throw new Exception(s"Erro ao processar o JSON ou construir o grafo: ${e.getMessage}", e)
    }

  /** Carrega um grafo a partir de um `.db` SQLite gerado por
    * `tools/scenario-db-converter/convert.py` (tabelas `city_map_meta`/`city_map_node`/
    * `city_map_edge`), como alternativa a [[loadFromJsonFile]].
    *
    * Unlike `loadFromJsonFile`, this is NOT generic over `V`/`ID`/`W`/`L` — the `.db` schema is
    * concretely shaped around `NodeGraph`/`EdgeGraph` (the converter's schema mirrors those exact
    * case classes), so there is no equivalent of Jackson's reflective `JsonGraphRefFormat[V, ID,
    * W, L]` to build here. The one real call site (`CityMapUtil.loadedCityData`) only ever
    * instantiates the generic JSON loader with `[NodeGraph, String, Double, EdgeGraph]` anyway.
    *
    * Opened with `immutable=1`/`PRAGMA query_only=ON` for the same reason as
    * `SqliteLoadData`/`ProgressiveSqliteLoadData`: the `.db` is produced once by the converter and
    * never mutated afterward.
    *
    * @param dbPath
    *   path to the SQLite database.
    * @param defaultWeightForUnweighted
    *   peso padrão quando `weight` é NULL na tabela `city_map_edge`.
    * @return
    *   Try[LoadedGraphData] contendo o grafo e mapas de consulta — mesmo formato de retorno de
    *   [[loadFromJsonFile]].
    */
  def loadFromSqliteFile(
    dbPath: String,
    defaultWeightForUnweighted: Double = 0.0
  ): Try[LoadedGraphData[NodeGraph, String, Double, EdgeGraph]] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val conn = DriverManager.getConnection(s"jdbc:sqlite:file:$dbPath?immutable=1")
      try {
        val pragma = conn.createStatement()
        pragma.execute("PRAGMA query_only = ON")
        pragma.close()

        val directed = {
          val stmt = conn.createStatement()
          try {
            val rs = stmt.executeQuery("SELECT directed FROM city_map_meta")
            try {
              rs.next()
              rs.getInt("directed") != 0
            } finally rs.close()
          } finally stmt.close()
        }

        val nodeMapBuilder = Map.newBuilder[String, NodeGraph]
        val nodeStmt = conn.createStatement()
        try {
          val nodeRs = nodeStmt.executeQuery(
            "SELECT id, resource_id, class_type, latitude, longitude FROM city_map_node"
          )
          try
            while (nodeRs.next()) {
              val node = NodeGraph(
                id = nodeRs.getString("id"),
                resourceId = nodeRs.getString("resource_id"),
                classType = nodeRs.getString("class_type"),
                latitude = nodeRs.getDouble("latitude"),
                longitude = nodeRs.getDouble("longitude")
              )
              nodeMapBuilder += (node.id -> node)
            }
          finally nodeRs.close()
        } finally nodeStmt.close()
        val nodesByIdMap: Map[String, NodeGraph] = nodeMapBuilder.result()

        var graph = Graph.empty[NodeGraph, Double, EdgeGraph]
        val edgeLabelMapBuilder = Map.newBuilder[String, EdgeGraph]
        val seenEdgeLabelIds = mutable.Set[String]()

        val edgeStmt = conn.createStatement()
        try {
          val edgeRs = edgeStmt.executeQuery(
            "SELECT id, resource_id, class_type, length, source_id, target_id, weight FROM city_map_edge"
          )
          try
            while (edgeRs.next()) {
              val sourceId = edgeRs.getString("source_id")
              val targetId = edgeRs.getString("target_id")

              (nodesByIdMap.get(sourceId), nodesByIdMap.get(targetId)) match {
                case (Some(sourceNode), Some(targetNode)) =>
                  val label = EdgeGraph(
                    id = edgeRs.getString("id"),
                    resourceId = edgeRs.getString("resource_id"),
                    classType = edgeRs.getString("class_type"),
                    length = edgeRs.getDouble("length")
                  )

                  if (!seenEdgeLabelIds.contains(label.id)) {
                    edgeLabelMapBuilder += (label.id -> label)
                    seenEdgeLabelIds.add(label.id)
                  }

                  val weightObj = edgeRs.getObject("weight")
                  val weight = if (weightObj == null) defaultWeightForUnweighted else edgeRs.getDouble("weight")

                  graph =
                    if (directed) graph.addEdge(sourceNode, targetNode, weight, label)
                    else graph.addUndirectedEdge(sourceNode, targetNode, weight, label)

                case (None, _) =>
                  throw new NoSuchElementException(s"Nó de origem com ID '$sourceId' não encontrado.")
                case (_, None) =>
                  throw new NoSuchElementException(s"Nó de destino com ID '$targetId' não encontrado.")
              }
            }
          finally edgeRs.close()
        } finally edgeStmt.close()

        LoadedGraphData(graph, nodesByIdMap, edgeLabelMapBuilder.result())
      } finally conn.close()
    }.recover {
      case e @ (_: IllegalArgumentException | _: NoSuchElementException) =>
        throw e
      case e: Exception =>
        throw new Exception(s"Erro ao processar o SQLite ou construir o grafo: ${e.getMessage}", e)
    }
}
