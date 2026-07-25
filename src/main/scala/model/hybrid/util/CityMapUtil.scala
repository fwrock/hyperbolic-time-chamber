package org.interscity.htc.model.hybrid.util

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.model.hybrid.collections.{ CompactGraph, CompactLandmarkIndex, Graph, LoadedGraphData, SCCIndex }
import org.interscity.htc.model.hybrid.collections.graph.{ ContractionHierarchiesIndex, LandmarkIndex }
import org.interscity.htc.model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }
import scala.util.{ Failure, Success }

object CityMapUtil {

  val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id

  val edgeGraphIdExtractor: EdgeGraph => String = (edgeLabel: EdgeGraph) => edgeLabel.id

  private lazy val cityMapFilePath: String =
    SimulatorSettingsRegistry
      .get("htc.mobility.city-map-file")
      .orElse(sys.env.get("HTC_MOBILITY_CITY_MAP_FILE"))
      .getOrElse("city_map.json")

  private lazy val loadedCityData: LoadedGraphData[NodeGraph, String, Double, EdgeGraph] =
    (
      if (cityMapFilePath.endsWith(".db")) {
        Graph.loadFromSqliteFile(cityMapFilePath)
      } else {
        Graph.loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
          cityMapFilePath,
          nodeGraphIdExtractor,
          edgeGraphIdExtractor,
          0.0
        )
      }
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

  // -------------------------------------------------------------------------
  // Feature flags (read once, with sensible defaults)
  //
  // Settings priority: SimulatorSettingsRegistry > env var > default.
  //
  //   - htc.mobility.enable-pedestrian-routing  (default: true)
  //       If false, pedestrian indexes are NEVER built — saves ~one full
  //       CompactGraph (bidirectional ≈ 2× directed) + ALT landmarks +
  //       compact ALT. For purely vehicular scenarios with no Person actors
  //       walking, set to false to drop several GBs of baseline RAM.
  //
  //   - htc.mobility.enable-ch                  (default: false)
  //       If false, contraction-hierarchies index is never built (calls to
  //       calcRouteCH* will throw / log). Default is false because production
  //       routing uses calcRouteCompact (CompactGraph + ALT) — CH is opt-in.
  //
  //   - htc.mobility.landmark-count             (default: 16)
  // -------------------------------------------------------------------------
  private lazy val enablePedestrianRouting: Boolean =
    SimulatorSettingsRegistry
      .get("htc.mobility.enable-pedestrian-routing")
      .orElse(sys.env.get("HTC_MOBILITY_ENABLE_PEDESTRIAN_ROUTING"))
      .flatMap(s => scala.util.Try(s.toBoolean).toOption)
      .getOrElse(true)

  private lazy val enableCH: Boolean =
    SimulatorSettingsRegistry
      .get("htc.mobility.enable-ch")
      .orElse(sys.env.get("HTC_MOBILITY_ENABLE_CH"))
      .flatMap(s => scala.util.Try(s.toBoolean).toOption)
      .getOrElse(false)

  /** Number of landmarks for the ALT index (configurable via htc.mobility.landmark-count). */
  private lazy val landmarkCount: Int =
    SimulatorSettingsRegistry
      .get("htc.mobility.landmark-count")
      .orElse(sys.env.get("HTC_MOBILITY_LANDMARK_COUNT"))
      .flatMap(s => scala.util.Try(s.toInt).toOption)
      .getOrElse(16)

  // -------------------------------------------------------------------------
  // Indexes that stay resident for the whole simulation lifetime.
  // Kept as lazy vals — no need to release them.
  // -------------------------------------------------------------------------

