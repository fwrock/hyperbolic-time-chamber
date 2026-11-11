package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.core.cache.CalculationCache
import org.interscity.htc.core.entity.configuration.CalculationCacheConfig
import org.interscity.htc.model.hybrid.collections.graph.LandmarkIndex
import org.interscity.htc.model.hybrid.entity.state.model.{EdgeGraph, NodeGraph}
import java.nio.file.Files
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LandmarkCacheSpec extends AnyFlatSpec with Matchers {
  private def data(weight: Double) = {
    val file = Files.createTempFile("landmark-graph", ".json")
    val nodes = (1 to 3).map(i => s"""{"id":$i,"resourceId":"n","classType":"Node","latitude":0,"longitude":0}""")
    val edges = (1 to 2).map(i => s"""{"source_id":$i,"target_id":${i+1},"weight":$weight,"label":{"id":$i,"resourceId":"l","classType":"Link","length":$weight}}""")
    Files.writeString(file, s"""{"nodes":[${nodes.mkString(",")}],"edges":[${edges.mkString(",")}],"directed":true}""")
    try Graph.loadFromJsonFile[NodeGraph, Long, Double, EdgeGraph](file.toString, _.id, _.id, 0.0).get
    finally Files.delete(file)
  }

  "landmark disk cache" should "restore exact heuristics without recalculating and invalidate weights and landmark count" in {
    val loaded = data(2.0)
    val graph = CompactGraph.fromLoaded(loaded)
    val dir = Files.createTempDirectory("landmark-cache")
    val config = Some(CalculationCacheConfig(dir.toString, List("routing.alt.vehicle")))
    val original = CompactLandmarkIndex.fromLandmarkIndex(LandmarkIndex.build(loaded.graph, 2), graph.nodeIndex, _.id, graph.n)
    def load(compute: => CompactLandmarkIndex) = CalculationCache.getOrCompute(config, "routing.alt.vehicle", 1,
      graph.staticFingerprint(2), CompactLandmarkIndex.codec(graph.n, 2))(compute)
    load(original)
    val restored = load(fail("a cache hit must not calculate landmarks"))
    for (u <- 0 until graph.n; v <- 0 until graph.n) restored.heuristic(u, v) shouldBe original.heuristic(u, v)
    graph.staticFingerprint(2) should not be graph.staticFingerprint(3)
    graph.staticFingerprint(2) should not be CompactGraph.fromLoaded(data(3.0)).staticFingerprint(2)
    val stream = Files.list(dir)
    try stream.forEach(p => Files.delete(p)) finally stream.close()
    Files.delete(dir)
  }
}
