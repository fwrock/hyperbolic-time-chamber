package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }
import org.interscity.htc.model.hybrid.util.DynamicWeightCache

import java.util.BitSet
import scala.collection.mutable

/** CSR (Compressed Sparse Row) graph representation optimised for shortest-path search.
  *
  * ==Memory layout==
  * {{{
  *   rowPtr(i)   — index in colIdx/edgeWeight where neighbours of node i start
  *   rowPtr(i+1) — exclusive end
  *   colIdx(e)   — target node index for edge e
  *   edgeWeight(e) — static weight (Double) for edge e
  *   edgeIdx(e)  — index into edgeIds / edgeLengths for edge e
  * }}}
  *
  * All per-node and per-edge data is stored in flat primitive arrays — no boxing, no pointer
  * chasing, cache-line friendly. Node references are replaced by Int indices throughout.
  *
  * ==Search==
  * The companion [[CompactGraph.SearchBuffers]] holds per-thread scratch arrays that are
  * re-used between searches via a [[ThreadLocal]]. This eliminates GC pressure entirely during
  * the search hot-path.
  *
  * ==Coexistence==
  * Built from a fully-loaded [[LoadedGraphData]] without changing any JSON parsing logic.
  * The original [[Graph]] remains the source of truth for CH/ALT index construction and for
  * any code that needs the rich `NodeGraph`/`EdgeGraph` domain objects.
  *
  * @param n           Number of nodes.
  * @param rowPtr      CSR row-pointer array (size n+1).
  * @param colIdx      CSR column-index array (size = number of directed edges).
  * @param edgeWeight  Static edge weights, parallel to colIdx.
  * @param edgeIdx     Index into edgeIds/edgeLengths, parallel to colIdx.
  * @param nodeLat     Node latitudes in degrees, indexed by node index.
  * @param nodeLon     Node longitudes in degrees, indexed by node index.
  * @param nodeIds     Node IDs (String) indexed by node index — used only to produce the output route.
  * @param edgeIds     Edge IDs (String) indexed by edge index — used only to produce the output route.
  * @param nodeIndex   Map from node ID String → Int index (used at query entry-points only).
  */