  /** Compact CSR representation of the city graph.
    *
    * Built once from [[loadedCityData]] without changing the JSON-loading pipeline.
    * Uses flat primitive arrays (no boxing) and ThreadLocal scratch buffers to eliminate
    * GC pressure during A* searches. See [[CompactGraph]] for details.
    */
  lazy val compactGraph: CompactGraph = {
    println(s"[CityMapUtil] Building CompactGraph (CSR)...")
    val t0 = System.currentTimeMillis()
    val cg = CompactGraph.fromLoaded(loadedCityData)
    println(s"[CityMapUtil] CompactGraph ready in ${System.currentTimeMillis() - t0}ms (${cg.n} nodes)")
    cg
  }

  /** SCC condensation reachability index for the vehicle graph.
    *
    * Built lazily on first access (or eagerly during [[warmUp]]).
    * O(1) canReach queries after construction.
    */
  lazy val sccIndex: SCCIndex = {
    println(s"[CityMapUtil] Building SCC condensation index...")
    val t0  = System.currentTimeMillis()
    val idx = SCCIndex.fromCompactGraph(compactGraph)
    println(
      s"[CityMapUtil] SCC index ready in ${System.currentTimeMillis() - t0}ms " +
        s"(${idx.numSCCs} SCCs for ${compactGraph.n} nodes)"
    )
    idx
  }

  /** SCC condensation reachability index for the pedestrian (undirected) graph.
    *
    * For an undirected graph every SCC is a connected component, so this check
    * eliminates disconnected islands before walking A*.
    */
  def sccIndexPedestrian: SCCIndex = {
    val current = _sccIndexPedestrian
    if (current != null) current
    else
      synchronized {
        if (_sccIndexPedestrian == null) {
          println(s"[CityMapUtil] Building pedestrian SCC condensation index...")
          val t0 = System.currentTimeMillis()
          _sccIndexPedestrian = SCCIndex.fromCompactGraph(compactGraphPedestrian)
          println(
            s"[CityMapUtil] Pedestrian SCC index ready in ${System.currentTimeMillis() - t0}ms " +
              s"(${_sccIndexPedestrian.numSCCs} SCCs)"
          )
        }
        _sccIndexPedestrian
      }
  }

  /** Pre-computed Contraction Hierarchies index (static). Built once at first access. Use
    * [[getOrRebuildCHIndex]] for traffic-aware rebuilds.
    */
  lazy val chIndex: ContractionHierarchiesIndex[NodeGraph, Double, EdgeGraph] =
    cityMap.buildContractionHierarchies

  // -------------------------------------------------------------------------
  // Transient indexes — heavy Map-based structures used only to BUILD the
  // compact array-based versions. After warmUp() they are released by
  // releaseTransientIndexes() to free hundreds of MBs / several GBs of heap.
  //
  // Access patterns:
  //   - compactAltIndex / compactAltIndexPedestrian are kept resident and
  //     used in production routing (calcRouteCompact, calcRouteCompactWalking).
  //   - altIndex / altIndexPedestrian are only needed to construct the
  //     compact versions and for the rarely-used calcRouteALT (which falls
  //     back to a rebuild if released).
  //   - pedestrianCityMap is only needed to construct altIndexPedestrian.
  // -------------------------------------------------------------------------

  @volatile private var _altIndex: LandmarkIndex[NodeGraph, Double, EdgeGraph] = null
  @volatile private var _altIndexPedestrian: LandmarkIndex[NodeGraph, Double, EdgeGraph] = null
  @volatile private var _pedestrianCityMap: Graph[NodeGraph, Double, EdgeGraph] = null
  @volatile private var _compactGraphPedestrian: CompactGraph = null
  @volatile private var _compactAltIndex: CompactLandmarkIndex = null
  @volatile private var _compactAltIndexPedestrian: CompactLandmarkIndex = null
  @volatile private var _sccIndexPedestrian: SCCIndex = null

