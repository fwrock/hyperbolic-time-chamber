package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

import java.util.concurrent.{ Callable, ExecutionException, Executors, Future, ThreadFactory }
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** How the next landmark is chosen.
  *
  * `Weighted` is farthest-first by travel cost and picks the same landmarks as the Map-based
  * [[org.interscity.htc.model.hybrid.collections.graph.LandmarkIndex]]; its selection needs the
  * previous forward tree, so only the backward trees overlap with it. `Hops` is farthest-first by
  * edge count, which depends on the topology alone, so every tree runs in parallel from the start.
  */
enum LandmarkSelection {
  case Weighted, Hops
}

object LandmarkSelection {
  def parse(value: String): LandmarkSelection = value.trim.toLowerCase match {
    case "weighted" => Weighted
    case "hops"     => Hops
    case other =>
      throw new IllegalArgumentException(s"Unknown landmark selection '$other'; expected 'weighted' or 'hops'")
  }
}

/** Builds the ALT landmark index directly on the CSR [[CompactGraph]].
  *
  * Shortest-path trees use an indexed binary heap over `Int` node indices, the backward graph is a
  * CSR transpose, and distances land in the arrays [[CompactLandmarkIndex]] reads, so no boxed
  * `Map`, reversed `Graph` or translation pass is needed.
  *
  * The result does not depend on the thread count: selection is deterministic and every tree is
  * computed independently.
  */
object CompactAltBuilder {

  def defaultThreads: Int = math.max(1, math.min(4, Runtime.getRuntime.availableProcessors()))

  def build(
    graph: CompactGraph,
    landmarks: Int,
    selection: LandmarkSelection = LandmarkSelection.Weighted,
    threads: Int = defaultThreads
  ): CompactLandmarkIndex = {
    val n = graph.n
    if (n == 0 || landmarks <= 0) return CompactLandmarkIndex.fromArrays(Array.empty, Array.empty)

    val rowPtr = graph.rowPtrArray
    val col    = graph.colIdxArray
    val wt     = graph.edgeWeightArray
    requireNonNegative(wt)
    val (rrowPtr, rcol, rwt) = transpose(n, rowPtr, col, wt)

    val target = math.min(landmarks, n)
    require(2L * target * n <= Int.MaxValue, s"landmark index of $target landmarks × $n nodes does not fit one array")
    println(s"[CompactAlt] Building $target landmarks on $n nodes (selection=$selection, threads=$threads)...")
    val t0   = System.nanoTime()
    val pool = Executors.newFixedThreadPool(math.max(1, threads), daemonThreads)
    try {
      val data = new Array[Double](2 * target * n)
      val chosen = selection match {
        case LandmarkSelection.Weighted => weighted(n, rowPtr, col, wt, rrowPtr, rcol, rwt, target, data, pool)
        case LandmarkSelection.Hops     => hops(n, rowPtr, col, wt, rrowPtr, rcol, rwt, target, data, pool)
      }
      // Selection returns an unselected node whenever one exists and target <= n, so this cannot fail.
      require(chosen == target, s"selected $chosen of $target landmarks")
      val elapsed = (System.nanoTime() - t0) / 1e9
      GPSMetrics.altPrecomputationDuration.set(elapsed)
      GPSMetrics.altLandmarkCount.set(target.toDouble)
      println(f"[CompactAlt] Done in $elapsed%.2fs ($target landmarks).")
      CompactLandmarkIndex.fromInterleaved(target, n, data)
    } finally pool.shutdown()
  }

  /** Interleaves one tree into the node-major array as it is produced, so trees are not all held at once. */
  private def copyTree(tree: Array[Double], data: Array[Double], n: Int, stride: Int, offset: Int): Unit = {
    var v = 0
    while (v < n) {
      data(v * stride + offset) = tree(v)
      v += 1
    }
  }

