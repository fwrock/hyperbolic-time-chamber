package org.interscity.htc.model.mobility.collections

import org.interscity.htc.model.mobility.collections.graph.LandmarkIndex
import org.interscity.htc.model.mobility.entity.state.model.{EdgeGraph, NodeGraph}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.util.Random

class CompactAltBuilderSpec extends AnyFlatSpec with Matchers {
  private val Inf = Double.PositiveInfinity

  private def load(nodeCount: Int, edges: Seq[(Int, Int, Double)]) = {
    val file  = Files.createTempFile("alt-builder-graph", ".json")
    val nodes = (1 to nodeCount).map(i => s"""{"id":$i,"resourceId":"n","classType":"Node","latitude":0,"longitude":0}""")
    val links = edges.zipWithIndex.map { case ((s, t, w), i) =>
      s"""{"source_id":$s,"target_id":$t,"weight":$w,"label":{"id":${i + 1},"resourceId":"l","classType":"Link","length":$w}}"""
    }
    Files.writeString(file, s"""{"nodes":[${nodes.mkString(",")}],"edges":[${links.mkString(",")}],"directed":true}""")
    try Graph.loadFromJsonFile[NodeGraph, Long, Double, EdgeGraph](file.toString, _.id, _.id, 0.0).get
    finally Files.delete(file)
  }

  // Integer weights keep every sum exact, so equality does not depend on which equal-cost path wins.
  private def randomEdges(rnd: Random, nodes: Range, count: Int): Seq[(Int, Int, Double)] = {
    val seen = scala.collection.mutable.Set.empty[(Int, Int)]
    Iterator
      .continually((nodes.start + rnd.nextInt(nodes.size), nodes.start + rnd.nextInt(nodes.size)))
      .filter { case (s, t) => s != t }
      .filter(seen.add)
      .take(count)
      .map { case (s, t) => (s, t, (1 + rnd.nextInt(20)).toDouble) }
      .toSeq
  }

  private lazy val connected    = load(120, randomEdges(new Random(7), 1 to 120, 360))
  private lazy val connectedCg  = CompactGraph.fromLoaded(connected)
  private lazy val twoBlocks = {
    val rnd = new Random(11)
    load(120, randomEdges(rnd, 1 to 60, 180) ++ randomEdges(rnd, 61 to 120, 180))
  }
  private lazy val twoBlocksCg = CompactGraph.fromLoaded(twoBlocks)

  private def legacy(loaded: LoadedGraphData[NodeGraph, Long, Double, EdgeGraph], cg: CompactGraph, k: Int) =
    CompactLandmarkIndex.fromLandmarkIndex(LandmarkIndex.build(loaded.graph, k), cg.nodeIndex, _.id, cg.n)

  private def sameHeuristics(a: CompactLandmarkIndex, b: CompactLandmarkIndex, n: Int): Unit = {
    a.k shouldBe b.k
    for (u <- 0 until n; v <- 0 until n) a.heuristic(u, v) shouldBe b.heuristic(u, v)
  }

  "The shortest-path trees" should "match the Map-based Dijkstra from every source, forward and backward" in {
    val cg = connectedCg
    val (rptr, rcol, rwt) = CompactAltBuilder.transpose(cg.n, cg.rowPtrArray, cg.colIdxArray, cg.edgeWeightArray)
    val reversed = connected.graph.reversed
    for (s <- 0 until cg.n) {
      val forward  = CompactAltBuilder.shortestTree(cg.n, cg.rowPtrArray, cg.colIdxArray, cg.edgeWeightArray, s)
      val backward = CompactAltBuilder.shortestTree(cg.n, rptr, rcol, rwt, s)
      val expectedForward  = connected.graph.dijkstraAllDistances(cg.nodeGraphs(s))
      val expectedBackward = reversed.dijkstraAllDistances(cg.nodeGraphs(s))
      for (v <- 0 until cg.n) {
        forward(v) shouldBe expectedForward.getOrElse(cg.nodeGraphs(v), Inf)
        backward(v) shouldBe expectedBackward.getOrElse(cg.nodeGraphs(v), Inf)
      }
    }
  }

  "Weighted selection" should "reproduce the legacy Map-based index exactly" in {
    for (k <- Seq(1, 4, 16))
      sameHeuristics(CompactAltBuilder.build(connectedCg, k, LandmarkSelection.Weighted, 3), legacy(connected, connectedCg, k), connectedCg.n)
  }

  it should "reproduce the legacy index on a disconnected graph" in {
    sameHeuristics(CompactAltBuilder.build(twoBlocksCg, 8, LandmarkSelection.Weighted, 3), legacy(twoBlocks, twoBlocksCg, 8), twoBlocksCg.n)
  }

  "The builder" should "return the same index for any thread count and selection" in {
    for (selection <- LandmarkSelection.values)
      sameHeuristics(
        CompactAltBuilder.build(connectedCg, 12, selection, 1),
        CompactAltBuilder.build(connectedCg, 12, selection, 4),
        connectedCg.n
      )
  }