final class CompactGraph private (
  val n: Int,
  private val rowPtr: Array[Int],
  private val colIdx: Array[Int],
  private val edgeWeight: Array[Double],
  private val edgeIdx: Array[Int],
  private val nodeLat: Array[Float],
  private val nodeLon: Array[Float],
  val nodeIds: Array[String],
  val edgeIds: Array[String],
  val nodeIndex: java.util.HashMap[String, Int],
  /** NodeGraph objects parallel to nodeIds — used to bridge into the LandmarkIndex heuristic. */
  val nodeGraphs: Array[NodeGraph]
) {

  // ── Internal thread-local scratch buffers ────────────────────────────────

  private val buffers: ThreadLocal[CompactGraph.SearchBuffers] =
    ThreadLocal.withInitial(() => new CompactGraph.SearchBuffers(n))

  // ── Heuristic ────────────────────────────────────────────────────────────

  /** Great-circle approximation using flat-Earth (fast, accurate for city scale). */
  @inline private def heuristic(u: Int, goal: Int): Double = {
    val dLat = (nodeLat(u) - nodeLat(goal)).toDouble
    val dLon = (nodeLon(u) - nodeLon(goal)).toDouble
    // 1 degree ≈ 111_000 m; longitude correction by cos(lat)
    val latM = dLat * 111_000.0
    val lonM = dLon * 111_000.0 * math.cos(math.toRadians(nodeLat(goal).toDouble))
    math.sqrt(latM * latM + lonM * lonM)
  }

  // ── ALT heuristic (optional landmark index) ───────────────────────────────

  /** A* with an external landmark heuristic (ALT).
    *
    * @param altH  ALT heuristic function (node index u, goal index t) → lower bound on d(u,t).
    *              Pass `null` to use the built-in Euclidean approximation.
    */
  private def runAStar(
    source: Int,
    target: Int,
    altH: (Int, Int) => Double,
    maxExpansions: Int,
    useDynamicWeights: Boolean
  ): Option[(Double, List[(String, String)])] = {

    if (source == target) return Some((0.0, List.empty))

    val buf = buffers.get()
    buf.reset()

    val gScore  = buf.gScore
    val prev    = buf.prev
    val prevEdg = buf.prevEdge
    val visited = buf.visited
    val heap    = buf.heap

    gScore(source) = 0.0
    buf.markDirty(source)
    heap.push(0.0, source)

    var expansions = 0

    while (!heap.isEmpty) {
      val (fU, u) = heap.pop()

      if (visited.get(u)) {
        // stale entry — skip
      } else if (u == target) {
        // Reconstruct path
        val path = mutable.ArrayBuffer[(String, String)]()
        var cur  = target
        while (cur != source) {
          val e = prevEdg(cur)
          path += ((edgeIds(edgeIdx(e)), nodeIds(cur)))
          cur = prev(cur)
        }
        val route = path.reverseIterator.toList
        return Some((gScore(target), route))
      } else {
        visited.set(u)
        expansions += 1
        if (expansions >= maxExpansions) return None

        var e = rowPtr(u)
        val end = rowPtr(u + 1)
        while (e < end) {
          val v  = colIdx(e)
          if (!visited.get(v)) {
            val baseW  = edgeWeight(e)
            val dynW   = if (useDynamicWeights) DynamicWeightCache.getWeight(edgeIds(edgeIdx(e)), baseW) else baseW
            val tentG  = gScore(u) + dynW
            if (tentG < gScore(v)) {
              gScore(v)  = tentG
              prev(v)    = u
              prevEdg(v) = e
              buf.markDirty(v)
              val h = if (altH != null) altH(v, target) else heuristic(v, target)
              heap.push(tentG + h, v)
            }
          }
          e += 1
        }
      }
    }

    None
  }

  /** A* search using built-in Euclidean heuristic (fast, no precomputed index). */
  def aStarEuclidean(
    originId: String,
    destinationId: String,
    useDynamicWeights: Boolean = true,
    maxExpansions: Int = 150_000
  ): Option[(Double, mutable.Queue[(String, String)])] = {
    val src = nodeIndex.getOrDefault(originId, -1)
    val dst = nodeIndex.getOrDefault(destinationId, -1)
    if (src == -1 || dst == -1) return None
    runAStar(src, dst, null, maxExpansions, useDynamicWeights)
      .map { case (cost, path) => (cost, mutable.Queue.from(path)) }
  }

  /** A* search with an external ALT heuristic (node-index based).
    *
    * @param altH  Function (nodeIdxU: Int, nodeIdxTarget: Int) → Double lower bound.
    *              Build it from [[CompactLandmarkIndex]] for best results.
    */
  def aStarALT(
    originId: String,
    destinationId: String,
    altH: (Int, Int) => Double,
    useDynamicWeights: Boolean = true,
    maxExpansions: Int = 150_000
  ): Option[(Double, mutable.Queue[(String, String)])] = {
    val src = nodeIndex.getOrDefault(originId, -1)
    val dst = nodeIndex.getOrDefault(destinationId, -1)
    if (src == -1 || dst == -1) return None
    runAStar(src, dst, altH, maxExpansions, useDynamicWeights)
      .map { case (cost, path) => (cost, mutable.Queue.from(path)) }
  }

  /** Node index for a given node ID string, or -1 if not found. */
  def indexOf(nodeId: String): Int = nodeIndex.getOrDefault(nodeId, -1)
}

// ── Companion object ──────────────────────────────────────────────────────────

object CompactGraph {

  // ── Per-thread scratch buffers ─────────────────────────────────────────────

  /** Scratch buffers for one A* search. Allocated once per thread, cleared between searches.
    *
    * Dirty-tracking resets only the touched entries in O(expansions), not O(n).
    */
  final class SearchBuffers(n: Int) {

