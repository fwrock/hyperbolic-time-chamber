package org.interscity.htc
package model.hybrid.collections

import model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.io.PrintWriter

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s model-level audit finding: `CompactGraph`'s
  * A* used to abort a search once `System.nanoTime()` passed a wall-clock deadline
  * (`GPSUtil.calcRouteCompact`'s old `maxNanos = 2_000_000_000L`), which made replaying the same
  * route search under Time Warp non-deterministic -- the same event, re-executed under different
  * CPU contention (plausible during a rollback cascade), could time out at a different point and
  * return a different route, or none, than the original run. `runAStar` now bounds total edges
  * examined (`maxEdgeRelaxations`), a pure function of graph topology and CSR insertion order, not
  * of wall-clock time.
  *
  * Builds a small hub-and-spoke graph where the real destination is reachable through the hub, but
  * only via the LAST edge in the hub's adjacency list (CSR preserves per-source edge order from the
  * JSON edge list) -- a small `maxEdgeRelaxations` budget exhausts itself on the hub's earlier decoy
  * edges before ever reaching it, proving the cap is driven by edges examined, not nodes expanded
  * (the hub is only the 2nd node expanded, far under `maxExpansions`).
  */
class CompactGraphEdgeRelaxationCapSpec extends AnyFlatSpec with Matchers {

  private val nodeIdExtractor: NodeGraph => String = _.id
  private val edgeLabelIdExtractor: EdgeGraph => String = _.id

  private val decoyCount = 20

  private def buildGraph(): CompactGraph = {
    val decoyNodes = (0 until decoyCount).map(i => s"""{"id":"leaf$i","resourceId":"r","classType":"Node","latitude":-23.0,"longitude":-46.0}""")
    val decoyEdges = (0 until decoyCount).map(i => s"""{"source_id":"hub","target_id":"leaf$i","weight":1.0,"label":{"id":"e-leaf$i","resourceId":"lr","classType":"Link","length":1.0}}""")

    val json =
      s"""{
         |  "nodes": [
         |    {"id":"origin","resourceId":"r","classType":"Node","latitude":-23.0,"longitude":-46.0},
         |    {"id":"hub","resourceId":"r","classType":"Node","latitude":-23.01,"longitude":-46.01},
         |    ${decoyNodes.mkString(",\n    ")},
         |    {"id":"dest","resourceId":"r","classType":"Node","latitude":-23.02,"longitude":-46.02}
         |  ],
         |  "edges": [
         |    {"source_id":"origin","target_id":"hub","weight":1.0,"label":{"id":"e-origin-hub","resourceId":"lr","classType":"Link","length":1.0}},
         |    ${decoyEdges.mkString(",\n    ")},
         |    {"source_id":"hub","target_id":"dest","weight":1.0,"label":{"id":"e-hub-dest","resourceId":"lr","classType":"Link","length":1.0}}
         |  ],
         |  "directed": true
         |}""".stripMargin

    val path: Path = Files.createTempFile("compact-graph-edge-relaxation-cap-spec", ".json")
    try {
      val writer = new PrintWriter(path.toFile)
      try writer.write(json)
      finally writer.close()

      val loaded = Graph
        .loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](path.toString, nodeIdExtractor, edgeLabelIdExtractor, 0.0)
        .get
      CompactGraph.fromLoaded(loaded)
    } finally Files.deleteIfExists(path)
  }

  "runAStar's maxEdgeRelaxations cap" should "let an unrestricted search find the route through the hub's last edge" in {
    val cg = buildGraph()
    val result = cg.aStarEuclidean("origin", "dest", useDynamicWeights = false, maxExpansions = 1000, maxEdgeRelaxations = Long.MaxValue)
    result shouldBe defined
    result.get._2.map(_._2).lastOption shouldBe Some("dest")
  }

  it should "abort deterministically, before reaching the hub's destination edge, when the budget is smaller than the hub's decoy fan-out" in {
    val cg = buildGraph()
    // 1 (origin->hub) + a handful of hub's decoy edges -- well short of decoyCount + 1, so the cap
    // is hit while still scanning decoys, never reaching hub->dest. maxExpansions=1000 is never
    // close to binding (only origin and hub get expanded before the cap triggers), proving this is
    // an edge-count cap, not a node-count one.
    val restricted = cg.aStarEuclidean("origin", "dest", useDynamicWeights = false, maxExpansions = 1000, maxEdgeRelaxations = 5L)
    restricted shouldBe None
  }

  it should "return the exact same result on repeated calls, proving the cap is deterministic (not wall-clock-dependent)" in {
    val cg = buildGraph()
    val results = (1 to 5).map(_ => cg.aStarEuclidean("origin", "dest", useDynamicWeights = false, maxExpansions = 1000, maxEdgeRelaxations = 5L))
    results.distinct shouldBe Seq(None)
  }
}
