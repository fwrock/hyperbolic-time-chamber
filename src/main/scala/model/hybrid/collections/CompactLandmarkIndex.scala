package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.model.hybrid.collections.graph.LandmarkIndex
import org.interscity.htc.model.hybrid.entity.state.model.NodeGraph

/** ALT landmark index stored as flat primitive arrays for zero-allocation heuristic evaluation.
  *
  * ==Motivation==
  * [[LandmarkIndex]] stores distances as `IndexedSeq[Map[V, Double]]`. Each `heuristic` call
  * does 4 × k HashMap lookups with a `NodeGraph` key (String hashCode). For a typical A* search
  * with 10K expansions × 5 neighbours × 4 × 16 lookups = 3.2M HashMap calls — the dominant
  * cost for long routes.
  *
  * This class pre-translates the maps to `Array[Array[Double]]` indexed by the same Int node
  * index used by [[CompactGraph]]. The heuristic becomes pure array arithmetic: 4 array reads
  * and 4 arithmetic ops per landmark, no object allocation, no HashMap, no boxing.
  *
  * ==Memory==
  * 2 × k × n × 8 bytes. For k=16, n=488K (Toulouse): ~125 MB — loaded once at startup.
  *
  * @param distFrom  `distFrom(i)(nodeIdx)` = forward distance from landmark i to node `nodeIdx`.
  * @param distTo    `distTo(i)(nodeIdx)`   = backward distance from landmark i to node `nodeIdx`.
  */
final class CompactLandmarkIndex private (
  private val distFrom: Array[Array[Double]], // [landmark][nodeIdx]
  private val distTo:   Array[Array[Double]]  // [landmark][nodeIdx]
) {

  val k: Int = distFrom.length

  /** Admissible ALT lower bound on d(u, target).
    *
    * Each landmark contributes two triangle-inequality bounds:
    * {{{
    *   h1 = distFrom(L)(target) - distFrom(L)(u)   // forward landmark
    *   h2 = distTo(L)(u)        - distTo(L)(target) // backward landmark
    * }}}
    * Returns `max(0, max over L of max(h1, h2))`.
    *
    * Pure array arithmetic — no objects, no HashMap, no allocation.
    */
  @inline def heuristic(u: Int, target: Int): Double = {
    var best = 0.0
    var i    = 0
    while (i < k) {
      val dft = distFrom(i)(target)
      val dfu = distFrom(i)(u)
      val dtu = distTo(i)(u)
      val dtt = distTo(i)(target)

      // Forward landmark: L→target cheaper than L→u→target
      val h1 = dft - dfu
      // Backward landmark: u→L more expensive than target→L
      val h2 = dtu - dtt

      if (h1 > best && h1 < Double.PositiveInfinity) best = h1
      if (h2 > best && h2 < Double.PositiveInfinity) best = h2
      i += 1
    }
    best
  }
}

object CompactLandmarkIndex {

  /** Build a [[CompactLandmarkIndex]] from an existing [[LandmarkIndex]].
    *
    * Translates `Map[NodeGraph, Double]` distance maps to flat `Array[Array[Double]]` using
    * [[CompactGraph.nodeIndex]] as the Int-index mapping. Nodes not present in the landmark
    * maps (unreachable) retain `PositiveInfinity`.
    *
    * @param index         Pre-computed [[LandmarkIndex]] (from `LandmarkIndex.build`).
    * @param nodeIndex     Int-index mapping from [[CompactGraph.nodeIndex]].
    * @param nodeIdOf      Extracts the String ID from a [[NodeGraph]] (for index lookup).
    * @param n             Total node count from [[CompactGraph.n]].
    */
  def fromLandmarkIndex(
    index:     LandmarkIndex[NodeGraph, Double, ?],
    nodeIndex: java.util.HashMap[String, Int],
    nodeIdOf:  NodeGraph => String,
    n:         Int
  ): CompactLandmarkIndex = {
    val k = index.distFrom.length
    println(s"[CompactLandmarkIndex] Translating $k landmarks × $n nodes to arrays...")
    val t0 = System.currentTimeMillis()

    val compactFrom = Array.ofDim[Double](k, n)
    val compactTo   = Array.ofDim[Double](k, n)

    // Default: unreachable = PositiveInfinity
    var i = 0
    while (i < k) {
      java.util.Arrays.fill(compactFrom(i), Double.PositiveInfinity)
      java.util.Arrays.fill(compactTo(i), Double.PositiveInfinity)
      i += 1
    }

    // Fill from existing maps using CompactGraph Int indices
    i = 0
    while (i < k) {
      index.distFrom(i).foreach { case (ng, dist) =>
        val idx = nodeIndex.getOrDefault(nodeIdOf(ng), -1)
        if (idx >= 0) compactFrom(i)(idx) = dist
      }
      index.distTo(i).foreach { case (ng, dist) =>
        val idx = nodeIndex.getOrDefault(nodeIdOf(ng), -1)
        if (idx >= 0) compactTo(i)(idx) = dist
      }
      i += 1
    }

    println(s"[CompactLandmarkIndex] Done in ${System.currentTimeMillis() - t0}ms")
    new CompactLandmarkIndex(compactFrom, compactTo)
  }
}