    /** g-scores; default PositiveInfinity without zeroing the full array each time. */
    val gScore: Array[Double] = Array.fill(n)(Double.PositiveInfinity)

    /** Predecessor node index (-1 = none). */
    val prev: Array[Int] = Array.fill(n)(-1)

    /** Edge index (into CSR) that led to each node. */
    val prevEdge: Array[Int] = Array.fill(n)(-1)

    /** Visited bitset — 1 bit per node, 61 KB for 488K nodes. */
    val visited: BitSet = new BitSet(n)

    /** Binary min-heap over (fScore: Double, nodeIdx: Int). */
    val heap: DoubleIntHeap = new DoubleIntHeap(8192)

    /** Tracks which node indices were modified so we can reset only those.
      * A node may be relaxed (gScore improved) multiple times in one search, but we only
      * need one dirty entry per node.  The companion [[inDirty]] bitset ensures we never
      * add the same index twice, preventing an [[ArrayIndexOutOfBoundsException]] when
      * dirtySize would otherwise reach n (= array length).
      */
    private val dirty: Array[Int] = new Array[Int](n)
    private var dirtySize: Int    = 0
    private val inDirty: BitSet   = new BitSet(n)

    @inline def markDirty(i: Int): Unit =
      if (!inDirty.get(i)) {
        dirty(dirtySize) = i
        dirtySize += 1
        inDirty.set(i)
      }

    /** Reset only the touched entries — O(expansions), not O(n). */
    def reset(): Unit = {
      var i = 0
      while (i < dirtySize) {
        val idx = dirty(i)
        gScore(idx)   = Double.PositiveInfinity
        prev(idx)     = -1
        prevEdge(idx) = -1
        i += 1
      }
      dirtySize = 0
      inDirty.clear()
      visited.clear()
      heap.clear()
    }
  }

  // ── Minimal binary min-heap (Double key, Int value) ────────────────────────
  // Avoids boxing: stores interleaved (bits-of-double, int) in a long[].

  final class DoubleIntHeap(initialCapacity: Int) {
    // Stores pairs as two parallel arrays to avoid tuple allocation
    private var keys:   Array[Double] = new Array[Double](initialCapacity)
    private var values: Array[Int]    = new Array[Int](initialCapacity)
    private var size: Int             = 0

    def isEmpty: Boolean = size == 0

    def clear(): Unit = { size = 0 }

    def push(key: Double, value: Int): Unit = {
      if (size == keys.length) grow()
      keys(size)   = key
      values(size) = value
      siftUp(size)
      size += 1
    }

    /** Returns (key, value) of the minimum element and removes it. */
    def pop(): (Double, Int) = {
      val k = keys(0)
      val v = values(0)
      size -= 1
      if (size > 0) {
        keys(0)   = keys(size)
        values(0) = values(size)
        siftDown(0)
      }
      (k, v)
    }

    private def grow(): Unit = {
      val newCap = keys.length * 2
      keys   = java.util.Arrays.copyOf(keys, newCap)
      values = java.util.Arrays.copyOf(values, newCap)
    }

    @inline private def siftUp(i: Int): Unit = {
      var idx = i
      while (idx > 0) {
        val parent = (idx - 1) >>> 1
        if (keys(parent) > keys(idx)) {
          swap(parent, idx)
          idx = parent
        } else {
          idx = 0 // break
        }
      }
    }

    @inline private def siftDown(i: Int): Unit = {
      var idx = i
      var continue = true
      while (continue) {
        val l = (idx << 1) + 1
        val r = l + 1
        var smallest = idx
        if (l < size && keys(l) < keys(smallest)) smallest = l
        if (r < size && keys(r) < keys(smallest)) smallest = r
        if (smallest != idx) {
          swap(smallest, idx)
          idx = smallest
        } else {
          continue = false
        }
      }
    }

