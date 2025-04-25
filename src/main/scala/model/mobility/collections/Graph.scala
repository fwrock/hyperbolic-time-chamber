package org.interscity.htc
package model.mobility.collections

import org.interscity.htc.model.mobility.collections.graph.{ Edge, EdgeInfo }
import com.fasterxml.jackson.databind.{ DeserializationFeature, JavaType, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.interscity.htc.core.util.JsonUtil

import scala.reflect.ClassTag
import scala.collection.immutable.{ Map, Queue, Set }
import scala.collection.mutable
import scala.util.Try
import scala.annotation.tailrec
import scala.math.Numeric

/** Classe principal do Grafo, agora com tipo de label L para arestas.
  * @param adjacencyList
  *   Mapa: Vértice -> (Vizinho -> EdgeInfo(Peso, Label))
  * @tparam V
  *   Tipo do identificador do vértice.
  * @tparam W
  *   Tipo do peso da aresta.
  * @tparam L
  *   Tipo do objeto "label" da aresta.
  */
case class Graph[V, W, L] private (
  private val adjacencyList: Map[V, Map[V, EdgeInfo[W, L]]]
) {

  // --- Operações Básicas (Atualizadas) ---

  val vertices: Set[V] = adjacencyList.keySet

  def addVertex(vertex: V): Graph[V, W, L] =
    if (adjacencyList.contains(vertex)) {
      this
    } else {
      Graph(adjacencyList + (vertex -> Map.empty[V, EdgeInfo[W, L]]))
    }

  /** Adiciona/atualiza uma aresta direcionada com peso e label. */
  def addEdge(source: V, target: V, weight: W, label: L): Graph[V, W, L] = {
    val graphWithVertices = addVertex(source).addVertex(target)
    val edgeInfo = EdgeInfo(weight, label)
    val sourceNeighbors = graphWithVertices.adjacencyList.getOrElse(source, Map.empty)
    val updatedNeighbors = sourceNeighbors + (target -> edgeInfo)
    Graph(graphWithVertices.adjacencyList + (source -> updatedNeighbors))
  }

  /** Adiciona/atualiza uma aresta não direcionada com peso e label. */
  def addUndirectedEdge(v1: V, v2: V, weight: W, label: L): Graph[V, W, L] =
    // Adiciona em ambas as direções com o mesmo label
    addEdge(v1, v2, weight, label).addEdge(v2, v1, weight, label)

  /** Retorna os vizinhos e as informações completas das arestas (peso e label). */
  def neighbors(vertex: V): Map[V, EdgeInfo[W, L]] =
    adjacencyList.getOrElse(vertex, Map.empty)

  /** Retorna as informações completas da aresta (peso e label), se existir. */
  def edgeInfo(source: V, target: V): Option[EdgeInfo[W, L]] =
    adjacencyList.get(source).flatMap(_.get(target))

  /** Retorna o peso da aresta, se existir. */
  def weight(source: V, target: V): Option[W] =
    edgeInfo(source, target).map(_.weight)

  /** Retorna o label da aresta, se existir. */
  def label(source: V, target: V): Option[L] =
    edgeInfo(source, target).map(_.label)

  /** Retorna todas as arestas (como objetos Edge) do grafo. */
  def edges: Set[Edge[V, W, L]] =
    adjacencyList.flatMap {
      case (source, neighborsMap) =>
        neighborsMap.map {
          case (target, info) =>
            Edge(source, target, info.weight, info.label)
        }
    }.toSet

  def contains(vertex: V): Boolean = adjacencyList.contains(vertex)

  // --- Algoritmos (Atualizados para acessar EdgeInfo.weight) ---

  def bfs(startNode: V): List[V] = { // Não muda, pois usa apenas as chaves dos vizinhos
    if (!contains(startNode)) return List.empty
    // ... (lógica como antes, usando neighbors(current).keys) ...
    @tailrec
    def bfsRecursive(queue: Queue[V], visited: Set[V], result: List[V]): List[V] =
      queue.dequeueOption match {
        case None => result.reverse
        case Some((current, remainingQueue)) =>
          if (visited.contains(current)) {
            bfsRecursive(remainingQueue, visited, result)
          } else {
            val newVisited = visited + current
            // neighbors(current).keys não é afetado pela mudança no valor do Map
            val currentNeighbors = neighbors(current).keys.filterNot(newVisited.contains)
            val newQueue = remainingQueue.enqueueAll(currentNeighbors)
            bfsRecursive(newQueue, newVisited, current :: result)
          }
      }
    bfsRecursive(Queue(startNode), Set.empty, List.empty)
  }

  def dfs(startNode: V): List[V] = { // Não muda, pois usa apenas as chaves dos vizinhos
    if (!contains(startNode)) return List.empty
    // ... (lógica como antes, usando neighbors(current).keys) ...
    @tailrec
    def dfsRecursive(stack: List[V], visited: Set[V], result: List[V]): List[V] =
      stack match {
        case Nil => result.reverse
        case current :: remainingStack =>
          if (visited.contains(current)) {
            dfsRecursive(remainingStack, visited, result)
          } else {
            val newVisited = visited + current
            // neighbors(current).keys não é afetado pela mudança no valor do Map
            val currentNeighbors = neighbors(current).keys.filterNot(newVisited.contains).toList
            dfsRecursive(currentNeighbors ::: remainingStack, newVisited, current :: result)
          }
      }
    dfsRecursive(List(startNode), Set.empty, List.empty)
  }

  /** A* que retorna o caminho como uma lista de Arestas. Otimizado pelo uso de uma boa heurística
    * (fornecida pelo chamador).
    */
  def aStarEdges(startNode: V, goalNode: V, heuristic: (V, V) => Double)(implicit
    num: Numeric[W]
  ): Option[(Double, List[Edge[V, W, L]])] = {

    if (!contains(startNode) || !contains(goalNode)) return None

    val weightToDouble: W => Double = num.toDouble(_)
    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]() // Mapa para reconstrução (Nó -> Predecessor)
    val openSet =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    gScore(startNode) = 0.0
    fScore(startNode) = heuristic(startNode, goalNode)
    openSet.enqueue((fScore(startNode), startNode))

    var foundGoalScore: Option[Double] = None

    while (openSet.nonEmpty && foundGoalScore.isEmpty) {
      val (_, current) = openSet.dequeue()

      if (current == goalNode) {
        foundGoalScore = Some(gScore(goalNode)) // Armazena o custo ao encontrar
      } else if (gScore(current) < Double.PositiveInfinity) { // Evita explorar nós já "infinitos" se o goal foi removido
        neighbors(current).foreach {
          case (neighbor, edgeInfo) =>
            val tentativeGScore = gScore(current) + weightToDouble(edgeInfo.weight)
            if (tentativeGScore < gScore(neighbor)) {
              cameFrom(neighbor) = current
              gScore(neighbor) = tentativeGScore
              fScore(neighbor) = tentativeGScore + heuristic(neighbor, goalNode)
              openSet.enqueue((fScore(neighbor), neighbor))
            }
        }
      }
    }

    // Após o loop, se o goal foi encontrado, reconstrói o caminho e retorna com o custo
    foundGoalScore.flatMap {
      cost =>
        reconstructEdgePath(cameFrom, startNode, goalNode).map(
          path => (cost, path)
        )
    }
  }

  /** A* que retorna o caminho como uma lista de tuplas (Aresta, Nó Destino). */
  def aStarEdgeTargets(startNode: V, goalNode: V, heuristic: (V, V) => Double)(implicit
    num: Numeric[W]
  ): Option[(Double, List[(Edge[V, W, L], V)])] = {
    // Lógica do A* é idêntica à de aStarEdges até o final do loop while
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)
    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]()
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
          case (neighbor, edgeInfo) =>
            val tentativeGScore = gScore(current) + weightToDouble(edgeInfo.weight)
            if (tentativeGScore < gScore(neighbor)) {
              cameFrom(neighbor) = current
              gScore(neighbor) = tentativeGScore
              fScore(neighbor) = tentativeGScore + heuristic(neighbor, goalNode)
              openSet.enqueue((fScore(neighbor), neighbor))
            }
        }
      }
    }

    // Reconstrução diferente no final
    foundGoalScore.flatMap {
      cost =>
        reconstructEdgeTargetTuplePath(cameFrom, startNode, goalNode).map(
          path => (cost, path)
        )
    }
  }

  // Manter o A* original que retorna List[V] para compatibilidade ou simplicidade
  def aStar(startNode: V, goalNode: V, heuristic: (V, V) => Double)(implicit
    num: Numeric[W]
  ): Option[List[V]] = {
    // ... (lógica original do A*, usando mutable cameFrom) ...
    // No final, se encontrar o caminho:
    // return Some(reconstructNodePath(cameFrom, startNode, goalNode))
    // Se não: return None
    // Copiando a lógica central para claridade (idealmente refatorar para compartilhar mais):
    if (!contains(startNode) || !contains(goalNode)) return None
    val weightToDouble: W => Double = num.toDouble(_)
    val gScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val fScore = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val cameFrom = mutable.Map[V, V]()
    val openSet =
      mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    gScore(startNode) = 0.0
    fScore(startNode) = heuristic(startNode, goalNode)
    openSet.enqueue((fScore(startNode), startNode))

    var found = false
    while (openSet.nonEmpty && !found) {
      val (_, current) = openSet.dequeue()
      if (current == goalNode) {
        found = true
      } else if (gScore(current) < Double.PositiveInfinity) {
        neighbors(current).foreach {
          case (neighbor, edgeInfo) =>
            val tentativeGScore = gScore(current) + weightToDouble(edgeInfo.weight)
            if (tentativeGScore < gScore(neighbor)) {
              cameFrom(neighbor) = current
              gScore(neighbor) = tentativeGScore
              fScore(neighbor) = tentativeGScore + heuristic(neighbor, goalNode)
              openSet.enqueue((fScore(neighbor), neighbor))
            }
        }
      }
    }
    if (found) Some(reconstructNodePath(cameFrom, startNode, goalNode)) else None
  }

  // --- Dijkstra com Retornos Alternativos ---

  // Primeiro, refatorar o Dijkstra original para retornar o mapa de predecessores
  private def dijkstraCore(startNode: V)(implicit num: Numeric[W]): (Map[V, Double], Map[V, V]) = {
    if (!contains(startNode)) return (Map.empty, Map.empty)

    val weightToDouble: W => Double = num.toDouble(_)
    // Usar mapas imutáveis para o resultado, mas mutáveis para o processo interno
    val distances = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val predecessors = mutable.Map[V, V]()
    val pq = mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    distances(startNode) = 0.0
    pq.enqueue((0.0, startNode))

    while (pq.nonEmpty) {
      val (distU, u) = pq.dequeue()

      // Otimização: Se encontramos uma distância maior que a já registrada, pulamos (acontece com enqueue de duplicatas)
      if (distU <= distances(u)) {
        neighbors(u).foreach {
          case (v, edgeInfo) =>
            val altDistance = distances(u) + weightToDouble(edgeInfo.weight)
            if (altDistance < distances(v)) {
              distances(v) = altDistance
              predecessors(v) = u
              pq.enqueue((altDistance, v))
            }
        }
      }
    }
    (distances.toMap, predecessors.toMap) // Retorna como imutável
  }

  /** Dijkstra original - retorna mapa de (Distância, Predecessor Opcional). */
  def dijkstra(startNode: V)(implicit num: Numeric[W]): Map[V, (Double, Option[V])] = {
    val (distances, predecessors) = dijkstraCore(startNode)
    // Constrói o mapa de resultado no formato original
    vertices.map {
      v =>
        val dist = distances.getOrElse(v, Double.PositiveInfinity)
        val pred = predecessors.get(v)
        v -> (dist, pred)
    }.toMap
  }

  /** Dijkstra que retorna mapa de (Distância, Lista de Arestas Opcional). */
  def dijkstraEdges(
    startNode: V
  )(implicit num: Numeric[W]): Map[V, (Double, Option[List[Edge[V, W, L]]])] = {
    val (distances, predecessors) = dijkstraCore(startNode)
    // Para cada vértice alcançável, reconstrói o caminho de arestas
    vertices.map {
      v =>
        val dist = distances.getOrElse(v, Double.PositiveInfinity)
        val pathOpt =
          if (dist.isInfinity) None
          else reconstructEdgePathFromImmutable(predecessors, startNode, v)
        v -> (dist, pathOpt)
    }.toMap
  }

  /** Dijkstra que retorna mapa de (Distância, Lista de Tuplas (Aresta, Nó Destino) Opcional). */
  def dijkstraEdgeTargets(
    startNode: V
  )(implicit num: Numeric[W]): Map[V, (Double, Option[List[(Edge[V, W, L], V)]])] = {
    val (distances, predecessors) = dijkstraCore(startNode)
    // Para cada vértice alcançável, reconstrói o caminho de tuplas
    vertices.map {
      v =>
        val dist = distances.getOrElse(v, Double.PositiveInfinity)
        val pathOpt =
          if (dist.isInfinity) None
          else reconstructEdgeTargetTuplePathFromImmutable(predecessors, startNode, v)
        v -> (dist, pathOpt)
    }.toMap
  }

  // reconstructPath (como antes)
  private def reconstructPath(cameFrom: mutable.Map[V, V], current: V): List[V] = {
    @tailrec
    def loop(node: V, acc: List[V]): List[V] =
      cameFrom.get(node) match {
        case Some(prev) => loop(prev, node :: acc)
        case None       => node :: acc
      }
    loop(current, Nil)
  }

  /** Reconstrói o caminho como uma lista de nós (V). */
  private def reconstructNodePath(
    cameFrom: mutable.Map[V, V],
    startNode: V,
    endNode: V
  ): List[V] = {
    @tailrec
    def loop(curr: V, acc: List[V]): List[V] =
      if (curr == startNode) curr :: acc
      else
        cameFrom.get(curr) match {
          case Some(prev) => loop(prev, curr :: acc)
          case None       => curr :: acc // Should only happen if endNode == startNode
        }

    if (startNode == endNode) List(startNode)
    else loop(endNode, Nil)
  }

  /** Reconstrói o caminho como uma lista de arestas (Edge). */
  private def reconstructEdgePath(
    cameFrom: mutable.Map[V, V],
    startNode: V,
    endNode: V
  ): Option[List[Edge[V, W, L]]] = {
    if (startNode == endNode) return Some(List.empty) // Sem arestas para caminho de nó único

    @tailrec
    def loop(curr: V, acc: List[Edge[V, W, L]]): Option[List[Edge[V, W, L]]] =
      cameFrom.get(curr) match {
        case Some(prev) =>
          // Busca a informação da aresta (prev -> curr)
          edgeInfo(prev, curr) match {
            case Some(info) =>
              val edge = Edge(prev, curr, info.weight, info.label)
              if (prev == startNode) Some(edge :: acc) // Chegou ao início
              else loop(prev, edge :: acc)
            case None => None // Erro: Aresta não encontrada no mapa cameFrom, inconsistência?
          }
        case None => if (curr == startNode) Some(acc) else None // Chegou ao início ou erro
      }
    // Inicia a reconstrução a partir do nó final
    loop(endNode, Nil)
  }

  /** Reconstrói o caminho como lista de tuplas (Edge, TargetNode). */
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
              val tuple = (edge, curr) // Aresta e o nó *destino* dessa aresta no caminho
              if (prev == startNode) Some(tuple :: acc)
              else loop(prev, tuple :: acc)
            case None => None // Erro
          }
        case None => if (curr == startNode) Some(acc) else None // Erro
      }

    loop(endNode, Nil)
  }

  // Adaptação da função original (que usa mutable Map) para usar Map imutável (passado dos novos algoritmos)
  private def reconstructNodePathFromImmutable(
    cameFrom: Map[V, V],
    startNode: V,
    endNode: V
  ): List[V] =
    reconstructNodePath(
      mutable.Map.newBuilder(cameFrom).result(),
      startNode,
      endNode
    ) // Converte para Map padrão para reutilizar

  private def reconstructEdgePathFromImmutable(
    cameFrom: Map[V, V],
    startNode: V,
    endNode: V
  ): Option[List[Edge[V, W, L]]] =
    reconstructEdgePath(mutable.Map.newBuilder(cameFrom).result(), startNode, endNode)

  private def reconstructEdgeTargetTuplePathFromImmutable(
    cameFrom: Map[V, V],
    startNode: V,
    endNode: V
  ): Option[List[(Edge[V, W, L], V)]] =
    reconstructEdgeTargetTuplePath(mutable.Map.newBuilder(cameFrom).result(), startNode, endNode)

}