  /** Pre-computed ALT (A* + Landmarks + Triangle inequality) index.
    *
    * Built on first access. Released by [[releaseTransientIndexes]] once the
    * compact version is built — calling this after release will rebuild it.
    */
  def altIndex: LandmarkIndex[NodeGraph, Double, EdgeGraph] = {
    val current = _altIndex
    if (current != null) current
    else
      synchronized {
        if (_altIndex == null) {
          println(s"[CityMapUtil] Building ALT landmark index ($landmarkCount landmarks)...")
          val t0 = System.currentTimeMillis()
          _altIndex = LandmarkIndex.build(cityMap, landmarkCount)
          println(s"[CityMapUtil] ALT index ready in ${System.currentTimeMillis() - t0}ms")
        }
        _altIndex
      }
  }

  /** Compact array-based ALT index for zero-allocation heuristic evaluation.
    *
    * Translates the `Map[NodeGraph, Double]` landmark distances from [[altIndex]] into flat
    * `Array[Array[Double]]` indexed by [[CompactGraph]] Int node index.
    */
  def compactAltIndex: CompactLandmarkIndex = {
    val current = _compactAltIndex
    if (current != null) current
    else
      synchronized {
        if (_compactAltIndex == null) {
          _compactAltIndex = CompactLandmarkIndex.fromLandmarkIndex(
            altIndex,
            compactGraph.nodeIndex,
            nodeGraphIdExtractor,
            compactGraph.n
          )
        }
        _compactAltIndex
      }
  }

  /** Undirected CompactGraph for pedestrian routing.
    *
    * Built only when pedestrian routing is enabled (default: true). Set
    * `htc.mobility.enable-pedestrian-routing=false` for vehicular-only scenarios.
    */
  def compactGraphPedestrian: CompactGraph = {
    val current = _compactGraphPedestrian
    if (current != null) current
    else
      synchronized {
        if (_compactGraphPedestrian == null) {
          println(s"[CityMapUtil] Building pedestrian CompactGraph (undirected CSR)...")
          val t0 = System.currentTimeMillis()
          _compactGraphPedestrian = CompactGraph.fromLoadedBidirectional(loadedCityData)
          println(s"[CityMapUtil] Pedestrian CompactGraph ready in ${System.currentTimeMillis() - t0}ms")
        }
        _compactGraphPedestrian
      }
  }

  /** Undirected Graph for pedestrian routing — same as [[cityMap]] but with every edge
    * duplicated in the reverse direction so ALT landmarks are computed on the correct graph.
    *
    * Released by [[releaseTransientIndexes]] after [[altIndexPedestrian]] is built.
    */
  def pedestrianCityMap: Graph[NodeGraph, Double, EdgeGraph] = {
    val current = _pedestrianCityMap
    if (current != null) current
    else
      synchronized {
        if (_pedestrianCityMap == null) {
          println(s"[CityMapUtil] Building pedestrian Graph (objects)...")
          val t0 = System.currentTimeMillis()
          _pedestrianCityMap = cityMap.edges.foldLeft(cityMap) { (g, e) =>
            g.addEdge(e.target, e.source, e.weight, e.label)
          }
          println(s"[CityMapUtil] Pedestrian Graph ready in ${System.currentTimeMillis() - t0}ms")
        }
        _pedestrianCityMap
      }
  }

  /** ALT landmark index built on the undirected [[pedestrianCityMap]].
    * Released by [[releaseTransientIndexes]] after [[compactAltIndexPedestrian]] is built.
    */
  def altIndexPedestrian: LandmarkIndex[NodeGraph, Double, EdgeGraph] = {
    val current = _altIndexPedestrian
    if (current != null) current
    else
      synchronized {
        if (_altIndexPedestrian == null) {
          println(s"[CityMapUtil] Building pedestrian ALT landmark index ($landmarkCount landmarks)...")
          val t0 = System.currentTimeMillis()
          _altIndexPedestrian = LandmarkIndex.build(pedestrianCityMap, landmarkCount)
          println(s"[CityMapUtil] Pedestrian ALT index ready in ${System.currentTimeMillis() - t0}ms")
        }
        _altIndexPedestrian
      }
  }