  private def weighted(
    n: Int,
    rowPtr: Array[Int], col: Array[Int], wt: Array[Double],
    rrowPtr: Array[Int], rcol: Array[Int], rwt: Array[Double],
    target: Int,
    data: Array[Double],
    pool: java.util.concurrent.ExecutorService
  ): Int = {
    val stride     = 2 * target
    val isSelected = new Array[Boolean](n)
    val minDist    = new Array[Double](n)
    java.util.Arrays.fill(minDist, Double.PositiveInfinity)
    val backward = mutable.ArrayBuffer.empty[Future[Array[Double]]]

    var chosen = 0
    var next   = n / 2
    while (next >= 0 && chosen < target) {
      val landmark = next
      isSelected(landmark) = true
      backward += pool.submit(task(shortestTree(n, rrowPtr, rcol, rwt, landmark)))
      val tree = shortestTree(n, rowPtr, col, wt, landmark)
      copyTree(tree, data, n, stride, 2 * chosen)
      var v = 0
      while (v < n) {
        if (tree(v) < minDist(v)) minDist(v) = tree(v)
        v += 1
      }
      chosen += 1
      next = if (chosen < target) farthestByCost(n, minDist, isSelected) else -1
    }
    backward.zipWithIndex.foreach { case (future, i) => copyTree(await(future), data, n, stride, 2 * i + 1) }
    chosen
  }

  private def hops(
    n: Int,
    rowPtr: Array[Int], col: Array[Int], wt: Array[Double],
    rrowPtr: Array[Int], rcol: Array[Int], rwt: Array[Double],
    target: Int,
    data: Array[Double],
    pool: java.util.concurrent.ExecutorService
  ): Int = {
    val stride     = 2 * target
    val isSelected = new Array[Boolean](n)
    val selected   = mutable.ArrayBuffer.empty[Int]
    val forward    = mutable.ArrayBuffer.empty[Future[Array[Double]]]
    val backward   = mutable.ArrayBuffer.empty[Future[Array[Double]]]

    var next = n / 2
    while (next >= 0 && selected.size < target) {
      val landmark = next
      selected += landmark
      isSelected(landmark) = true
      forward += pool.submit(task(shortestTree(n, rowPtr, col, wt, landmark)))
      backward += pool.submit(task(shortestTree(n, rrowPtr, rcol, rwt, landmark)))
      next = if (selected.size < target) farthestByHops(n, rowPtr, col, selected, isSelected) else -1
    }
    forward.zipWithIndex.foreach { case (future, i) => copyTree(await(future), data, n, stride, 2 * i) }
    backward.zipWithIndex.foreach { case (future, i) => copyTree(await(future), data, n, stride, 2 * i + 1) }
    selected.size
  }

  /** First node with the largest finite distance to the selected set; any unselected node when
    * none is reachable, so a disconnected graph still yields the requested landmark count.
    */
  private def farthestByCost(n: Int, minDist: Array[Double], isSelected: Array[Boolean]): Int = {
    var best    = -1
    var bestVal = Double.NegativeInfinity
    var firstUnselected = -1
    var v = 0
    while (v < n) {
      if (!isSelected(v)) {
        if (firstUnselected < 0) firstUnselected = v
        val d = minDist(v)
        if (d < Double.PositiveInfinity && d > bestVal) {
          best = v
          bestVal = d
        }
      }
      v += 1
    }
    if (best >= 0) best else firstUnselected
  }

  /** Last node a multi-source BFS from the selected set dequeues, i.e. one of the farthest in hops. */
  private def farthestByHops(
    n: Int,
    rowPtr: Array[Int],
    col: Array[Int],
    selected: mutable.ArrayBuffer[Int],
    isSelected: Array[Boolean]
  ): Int = {
    val seen  = new Array[Boolean](n)
    val queue = new Array[Int](n)
    var head  = 0
    var tail  = 0
    selected.foreach { s =>
      seen(s) = true
      queue(tail) = s
      tail += 1
    }
    var last = -1
    while (head < tail) {
      val u = queue(head)
      head += 1
      last = u
      var e = rowPtr(u)
      val end = rowPtr(u + 1)
      while (e < end) {
        val v = col(e)
        if (!seen(v)) {
          seen(v) = true
          queue(tail) = v
          tail += 1
        }
        e += 1
      }
    }
    if (last >= 0 && !isSelected(last)) last
    else {
      var v = 0
      while (v < n && isSelected(v)) v += 1
      if (v < n) v else -1
    }
  }