object Graph {

  /** Cria um grafo vazio. */
  def empty[V, W, L]: Graph[V, W, L] = Graph(Map.empty[V, Map[V, EdgeInfo[W, L]]])

  // Configuração do Jackson (como antes)
  private object JacksonConfig {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Constrói JavaType para JsonGraphFormat[V, W, L]. */
    def buildGraphFormatType[V: ClassTag, W: ClassTag, L: ClassTag](): JavaType = { // Adiciona L
      val typeFactory = mapper.getTypeFactory
      val classV = implicitly[ClassTag[V]].runtimeClass
      val classW = implicitly[ClassTag[W]].runtimeClass
      val classL = implicitly[ClassTag[L]].runtimeClass // Obtém classe para L
      // Constrói o tipo parametrizado JsonGraphFormat[V, W, L]
      typeFactory.constructParametricType(classOf[JsonGraphFormat[_, _, _]], classV, classW, classL)
    }
  }

  // --- Estruturas Auxiliares para JSON (Atualizadas) ---

  /** Estrutura para uma aresta no JSON, incluindo o label. */
  private case class JsonEdgeFormat[V, W, L](
    source: V,
    target: V,
    weight: Option[W], // Peso continua opcional
    label: L // Label é obrigatório conforme solicitado
  )

  /** Estrutura que representa o formato JSON completo esperado. */
  private case class JsonGraphFormat[V, W, L]( // Adiciona L
    vertices: Option[List[V]],
    edges: List[JsonEdgeFormat[V, W, L]], // Usa JsonEdgeFormat atualizado
    directed: Boolean
  )

