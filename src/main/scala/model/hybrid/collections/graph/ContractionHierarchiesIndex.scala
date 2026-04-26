package org.interscity.htc
package model.hybrid.collections.graph

import scala.collection.mutable

/** Contraction Hierarchies (CH) index.
  *
  * Precomputed during startup via [[org.interscity.htc.model.hybrid.collections.Graph.buildContractionHierarchies]].
  * Once built, point-to-point queries run orders of magnitude faster than plain Dijkstra on
  * large road networks.
  *
  * Algorithm summary
  * -----------------
  * 1. **Preprocessing** – nodes are assigned a numeric *order* (rank). Nodes are contracted
  *    one by one in order of estimated importance (edge-difference heuristic). When a node v
  *    is contracted, *shortcut* edges are inserted between every pair of neighbours
  *    (u → v → w) that would otherwise lose their shortest path through v.
  *
  * 2. **Query** – bidirectional Dijkstra that only relaxes edges pointing *upward* in the
  *    hierarchy (i.e. towards higher-ranked nodes). The optimal meeting point gives the
  *    shortest distance.
  *
  * 3. **Path unpacking** – shortcuts record the *via* node they replaced, allowing full
  *    path reconstruction by recursive unpacking.
  *
  * Limitations / trade-offs in this implementation
  * ------------------------------------------------
  * - Node ordering uses a simple **edge-difference** heuristic (|shortcuts added| − |edges
  *   removed|). More sophisticated orderings (e.g. combined with "independence" or
  *   "cover quotient") would yield better query performance on very large graphs.
  * - The graph type parameter `V` must have a stable `hashCode`/`equals` (satisfied by
  *   `NodeGraph` which is a `case class`).
  * - The index is *immutable* after construction; dynamic weight updates require rebuilding.
  *   For live traffic use the Dijkstra-based `GPSUtil.calcRoute` with `useDynamicWeights = true`.
  *
  * @param upwardEdges   For every node: neighbour → (weight, label) pairs that go UP in rank.
  * @param downwardEdges For every node: neighbour → (weight, label) pairs that go DOWN in rank
  *                      (used in the backward search which traverses edges in reverse).
  * @param shortcuts     Shortcut metadata: (u, w) → via-node v (used for path unpacking).
  * @param rank          Node → its contraction order (lower = contracted earlier = less important).
  * @param edgesById     Original edge labels indexed by (source, target) for path unpacking.
  */
