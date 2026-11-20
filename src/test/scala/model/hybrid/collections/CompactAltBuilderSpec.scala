package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.model.hybrid.collections.graph.LandmarkIndex
import org.interscity.htc.model.hybrid.entity.state.model.{EdgeGraph, NodeGraph}
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

  "LandmarkSelection.parse" should "accept the two names and reject others" in {
    LandmarkSelection.parse(" Weighted ") shouldBe LandmarkSelection.Weighted
    LandmarkSelection.parse("hops") shouldBe LandmarkSelection.Hops
    an[IllegalArgumentException] should be thrownBy LandmarkSelection.parse("random")
  }
}