  /** Carrega um grafo a partir de um arquivo JSON usando Jackson.
    *
    * @param filePath
    *   A path to JSON file.
    * @param defaultWeightForUnweighted
    *   Peso a ser usado se 'weight' estiver ausente.
    * @tparam V
    *   Implicit ClassTag for V.
    * @tparam W
    *   Implicit ClassTag for W.
    * @tparam L
    *   Implicit ClassTag for L (necessário para deserializar o label).
    * @return
    *   Try[Graph[V, W, L]] contendo o grafo ou o erro.
    *
    * Formato JSON esperado: { "vertices": ["A", "B", "C"], // Opcional "edges": [ {"source": "A",
    * "target": "B", "weight": 5, "label": {"type": "road", "name": "BR-101"}}, {"source": "B",
    * "target": "C", "label": {"type": "path"}} // Peso omitido ], "directed": false }
    */
  def loadFromJsonFile[V: ClassTag, W: ClassTag, L: ClassTag]( // Adiciona L: ClassTag
    filePath: String,
    defaultWeightForUnweighted: W
  ): Try[Graph[V, W, L]] = {
    val content = JsonUtil.readJsonFile(filePath)
    loadFromJson(content, defaultWeightForUnweighted)
  }

  /** Carrega um grafo a partir de uma string JSON usando Jackson.
    *
    * @param jsonString
    *   A string JSON.
    * @param defaultWeightForUnweighted
    *   Peso a ser usado se 'weight' estiver ausente.
    * @tparam V
    *   Implicit ClassTag for V.
    * @tparam W
    *   Implicit ClassTag for W.
    * @tparam L
    *   Implicit ClassTag for L (necessário para deserializar o label).
    * @return
    *   Try[Graph[V, W, L]] contendo o grafo ou o erro.
    *
    * Formato JSON esperado: { "vertices": ["A", "B", "C"], // Opcional "edges": [ {"source": "A",
    * "target": "B", "weight": 5, "label": {"type": "road", "name": "BR-101"}}, {"source": "B",
    * "target": "C", "label": {"type": "path"}} // Peso omitido ], "directed": false }
    */
  def loadFromJson[V: ClassTag, W: ClassTag, L: ClassTag]( // Adiciona L: ClassTag
    jsonString: String,
    defaultWeightForUnweighted: W
  ): Try[Graph[V, W, L]] = // Retorna Graph[V, W, L]

