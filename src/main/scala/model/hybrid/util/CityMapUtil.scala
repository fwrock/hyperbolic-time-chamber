package org.interscity.htc.model.hybrid.util

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.model.hybrid.collections.{ Graph, LoadedGraphData }
import org.interscity.htc.model.hybrid.collections.graph.{ ContractionHierarchiesIndex, LandmarkIndex }
import org.interscity.htc.model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }
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

  /** Pre-computed Contraction Hierarchies index (static). Built once at first access. Use
    * [[getOrRebuildCHIndex]] for traffic-aware rebuilds.
    */
  lazy val chIndex: ContractionHierarchiesIndex[NodeGraph, Double, EdgeGraph] =
    cityMap.buildContractionHierarchies

  /** Number of landmarks for the ALT index (configurable via htc.mobility.landmark-count). */
  private lazy val landmarkCount: Int =
    SimulatorSettingsRegistry
      .get("htc.mobility.landmark-count")
      .orElse(sys.env.get("HTC_MOBILITY_LANDMARK_COUNT"))
      .flatMap(s => scala.util.Try(s.toInt).toOption)
      .getOrElse(16)

  /** Pre-computed ALT (A* + Landmarks + Triangle inequality) index.
    *
    * Built once at first access using farthest-first landmark selection. Number of landmarks
    * is configurable via `htc.mobility.landmark-count` (default: 16).
    */
  lazy val altIndex: LandmarkIndex[NodeGraph, Double, EdgeGraph] =
    LandmarkIndex.build(cityMap, landmarkCount)

  /** Static weights indexed by link ID — used for blocked-link threshold checks. */
  lazy val staticWeightsByLinkId: Map[String, Double] =
    cityMap.edges
      .map(
        e => e.label.id -> e.weight
      )
      .toMap

  private val _adaptiveCHIndex =
    new java.util.concurrent.atomic.AtomicReference[
      ContractionHierarchiesIndex[NodeGraph, Double, EdgeGraph]
    ](null)

  @volatile private var _lastRebuildTick: Int = -1

  private val _rebuildLock = new Object()

  /** Returns the CH index, rebuilding it if the [[CHRebuildPolicy]] is triggered.
    *
    * Rebuild is triggered when '''either''' condition is met:
    *   - `currentTick` is one of the `policy.scheduledTicks` (and hasn't been rebuilt this tick)
    *   - Any link's dynamic weight exceeds `staticWeight × policy.blockThresholdFactor`
    *
    * Thread-safe: only one thread rebuilds at a time; all others receive the previous (still valid)
    * index immediately while the rebuild runs.
    *
    * @param currentTick
    *   Current global simulation tick.
    * @param policy
    *   Rebuild policy — defaults to [[CHRebuildPolicy.fromConfig]].
    */
  def getOrRebuildCHIndex(
    currentTick: Int,
    policy: CHRebuildPolicy = CHRebuildPolicy.fromConfig
  ): ContractionHierarchiesIndex[NodeGraph, Double, EdgeGraph] = {
    val current = _adaptiveCHIndex.get()
    val scheduledNow =
      policy.scheduledTicks.contains(currentTick) && currentTick != _lastRebuildTick
    val blocked = hasBlockedLinks(policy.blockThresholdFactor)

    if (current == null || scheduledNow || blocked) {
      _rebuildLock.synchronized {
        val c2 = _adaptiveCHIndex.get()
        val scheduled2 =
          policy.scheduledTicks.contains(currentTick) && currentTick != _lastRebuildTick
        val blocked2 = c2 == null || hasBlockedLinks(policy.blockThresholdFactor)
        if (c2 == null || scheduled2 || blocked2) {
          val reason =
            if (c2 == null) "first-use"
            else if (scheduled2) s"scheduled tick $currentTick"
            else "blocked link detected"
          println(
            s"[CityMapUtil] Rebuilding adaptive CH index at tick $currentTick ($reason) — nodes=${nodesById.size}, edges=${cityMap.edges.size}"
          )
          val t0 = System.currentTimeMillis()
          val newIndex = cityMap.buildContractionHierarchies
          val elapsed = System.currentTimeMillis() - t0
          println(
            s"[CityMapUtil] CH rebuild complete in ${elapsed}ms (shortcuts=${newIndex.shortcuts.size})"
          )
          _adaptiveCHIndex.set(newIndex)
          _lastRebuildTick = currentTick
          newIndex
        } else c2
      }
    } else current
  }

  /** Returns true if any link's dynamic weight exceeds `staticWeight × factor`. Uses `exists` so it
    * short-circuits on the first blocked link found.
    */
  private def hasBlockedLinks(thresholdFactor: Double): Boolean =
    staticWeightsByLinkId.exists {
      case (linkId, staticWeight) =>
        DynamicWeightCache.getWeight(linkId, staticWeight) > staticWeight * thresholdFactor
    }

  /** Pre-builds the adaptive CH index so the first actor request doesn't pay the cost. Call this
    * from SimulationManager (or equivalent) before the simulation ticks start.
    */
  def warmUp(): Unit = {
    println(s"[CityMapUtil] Warming up CH index (${nodesById.size} nodes)...")
    getOrRebuildCHIndex(
      currentTick = -1,
      policy = CHRebuildPolicy.fromConfig.copy(scheduledTicks = Set(-1))
    )
    println(s"[CityMapUtil] CH warm-up complete.")
  }

  def printMapStats(): Unit = {
    println(s"Nós carregados: ${nodesById.size}")
    println(s"Labels de Arestas (EdgeGraphs) carregados: ${edgeLabelsById.size}")
    println(s"Total de arestas no grafo: ${cityMap.edges.size}")
  }
}