    @inline private def swap(a: Int, b: Int): Unit = {
      val tk = keys(a);   keys(a)   = keys(b);   keys(b)   = tk
      val tv = values(a); values(a) = values(b); values(b) = tv
    }
  }

  // ── Builder ────────────────────────────────────────────────────────────────

  /** Build a [[CompactGraph]] from the already-loaded [[LoadedGraphData]].
    *
    * This is called once at startup (lazy) and does not change the JSON-loading pipeline.
    */
  def fromLoaded(
    data: LoadedGraphData[NodeGraph, String, Double, EdgeGraph]
  ): CompactGraph = {

    val nodeList: Array[NodeGraph] = data.graph.vertices.toArray
    val n                         = nodeList.length

    // Assign a stable Int index to each NodeGraph
    val nodeIdxMap = new java.util.HashMap[String, Int](n * 2)
    val nodeIdArr  = new Array[String](n)
    val latArr     = new Array[Float](n)
    val lonArr     = new Array[Float](n)

    var i = 0
    while (i < n) {
      val node = nodeList(i)
      nodeIdxMap.put(node.id, i)
      nodeIdArr(i) = node.id
      latArr(i)    = node.latitude.toFloat
      lonArr(i)    = node.longitude.toFloat
      i += 1
    }

    // Collect all edges ordered by source node (for CSR construction)
    val m = data.graph.edges.size

    // First pass: count out-degree per node
    val degree = new Array[Int](n)
    data.graph.edges.foreach { e =>
      val src = nodeIdxMap.get(e.source.id)
      degree(src) += 1
    }

    // Build rowPtr (prefix sum)
    val rowPtr = new Array[Int](n + 1)
    i = 0
    while (i < n) {
      rowPtr(i + 1) = rowPtr(i) + degree(i)
      i += 1
    }

    // Collect edge label IDs (deduplicated, assign edge-label index)
    val edgeLabelIdxMap = new java.util.HashMap[String, Int](m * 2)
    val edgeLabelIdList = mutable.ArrayBuffer[String]()
    data.edgeLabelsById.foreachEntry { (id, _) =>
      if (!edgeLabelIdxMap.containsKey(id)) {
        edgeLabelIdxMap.put(id, edgeLabelIdList.size)
        edgeLabelIdList += id
      }
    }
    val edgeLabelIds = edgeLabelIdList.toArray

    // Second pass: fill CSR arrays
    val colIdx     = new Array[Int](m)
    val edgeWt     = new Array[Double](m)
    val edgeIdxArr = new Array[Int](m)
    val cursor     = rowPtr.clone() // tracks current insertion position per row

    data.graph.edges.foreach { e =>
      val src = nodeIdxMap.get(e.source.id)
      val dst = nodeIdxMap.get(e.target.id)
      val pos = cursor(src)
      colIdx(pos)     = dst
      edgeWt(pos)     = e.weight
      edgeIdxArr(pos) = edgeLabelIdxMap.get(e.label.id)
      cursor(src)    += 1
    }

    // NodeGraph array parallel to nodeIdArr — enables O(1) bridge into LandmarkIndex.heuristic
    val nodeGraphArr = new Array[NodeGraph](n)
    var j = 0
    while (j < n) {
      nodeGraphArr(j) = nodeList(j)
      j += 1
    }

    new CompactGraph(
      n           = n,
      rowPtr      = rowPtr,
      colIdx      = colIdx,
      edgeWeight  = edgeWt,
      edgeIdx     = edgeIdxArr,
      nodeLat     = latArr,
      nodeLon     = lonArr,
      nodeIds     = nodeIdArr,
      edgeIds     = edgeLabelIds,
      nodeIndex   = nodeIdxMap,
      nodeGraphs  = nodeGraphArr
    )
  }