    Try {
      // 1. Constrói o JavaType para JsonGraphFormat[V, W, L]
      val graphFormatType: JavaType = JacksonConfig.buildGraphFormatType[V, W, L]()

      // 2. Faz o parsing do JSON
      val jsonGraph: JsonGraphFormat[V, W, L] = JacksonConfig.mapper
        .readValue(jsonString, graphFormatType)
        .asInstanceOf[JsonGraphFormat[V, W, L]]

      // 3. Constrói o objeto Graph
      var graph = Graph.empty[V, W, L] // Usa o novo empty

      jsonGraph.vertices.getOrElse(List.empty).foreach {
        vertex =>
          graph = graph.addVertex(vertex)
      }

      jsonGraph.edges.foreach {
        jsonEdge =>
          val weight = jsonEdge.weight.getOrElse(defaultWeightForUnweighted)
          // >>> ATUALIZAÇÃO AQUI <<<
          val label = jsonEdge.label // Obtém o label do JSON

          if (jsonGraph.directed) {
            // >>> ATUALIZAÇÃO AQUI <<<
            graph = graph.addEdge(jsonEdge.source, jsonEdge.target, weight, label) // Passa o label
          } else {
            // >>> ATUALIZAÇÃO AQUI <<<
            graph = graph.addUndirectedEdge(
              jsonEdge.source,
              jsonEdge.target,
              weight,
              label
            ) // Passa o label
          }
      }
      graph

    }.recover {
      case e: com.fasterxml.jackson.core.JsonProcessingException =>
        throw Exception(s"Erro no parsing do JSON (Jackson): ${e.getMessage}", e)
      case e: Exception =>
        throw Exception(s"Erro ao processar o JSON ou construir o grafo: ${e.getMessage}", e)
    }
}