case class ContractionHierarchiesIndex[V, W, L](
  upwardEdges: Map[V, Map[V, (Double, Option[L])]],
  downwardEdges: Map[V, Map[V, (Double, Option[L])]],
  shortcuts: Map[(V, V), V],
  rank: Map[V, Int],
  edgesById: Map[(V, V), (Double, L)]
) {

  /** Query: find shortest distance + path between two nodes using bidirectional CH-Dijkstra.
    *
    * @return Some((cost, List of (edge-label-id, target-node))) or None if unreachable.
    *         The path uses the *original* edge labels (shortcuts are unpacked transparently).
    */
  def query(source: V, target: V): Option[(Double, List[(L, V)])] =
    queryAStar(source, target, (_, _) => 0.0)

  /** Query: find shortest distance + path using bidirectional CH-A*.
    *
    * Guides the bidirectional search with a potential function, reducing the number of
    * nodes settled compared to plain CH-Dijkstra. For road networks, passing the
    * Euclidean (great-circle) distance as `heuristic` yields the best speedup.
    *
    * Requirements for correctness:
    *  - `heuristic` must be *admissible* (never overestimates) and *consistent* (monotone).
    *  - For the forward search  : priority(u) = g(u) + h(u, target)
    *  - For the backward search : priority(u) = g(u) + h(source, u)
    * Because CH restricts relaxation to upward edges only, the usual consistency of the
    * Euclidean heuristic on road networks is preserved.
    *
    * @param heuristic A lower-bound function on the remaining distance. Passing `(_, _) => 0.0`
    *                  degenerates to plain [[query]] (CH-Dijkstra).
    * @return Some((cost, path)) or None if unreachable.
    */
  def queryAStar(source: V, target: V, heuristic: (V, V) => Double): Option[(Double, List[(L, V)])] = {
    if (source == target) return Some((0.0, List.empty))

    val distF = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val prevF = mutable.Map[V, V]()
    val distB = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    val prevB = mutable.Map[V, V]()

    val pqF = mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)
    val pqB = mutable.PriorityQueue[(Double, V)]()(Ordering.by[(Double, V), Double](_._1).reverse)

    distF(source) = 0.0
    distB(target) = 0.0
    pqF.enqueue((heuristic(source, target), source))
    pqB.enqueue((heuristic(target, source), target))

    val settledF = mutable.Set[V]()
    val settledB = mutable.Set[V]()

    var bestDist    = Double.PositiveInfinity
    var meetingNode: Option[V] = None

    def relaxF(): Unit = {
      if (pqF.isEmpty) return
      val (_, u) = pqF.dequeue()
      if (settledF.contains(u)) return
      settledF.add(u)
      if (settledB.contains(u)) {
        val candidate = distF(u) + distB(u)
        if (candidate < bestDist) { bestDist = candidate; meetingNode = Some(u) }
      }
      upwardEdges.getOrElse(u, Map.empty).foreach {
        case (v, (w, _)) =>
          val nd = distF(u) + w
          if (nd < distF(v)) {
            distF(v) = nd; prevF(v) = u
            pqF.enqueue((nd + heuristic(v, target), v))
          }
      }
    }

    def relaxB(): Unit = {
      if (pqB.isEmpty) return
      val (_, u) = pqB.dequeue()
      if (settledB.contains(u)) return
      settledB.add(u)
      if (settledF.contains(u)) {
        val candidate = distF(u) + distB(u)
        if (candidate < bestDist) { bestDist = candidate; meetingNode = Some(u) }
      }
      downwardEdges.getOrElse(u, Map.empty).foreach {
        case (v, (w, _)) =>
          val nd = distB(u) + w
          if (nd < distB(v)) {
            distB(v) = nd; prevB(v) = u
            pqB.enqueue((nd + heuristic(source, v), v))
          }
      }
    }

    while (pqF.nonEmpty || pqB.nonEmpty) {
      val nextF = if (pqF.nonEmpty) pqF.head._1 else Double.PositiveInfinity
      val nextB = if (pqB.nonEmpty) pqB.head._1 else Double.PositiveInfinity
      if (nextF >= bestDist && nextB >= bestDist) {
        pqF.clear(); pqB.clear()
      } else if (nextF <= nextB) relaxF()
      else relaxB()
    }

    meetingNode.filter(_ => bestDist < Double.PositiveInfinity).map {
      mid =>
        val pathF = unpackForward(prevF, source, mid)
        val pathB = unpackBackward(prevB, mid, target)
        val fullPath = pathF ++ pathB
        val edgeList = fullPath.sliding(2).collect {
          case Seq(a, b) => edgesById.get((a, b)).map { case (_, lbl) => (lbl, b) }
        }.flatten.toList
        (bestDist, edgeList)
    }
  }

  // Reconstruct forward path (source → mid) using prevF map, then unpack shortcuts
  private def unpackForward(prevF: mutable.Map[V, V], source: V, mid: V): List[V] = {
    val raw = mutable.ListBuffer[V]()
    var cur = mid
    raw.prepend(cur)
    while (cur != source && prevF.contains(cur)) {
      cur = prevF(cur); raw.prepend(cur)
    }
    expandShortcuts(raw.toList)
  }

  private def unpackBackward(prevB: mutable.Map[V, V], mid: V, target: V): List[V] = {
    val raw = mutable.ListBuffer[V]()
    var cur = target
    raw.prepend(cur)
    while (cur != mid && prevB.contains(cur)) {
      cur = prevB(cur); raw.prepend(cur)
    }
    val segment = raw.toList
    if (segment.headOption.contains(mid)) expandShortcuts(segment.tail)
    else expandShortcuts(segment)
  }

  /** Recursively expand shortcut edges into original edges. */
  private def expandShortcuts(path: List[V]): List[V] = {
    @annotation.tailrec
    def expand(remaining: List[V], acc: mutable.ListBuffer[V]): List[V] =
      remaining match {
        case Nil => acc.toList
        case u :: Nil => acc.addOne(u); acc.toList
        case u :: v :: rest =>
          shortcuts.get((u, v)) match {
            case Some(via) => expand(u :: via :: v :: rest, acc)
            case None      => acc.addOne(u); expand(v :: rest, acc)
          }
      }
    if (path.isEmpty) List.empty
    else expand(path, mutable.ListBuffer.empty)
  }
}