  it should "stay admissible for both selections" in {
    for (selection <- LandmarkSelection.values; (loaded, cg) <- Seq((connected, connectedCg), (twoBlocks, twoBlocksCg))) {
      val index = CompactAltBuilder.build(cg, 8, selection, 2)
      for (u <- 0 until cg.n) {
        val exact = loaded.graph.dijkstraAllDistances(cg.nodeGraphs(u))
        for (v <- 0 until cg.n) {
          val h = index.heuristic(u, v)
          h should be >= 0.0
          val d = exact.getOrElse(cg.nodeGraphs(v), Inf)
          if (d < Inf) h should be <= d
        }
      }
    }
  }

  it should "return the requested landmark count on a disconnected graph for both selections" in {
    for (selection <- LandmarkSelection.values)
      CompactAltBuilder.build(twoBlocksCg, 10, selection, 2).k shouldBe 10
  }

  it should "clamp the count to the node count and return an empty index for zero" in {
    val tiny = CompactGraph.fromLoaded(load(3, Seq((1, 2, 1.0), (2, 3, 1.0))))
    for (selection <- LandmarkSelection.values) {
      CompactAltBuilder.build(tiny, 10, selection, 2).k shouldBe 3
      CompactAltBuilder.build(tiny, 0, selection, 2).k shouldBe 0
    }
  }

  it should "reject negative edge weights" in {
    val negative = CompactGraph.fromLoaded(load(2, Seq((1, 2, -1.0))))
    an[IllegalArgumentException] should be thrownBy CompactAltBuilder.build(negative, 2)
  }

  "The interleaved index" should "survive the cache codec round trip" in {
    val index = CompactAltBuilder.build(connectedCg, 8, LandmarkSelection.Weighted, 2)
    val codec = CompactLandmarkIndex.codec(connectedCg.n, 8)
    val bytes = new java.io.ByteArrayOutputStream()
    val out   = new java.io.DataOutputStream(bytes)
    codec.write(index, out)
    out.flush()
    val restored = codec.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray)))
    sameHeuristics(index, restored, connectedCg.n)
  }

  it should "keep the on-disk layout of earlier builds: all forward rows, then all backward rows" in {
    val index = CompactAltBuilder.build(connectedCg, 4, LandmarkSelection.Weighted, 2)
    val bytes = new java.io.ByteArrayOutputStream()
    val out   = new java.io.DataOutputStream(bytes)
    CompactLandmarkIndex.codec(connectedCg.n, 4).write(index, out)
    out.flush()
    val in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray))
    in.readInt() shouldBe 4
    in.readInt() shouldBe connectedCg.n
    for (landmark <- 0 until 4; v <- 0 until connectedCg.n) in.readDouble() shouldBe index.distanceFrom(landmark, v)
    for (landmark <- 0 until 4; v <- 0 until connectedCg.n) in.readDouble() shouldBe index.distanceTo(landmark, v)
    in.available() shouldBe 0
  }

  it should "read the same values the legacy translation stores" in {
    val legacyIndex = legacy(connected, connectedCg, 4)
    val built       = CompactAltBuilder.build(connectedCg, 4, LandmarkSelection.Weighted, 2)
    for (landmark <- 0 until 4; v <- 0 until connectedCg.n) {
      built.distanceFrom(landmark, v) shouldBe legacyIndex.distanceFrom(landmark, v)
      built.distanceTo(landmark, v) shouldBe legacyIndex.distanceTo(landmark, v)
    }
  }

  "The pedestrian graph" should "get admissible landmarks, checked against Bellman-Ford on its own CSR" in {
    val ped = CompactGraph.fromLoadedBidirectional(connected)
    ped.n should be > 0
    val (rowPtr, col, wt) = (ped.rowPtrArray, ped.colIdxArray, ped.edgeWeightArray)

    def exact(source: Int): Array[Double] = {
      val dist = Array.fill(ped.n)(Inf)
      dist(source) = 0.0
      var changed = true
      while (changed) {
        changed = false
        for (u <- 0 until ped.n if dist(u) < Inf; e <- rowPtr(u) until rowPtr(u + 1)) {
          val candidate = dist(u) + wt(e)
          if (candidate < dist(col(e))) { dist(col(e)) = candidate; changed = true }
        }
      }
      dist
    }

    for (selection <- LandmarkSelection.values) {
      val index = CompactAltBuilder.build(ped, 8, selection, 3)
      var positive = 0
      for (u <- 0 until ped.n) {
        val d = exact(u)
        for (v <- 0 until ped.n) {
          val h = index.heuristic(u, v)
          h should be >= 0.0
          if (d(v) < Inf) h should be <= d(v)
          if (h > 0.0) positive += 1
        }
      }
      positive should be > 0
    }
  }

  "LandmarkSelection.parse" should "accept the two names and reject others" in {
    LandmarkSelection.parse(" Weighted ") shouldBe LandmarkSelection.Weighted
    LandmarkSelection.parse("hops") shouldBe LandmarkSelection.Hops
    an[IllegalArgumentException] should be thrownBy LandmarkSelection.parse("random")
  }
}