  private[collections] def shortestTree(
    n: Int,
    rowPtr: Array[Int],
    col: Array[Int],
    wt: Array[Double],
    source: Int
  ): Array[Double] = {
    val dist = new Array[Double](n)
    java.util.Arrays.fill(dist, Double.PositiveInfinity)
    dist(source) = 0.0
    val heap = new IndexedMinHeap(n, dist)
    heap.insert(source)
    while (!heap.isEmpty) {
      val u  = heap.poll()
      val du = dist(u)
      var e  = rowPtr(u)
      val end = rowPtr(u + 1)
      while (e < end) {
        val v   = col(e)
        val nd  = du + wt(e)
        val old = dist(v)
        if (nd < old) {
          dist(v) = nd
          if (old == Double.PositiveInfinity) heap.insert(v) else heap.decreaseKey(v)
        }
        e += 1
      }
    }
    dist
  }

  private[collections] def transpose(
    n: Int,
    rowPtr: Array[Int],
    col: Array[Int],
    wt: Array[Double]
  ): (Array[Int], Array[Int], Array[Double]) = {
    val m    = col.length
    val rptr = new Array[Int](n + 1)
    var e = 0
    while (e < m) {
      rptr(col(e) + 1) += 1
      e += 1
    }
    var i = 0
    while (i < n) {
      rptr(i + 1) += rptr(i)
      i += 1
    }
    val rcol   = new Array[Int](m)
    val rwt    = new Array[Double](m)
    val cursor = rptr.clone()
    var u = 0
    while (u < n) {
      var k = rowPtr(u)
      val end = rowPtr(u + 1)
      while (k < end) {
        val v = col(k)
        val p = cursor(v)
        rcol(p) = u
        rwt(p) = wt(k)
        cursor(v) = p + 1
        k += 1
      }
      u += 1
    }
    (rptr, rcol, rwt)
  }

  private def requireNonNegative(wt: Array[Double]): Unit = {
    var i = 0
    while (i < wt.length) {
      require(wt(i) >= 0.0, s"landmark distances need non-negative edge weights; edge $i has ${wt(i)}")
      i += 1
    }
  }

  private def task[T](body: => T): Callable[T] = () => body

  private def await(future: Future[Array[Double]]): Array[Double] =
    try future.get()
    catch { case e: ExecutionException => throw e.getCause }

  private val threadIds = new AtomicInteger()

  private val daemonThreads: ThreadFactory = runnable => {
    val thread = new Thread(runnable, s"alt-landmark-${threadIds.incrementAndGet()}")
    thread.setDaemon(true)
    thread
  }

  /** Min-heap of node indices ordered by `key`, supporting decrease-key without duplicates. */
  private final class IndexedMinHeap(capacity: Int, key: Array[Double]) {
    private val heap = new Array[Int](capacity)
    private val pos  = new Array[Int](capacity)
    private var size = 0
    java.util.Arrays.fill(pos, -1)

    def isEmpty: Boolean = size == 0

    def insert(v: Int): Unit = {
      heap(size) = v
      pos(v) = size
      size += 1
      siftUp(size - 1)
    }

    def decreaseKey(v: Int): Unit = siftUp(pos(v))

    def poll(): Int = {
      val top = heap(0)
      size -= 1
      pos(top) = -1
      if (size > 0) {
        val last = heap(size)
        heap(0) = last
        pos(last) = 0
        siftDown(0)
      }
      top
    }

    private def siftUp(start: Int): Unit = {
      var i  = start
      val v  = heap(i)
      val kv = key(v)
      var moving = i > 0
      while (moving) {
        val parent = (i - 1) >>> 1
        val pv     = heap(parent)
        if (key(pv) <= kv) moving = false
        else {
          heap(i) = pv
          pos(pv) = i
          i = parent
          moving = i > 0
        }
      }
      heap(i) = v
      pos(v) = i
    }

    private def siftDown(start: Int): Unit = {
      var i  = start
      val v  = heap(i)
      val kv = key(v)
      var moving = true
      while (moving) {
        val left = 2 * i + 1
        if (left >= size) moving = false
        else {
          var child = left
          if (left + 1 < size && key(heap(left + 1)) < key(heap(left))) child = left + 1
          val cv = heap(child)
          if (key(cv) >= kv) moving = false
          else {
            heap(i) = cv
            pos(cv) = i
            i = child
          }
        }
      }
      heap(i) = v
      pos(v) = i
    }
  }
}
