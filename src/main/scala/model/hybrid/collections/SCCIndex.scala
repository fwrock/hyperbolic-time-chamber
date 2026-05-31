package org.interscity.htc.model.hybrid.collections

import java.util.BitSet

/** SCC (Strongly Connected Components) index with condensation DAG reachability.
  *
  * Provides O(1) directed reachability queries:
  *   canReach(u, v) == true  ⟺  a directed path u → v exists in the graph
  *
  * == Correctness ==
  * By the SCC condensation theorem, a directed path A→B exists in G if and only if
  * scc(A) can reach scc(B) in the condensation DAG.  This gives zero false positives
  * and zero false negatives.
  *
  * == Memory ==
  * For a graph with k SCCs: k × k bits for the reachability matrix.
  * On São Paulo (~480 K nodes, ~95% in the giant SCC) k ≈ 25 K → ~78 MB resident.
  *
  * == Usage ==
  * {{{
  *   val idx = SCCIndex.fromCompactGraph(cg)
  *   if (!idx.canReach(srcInt, dstInt)) return None  // skip A* entirely
  * }}}
  */
final class SCCIndex private (
  val sccId: Array[Int],
  val numSCCs: Int,
  private val reachable: Array[BitSet]
) {

  /** True iff there is a directed path from srcNodeIdx to dstNodeIdx in the graph. */
  @inline def canReach(srcNodeIdx: Int, dstNodeIdx: Int): Boolean = {
    val a = sccId(srcNodeIdx)
    val b = sccId(dstNodeIdx)
    a == b || reachable(a).get(b)
  }
}

object SCCIndex {

  /** Build an SCCIndex from a CompactGraph.
    *
    * Algorithm: Kosaraju's (two iterative DFS passes) + BFS reachability in condensation DAG.
    * Time  O(V + E + k²) where k = number of SCCs (typically k ≪ V).
    * Space O(V + E + k²/8) bytes.
    */
  def fromCompactGraph(cg: CompactGraph): SCCIndex = {
    val n = cg.n
    val m = cg.edgeCount

    val stackNode = new Array[Int](n)
    val stackEdge = new Array[Int](n)

    val finishOrder = new Array[Int](n)
    var finishTop   = -1
    val visited1    = new BitSet(n)

    var seed = 0
    while (seed < n) {
      if (!visited1.get(seed)) {
        visited1.set(seed)
        var stackTop = 0
        stackNode(0) = seed
        stackEdge(0) = cg.rowPtrAt(seed)

        while (stackTop >= 0) {
          val u   = stackNode(stackTop)
          val pos = stackEdge(stackTop)
          val end = cg.rowPtrAt(u + 1)
          if (pos == end) {
            stackTop  -= 1
            finishTop += 1
            finishOrder(finishTop) = u
          } else {
            stackEdge(stackTop) += 1
            val v = cg.colIdxAt(pos)
            if (!visited1.get(v)) {
              visited1.set(v)
              stackTop += 1
              stackNode(stackTop) = v
              stackEdge(stackTop) = cg.rowPtrAt(v)
            }
          }
        }
      }
      seed += 1
    }

    val transRowPtr = new Array[Int](n + 1)
    var e = 0
    while (e < m) {
      transRowPtr(cg.colIdxAt(e) + 1) += 1
      e += 1
    }
    var i = 0
    while (i < n) { transRowPtr(i + 1) += transRowPtr(i); i += 1 }

    val transColIdx = new Array[Int](m)
    val cursor      = transRowPtr.clone()
    var u = 0
    while (u < n) {
      var ep    = cg.rowPtrAt(u)
      val epEnd = cg.rowPtrAt(u + 1)
      while (ep < epEnd) {
        val v = cg.colIdxAt(ep)
        transColIdx(cursor(v)) = u
        cursor(v) += 1
        ep += 1
      }
      u += 1
    }

    val sccId    = new Array[Int](n)
    var sccCount = 0
    val visited2 = new BitSet(n)

    var fi = finishTop
    while (fi >= 0) {
      val s = finishOrder(fi)
      fi -= 1
      if (!visited2.get(s)) {
        visited2.set(s)
        sccId(s) = sccCount
        var stackTop = 0
        stackNode(0) = s
        stackEdge(0) = transRowPtr(s)

        while (stackTop >= 0) {
          val u   = stackNode(stackTop)
          val pos = stackEdge(stackTop)
          val end = transRowPtr(u + 1)
          if (pos == end) {
            stackTop -= 1
          } else {
            stackEdge(stackTop) += 1
            val v = transColIdx(pos)
            if (!visited2.get(v)) {
              visited2.set(v)
              sccId(v) = sccCount
              stackTop += 1
              stackNode(stackTop) = v
              stackEdge(stackTop) = transRowPtr(v)
            }
          }
        }
        sccCount += 1
      }
    }
    val condensEdges = new java.util.HashSet[Long](sccCount * 4)
    u = 0
    while (u < n) {
      val a     = sccId(u)
      var ep    = cg.rowPtrAt(u)
      val epEnd = cg.rowPtrAt(u + 1)
      while (ep < epEnd) {
        val b = sccId(cg.colIdxAt(ep))
        if (a != b) condensEdges.add((a.toLong << 32) | (b.toLong & 0xFFFFFFFFL))
        ep += 1
      }
      u += 1
    }

    val condDegree = new Array[Int](sccCount)
    val it1        = condensEdges.iterator()
    while (it1.hasNext) { condDegree((it1.next() >>> 32).toInt) += 1 }

    val condRowPtr = new Array[Int](sccCount + 1)
    i = 0
    while (i < sccCount) { condRowPtr(i + 1) = condRowPtr(i) + condDegree(i); i += 1 }

    val condColIdx = new Array[Int](condensEdges.size())
    val condCursor = condRowPtr.clone()
    val it2        = condensEdges.iterator()
    while (it2.hasNext) {
      val packed     = it2.next()
      val a          = (packed >>> 32).toInt
      val b          = (packed & 0xFFFFFFFFL).toInt
      condColIdx(condCursor(a)) = b
      condCursor(a) += 1
    }

    val reachable = new Array[BitSet](sccCount)
    val bfsQueue  = new java.util.ArrayDeque[Int](sccCount)
    i = 0
    while (i < sccCount) {
      val bs = new BitSet(sccCount)
      bs.set(i)
      bfsQueue.clear()
      bfsQueue.add(i)
      while (!bfsQueue.isEmpty) {
        val a     = bfsQueue.poll()
        var ep    = condRowPtr(a)
        val epEnd = condRowPtr(a + 1)
        while (ep < epEnd) {
          val b = condColIdx(ep)
          if (!bs.get(b)) { bs.set(b); bfsQueue.add(b) }
          ep += 1
        }
      }
      reachable(i) = bs
      i += 1
    }

    new SCCIndex(sccId, sccCount, reachable)
  }
}