  /** Build an undirected [[CompactGraph]] for pedestrian routing.
    *
    * Pedestrians do not obey one-way street restrictions: they walk on sidewalks in both
    * directions regardless of the vehicle traffic direction. This builder adds a reverse edge
    * for every directed edge in the original graph with the same weight, making every road
    * traversable in both directions.
    *
    * ==Correctness note==
    * The ALT [[CompactLandmarkIndex]] is precomputed on the directed vehicle graph and is
    * therefore NOT admissible for this undirected graph (reverse edges can create shorter paths,
    * making the directed heuristic overestimate). Callers must use [[aStarEuclidean]] (always
    * admissible) rather than [[aStarALT]] for pedestrian searches.
    */
  def fromLoadedBidirectional(
    data: LoadedGraphData[NodeGraph, String, Double, EdgeGraph]
  ): CompactGraph = {

    val nodeList: Array[NodeGraph] = data.graph.vertices.toArray
    val n                         = nodeList.length

    val nodeIdxMap = new java.util.HashMap[String, Int](n * 2)
    val nodeIdArr  = new Array[String](n)
    val latArr     = new Array[Float](n)
    val lonArr     = new Array[Float](n)

    var i = 0
    while (i < n) {
      val node = nodeList(i)
      nodeIdxMap.put(node.id, i)
      nodeIdArr(i) = node.id
      latArr(i)    = node.latitude.toFloat
      lonArr(i)    = node.longitude.toFloat
      i += 1
    }

    // Edge label index (same as directed builder)
    val m               = data.graph.edges.size
    val edgeLabelIdxMap = new java.util.HashMap[String, Int](m * 2)
    val edgeLabelIdList = mutable.ArrayBuffer[String]()
    data.edgeLabelsById.foreachEntry { (id, _) =>
      if (!edgeLabelIdxMap.containsKey(id)) {
        edgeLabelIdxMap.put(id, edgeLabelIdList.size)
        edgeLabelIdList += id
      }
    }
    val edgeLabelIds = edgeLabelIdList.toArray

    // Count degree: each directed edge contributes +1 to src and +1 to dst (reverse)
    val degree = new Array[Int](n)
    data.graph.edges.foreach { e =>
      val src = nodeIdxMap.get(e.source.id)
      val dst = nodeIdxMap.get(e.target.id)
      degree(src) += 1
      if (src != dst) degree(dst) += 1 // avoid self-loop duplication
    }

    val rowPtr = new Array[Int](n + 1)
    i = 0
    while (i < n) {
      rowPtr(i + 1) = rowPtr(i) + degree(i)
      i += 1
    }

    val totalEdges = rowPtr(n)
    val colIdx     = new Array[Int](totalEdges)
    val edgeWt     = new Array[Double](totalEdges)
    val edgeIdxArr = new Array[Int](totalEdges)
    val cursor     = rowPtr.clone()

    data.graph.edges.foreach { e =>
      val src  = nodeIdxMap.get(e.source.id)
      val dst  = nodeIdxMap.get(e.target.id)
      val eIdx = edgeLabelIdxMap.get(e.label.id)

      // Forward edge
      val fpos = cursor(src)
      colIdx(fpos)     = dst
      edgeWt(fpos)     = e.weight
      edgeIdxArr(fpos) = eIdx
      cursor(src)     += 1

      // Reverse edge (pedestrians can walk against traffic direction)
      if (src != dst) {
        val rpos = cursor(dst)
        colIdx(rpos)     = src
        edgeWt(rpos)     = e.weight
        edgeIdxArr(rpos) = eIdx
        cursor(dst)     += 1
      }
    }

    val nodeGraphArr = new Array[NodeGraph](n)
    var j = 0
    while (j < n) {
      nodeGraphArr(j) = nodeList(j)
      j += 1
    }

    new CompactGraph(
      n           = n,
      rowPtr      = rowPtr,
      colIdx      = colIdx,
      edgeWeight  = edgeWt,
      edgeIdx     = edgeIdxArr,
      nodeLat     = latArr,
      nodeLon     = lonArr,
      nodeIds     = nodeIdArr,
      edgeIds     = edgeLabelIds,
      nodeIndex   = nodeIdxMap,
      nodeGraphs  = nodeGraphArr
    )
  }
}
