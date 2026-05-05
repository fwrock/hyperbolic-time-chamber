package org.interscity.htc.model.hybrid.collections.graph

import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics
import org.interscity.htc.model.hybrid.collections.Graph

import scala.collection.mutable

/** ALT (A* + Landmarks + Triangle inequality) precomputed index.
  *
  * ==Algorithm==
  * For each of the k selected landmarks L, two distance maps are precomputed:
  *   - `distFrom(L)(v)` — shortest distance from landmark L to node v (forward Dijkstra)
  *   - `distTo(L)(v)` — shortest distance from node v to landmark L (Dijkstra on reversed graph)
  *
  * The admissible A* heuristic for the query (v → target) is:
  * {{{
  *   h(v, t) = max over L of max(distFrom(L)(t) - distFrom(L)(v),
  *                               distTo(L)(v)   - distTo(L)(t))
  * }}}
  *
  * Both expressions are non-negative lower bounds by the triangle inequality on shortest-path
  * distances (assuming non-negative edge weights), making the heuristic admissible and
  * consistent for use with A*.
  *
  * ==Landmark selection==
  * Uses the ''farthest-first'' (max-cover) strategy:
  *   1. Seed = middle node of the node list (deterministic, reasonable spatial coverage).
  *   2. Run Dijkstra from seed; maintain `minDist(v)` = min forward distance from any existing
  *      landmark.
  *   3. Repeatedly pick the node with the largest `minDist` as the next landmark and update
  *      `minDist`.
  *
  * Complexity: O(k × Dijkstra) = O(k × (E + V log V)) for preprocessing.
  *
  * @tparam V Vertex type.
  * @tparam W Weight type (must be Numeric).
  * @tparam L Edge label type.
  */
class LandmarkIndex[V, W, L] private (
  private val distFrom: IndexedSeq[Map[V, Double]],
  private val distTo:   IndexedSeq[Map[V, Double]]
) {

  /** ALT heuristic: admissible lower bound on d(v, target). */
  def heuristic(v: V, target: V): Double = {
    var best = 0.0
    var i    = 0
    while (i < distFrom.length) {
      val df  = distFrom(i)
      val dt  = distTo(i)
      val dft = df.getOrElse(target, Double.PositiveInfinity)
      val dfv = df.getOrElse(v, Double.PositiveInfinity)
      val dtv = dt.getOrElse(v, Double.PositiveInfinity)
      val dtt = dt.getOrElse(target, Double.PositiveInfinity)

      // Forward landmark: L → target is "easier" than L → v → target
      val h1 = dft - dfv
      // Backward landmark: v → L is "harder" than target → L
      val h2 = dtv - dtt

      if (h1 > best && h1 < Double.PositiveInfinity) best = h1
      if (h2 > best && h2 < Double.PositiveInfinity) best = h2
      i += 1
    }
    best
  }
}

object LandmarkIndex {

  /** Build an ALT index using farthest-first landmark selection.
    *
    * @param graph The directed city graph.
    * @param k     Number of landmarks (default: 16; configurable via htc.mobility.landmark-count).
    * @return      A precomputed [[LandmarkIndex]] ready for heuristic queries.
    */
  def build[V, W, L](graph: Graph[V, W, L], k: Int = 16)(implicit
    num: Numeric[W]
  ): LandmarkIndex[V, W, L] = {
    val nodes = graph.vertices.toIndexedSeq
    if (nodes.isEmpty || k <= 0) return new LandmarkIndex(IndexedSeq.empty, IndexedSeq.empty)

    val effectiveK  = math.min(k, nodes.size)
    val selected    = mutable.ArrayBuffer[V]()
    val selectedSet = mutable.HashSet[V]()

    // Seed: middle node for reasonable spatial spread (deterministic)
    val seed = nodes(nodes.size / 2)
    selected    += seed
    selectedSet += seed

    // minDist(v): min forward distance from any selected landmark to v
    val minDist = mutable.Map[V, Double]().withDefaultValue(Double.PositiveInfinity)
    graph.dijkstraAllDistances(seed).foreach { case (v, d) => minDist(v) = d }

    println(s"[LandmarkIndex] Selecting $effectiveK landmarks via farthest-first...")

    while (selected.size < effectiveK) {
      // Candidate: farthest reachable node not yet a landmark
      val candidate = nodes
        .iterator
        .filter(v => !selectedSet.contains(v) && minDist(v).isFinite)
        .maxByOption(v => minDist(v))
        .orElse(nodes.find(v => !selectedSet.contains(v))) // fallback: any unreached node

      candidate match {
        case None =>
          // No more nodes available
          selected.size // break out
          return buildIndex(graph, selected.toIndexedSeq)
        case Some(next) =>
          selected    += next
          selectedSet += next
          graph.dijkstraAllDistances(next).foreach {
            case (v, d) => if (d < minDist(v)) minDist(v) = d
          }
      }
    }

    buildIndex(graph, selected.toIndexedSeq)
  }

  private def buildIndex[V, W, L](
    graph: Graph[V, W, L],
    landmarks: IndexedSeq[V]
  )(implicit num: Numeric[W]): LandmarkIndex[V, W, L] = {
    println(s"[LandmarkIndex] Precomputing distances for ${landmarks.size} landmarks...")
    val t0  = System.nanoTime()
    val rev = graph.reversed

    val distFrom = landmarks.map { l => graph.dijkstraAllDistances(l) }
    val distTo   = landmarks.map { l => rev.dijkstraAllDistances(l) }

    val elapsed = (System.nanoTime() - t0) / 1e9
    GPSMetrics.altPrecomputationDuration.set(elapsed)
    GPSMetrics.altLandmarkCount.set(landmarks.size.toDouble)
    println(f"[LandmarkIndex] Done in ${elapsed}%.2fs (${landmarks.size} landmarks).")
    new LandmarkIndex(distFrom, distTo)
  }
}
