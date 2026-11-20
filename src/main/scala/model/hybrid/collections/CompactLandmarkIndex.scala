package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.model.hybrid.collections.graph.LandmarkIndex
import org.interscity.htc.model.hybrid.entity.state.model.NodeGraph

/** ALT landmark index stored as one flat primitive array for zero-allocation heuristic evaluation.
  *
  * ==Motivation==
  * [[LandmarkIndex]] stores distances as `IndexedSeq[Map[V, Double]]`. Each `heuristic` call
  * does 4 × k HashMap lookups with a `NodeGraph` key (String hashCode). For a typical A* search
  * with 10K expansions × 5 neighbours × 4 × 16 lookups = 3.2M HashMap calls — the dominant
  * cost for long routes.
  *
  * This class holds the distances as primitives indexed by the same Int node index used by
  * [[CompactGraph]]. The heuristic becomes pure array arithmetic: 4 array reads and 4
  * arithmetic ops per landmark, no object allocation, no HashMap, no boxing.
  *
  * ==Layout==
  * Node-major: the 2k values of one node are contiguous (`data(node * 2k + 2i)` is the distance
  * from landmark i to the node, `+ 1` the distance from the node to landmark i). A heuristic
  * call reads the rows of two nodes, 2 × 2k × 8 bytes in a few cache lines, instead of 4k
  * positions scattered over 4k arrays when each landmark owns its own array.
  *
  * ==Memory==
  * 2 × k × n × 8 bytes. For k=16, n=488K (Toulouse): ~125 MB — loaded once at startup.
  */
final class CompactLandmarkIndex private (
  val k: Int,
  private val data: Array[Double]
) {

  private val stride: Int = 2 * k

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
    val bu   = u * stride
    val bt   = target * stride
    var i    = 0
    while (i < stride) {
      val dfu = data(bu + i)
      val dtu = data(bu + i + 1)
      val dft = data(bt + i)
      val dtt = data(bt + i + 1)

      // Forward landmark: L→target cheaper than L→u→target
      val h1 = dft - dfu
      // Backward landmark: u→L more expensive than target→L
      val h2 = dtu - dtt

      if (h1 > best && h1 < Double.PositiveInfinity) best = h1
      if (h2 > best && h2 < Double.PositiveInfinity) best = h2
      i += 2
    }
    best
  }

  private[collections] def distanceFrom(landmark: Int, node: Int): Double = data(node * stride + 2 * landmark)
  private[collections] def distanceTo(landmark: Int, node: Int): Double   = data(node * stride + 2 * landmark + 1)
}

object CompactLandmarkIndex {

  /** Wraps node-major data produced by [[CompactAltBuilder]]: `data(node * 2k + 2i)` is the
    * distance from landmark i to the node and `+ 1` the distance from the node to landmark i.
    */
  private[collections] def fromInterleaved(k: Int, nodes: Int, data: Array[Double]): CompactLandmarkIndex = {
    require(k >= 0 && nodes >= 0 && data.length.toLong == 2L * k * nodes, "invalid landmark dimensions")
    new CompactLandmarkIndex(k, data)
  }

  /** Interleaves distance rows that are indexed by [[CompactGraph]] node index, one row per landmark. */
  private[collections] def fromArrays(
    distFrom: Array[Array[Double]],
    distTo:   Array[Array[Double]]
  ): CompactLandmarkIndex = {
    require(distFrom.length == distTo.length, "forward and backward landmark counts differ")
    val k     = distFrom.length
    val nodes = if (k == 0) 0 else distFrom(0).length
    val data  = new Array[Double](2 * k * nodes)
    var i = 0
    while (i < k) {
      require(distFrom(i).length == nodes && distTo(i).length == nodes, "landmark rows differ in length")
      var v = 0
      while (v < nodes) {
        data(v * 2 * k + 2 * i)     = distFrom(i)(v)
        data(v * 2 * k + 2 * i + 1) = distTo(i)(v)
        v += 1
      }
      i += 1
    }
    new CompactLandmarkIndex(k, data)
  }

  /** On-disk layout is unchanged (all forward rows, then all backward rows), so entries written by
    * earlier builds stay readable.
    */
  def codec(nodes: Int, maxLandmarks: Int): org.interscity.htc.core.cache.CalculationCodec[CompactLandmarkIndex] =
    new org.interscity.htc.core.cache.CalculationCodec[CompactLandmarkIndex] {
      def read(in: java.io.DataInputStream): CompactLandmarkIndex = {
        val k = in.readInt()
        require(k >= 0 && k <= math.max(0, maxLandmarks) && in.readInt() == nodes, "invalid landmark dimensions")
        val data = new Array[Double](2 * k * nodes)
        var side = 0
        while (side < 2) {
          var i = 0
          while (i < k) {
            var v = 0
            while (v < nodes) {
              val value = in.readDouble()
              require(!value.isNaN && value >= 0, "invalid landmark distance")
              data(v * 2 * k + 2 * i + side) = value
              v += 1
            }
            i += 1
          }
          side += 1
        }
        new CompactLandmarkIndex(k, data)
      }
      def write(index: CompactLandmarkIndex, out: java.io.DataOutputStream): Unit = {
        out.writeInt(index.k); out.writeInt(nodes)
        var i = 0
        while (i < index.k) {
          var v = 0
          while (v < nodes) { out.writeDouble(index.distanceFrom(i, v)); v += 1 }
          i += 1
        }
        i = 0
        while (i < index.k) {
          var v = 0
          while (v < nodes) { out.writeDouble(index.distanceTo(i, v)); v += 1 }
          i += 1
        }
      }
    }

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
    nodeIndex: java.util.HashMap[Long, Int],
    nodeIdOf:  NodeGraph => Long,
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
    fromArrays(compactFrom, compactTo)
  }
}