  /** Compact array-based ALT index for zero-allocation pedestrian heuristic evaluation. */
  def compactAltIndexPedestrian: CompactLandmarkIndex = {
    val current = _compactAltIndexPedestrian
    if (current != null) current
    else
      synchronized {
        if (_compactAltIndexPedestrian == null) {
          _compactAltIndexPedestrian = CompactLandmarkIndex.fromLandmarkIndex(
            altIndexPedestrian,
            compactGraphPedestrian.nodeIndex,
            nodeGraphIdExtractor,
            compactGraphPedestrian.n
          )
        }
        _compactAltIndexPedestrian
      }
  }

  /** Releases transient (Map-based) indexes that are no longer needed once their compact
    * array-based counterparts have been built.
    *
    *   - `altIndex`            (kept by `compactAltIndex`)
    *   - `altIndexPedestrian`  (kept by `compactAltIndexPedestrian`)
    *   - `pedestrianCityMap`   (only needed to build `altIndexPedestrian`)
    *
    * Safe to call multiple times. After release, re-accessing any of these will rebuild
    * them on demand. Called automatically at the end of [[warmUp]].
    */
  def releaseTransientIndexes(): Unit = synchronized {
    val freed = scala.collection.mutable.ListBuffer[String]()
    if (_altIndex != null) { _altIndex = null; freed += "altIndex" }
    if (_altIndexPedestrian != null) { _altIndexPedestrian = null; freed += "altIndexPedestrian" }
    if (_pedestrianCityMap != null) { _pedestrianCityMap = null; freed += "pedestrianCityMap" }
    if (freed.nonEmpty) {
      println(s"[CityMapUtil] Released transient indexes: ${freed.mkString(", ")} — suggesting GC")
      System.gc()
    }
  }

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

  /** Pre-builds compact graph and ALT landmark indexes (vehicle, plus pedestrian when enabled)
    * so the first routing call on this JVM doesn't pay the ~150s initialization cost.
    *
    * Called by [[WarmUpWorker]] on every cluster node before simulation ticks start.
    * Does NOT build the CH index — that is handled lazily by [[getOrRebuildCHIndex]].
    *
    * After warmup, releases the transient (Map-based) indexes whose data has been folded
    * into the compact array-based versions. This frees a substantial amount of heap
    * (often several GBs on city-scale graphs).
    */
  def warmUp(): Unit = {
    println(
      s"[CityMapUtil] Warming up routing indexes (${nodesById.size} nodes, " +
        s"pedestrian=$enablePedestrianRouting, ch=$enableCH)..."
    )
    val t0 = System.currentTimeMillis()

    // Vehicle graph (directed) — always required by calcRouteCompact.
    val _cg  = compactGraph
    val _cai = compactAltIndex
    val _sci = sccIndex   // O(1) reachability check; built once here

    // Pedestrian graph (undirected) — only if Person actors will walk.
    if (enablePedestrianRouting) {
      val _cgp  = compactGraphPedestrian
      val _caip = compactAltIndexPedestrian
      val _scip = sccIndexPedestrian
    } else {
      println(
        "[CityMapUtil] Pedestrian routing disabled (htc.mobility.enable-pedestrian-routing=false)" +
          " — skipping pedestrian indexes."
      )
    }

    // Optional: CH for calcRouteCH* (off by default since production routing uses calcRouteCompact).
    if (enableCH) {
      val _ch = chIndex
      println(s"[CityMapUtil] CH index pre-built (${_ch.shortcuts.size} shortcuts).")
    }

    println(s"[CityMapUtil] Warm-up complete in ${System.currentTimeMillis() - t0}ms")

    // Free Map-based intermediate structures whose data is now in the compact arrays.
    releaseTransientIndexes()
  }

  def printMapStats(): Unit = {
    println(s"Nós carregados: ${nodesById.size}")
    println(s"Labels de Arestas (EdgeGraphs) carregados: ${edgeLabelsById.size}")
    println(s"Total de arestas no grafo: ${cityMap.edges.size}")
  }
}
