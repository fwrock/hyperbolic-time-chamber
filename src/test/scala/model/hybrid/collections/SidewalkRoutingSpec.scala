package org.interscity.htc
package model.hybrid.collections

import model.hybrid.entity.state.model.{EdgeGraph, NodeGraph, SidewalkConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SidewalkRoutingSpec extends AnyFlatSpec with Matchers {
  private def node(id: Long) = NodeGraph(id, "r", "Node", 0, 0)
  private val origin = node(1); private val middle = node(2); private val destination = node(3)
  private val blocked = EdgeGraph(10, "r", "Link", 100, SidewalkConfig(enabled = false))
  private val open = EdgeGraph(11, "r", "Link", 100)
  private val detour = EdgeGraph(12, "r", "Link", 100)
  private def loaded(graph: Graph[NodeGraph, Double, EdgeGraph]) =
    LoadedGraphData(graph, graph.vertices.map(n => n.id -> n).toMap, graph.edges.map(e => e.label.id -> e.label).toMap)

  "Pedestrian routing" should "avoid disabled sidewalks while vehicle routing still uses the road" in {
    val graph = Graph.empty[NodeGraph, Double, EdgeGraph]
      .addEdge(origin, destination, 1, blocked).addEdge(origin, middle, 2, open).addEdge(middle, destination, 2, detour)
    val data = loaded(graph)
    CompactGraph.fromLoaded(data).aStarEuclidean(1, 3, useDynamicWeights = false).get._2.map(_._1).toList shouldBe List(10L)
    val walking = CompactGraph.fromLoadedBidirectional(data)
    walking.aStarEuclidean(1, 3, useDynamicWeights = false).get._2.map(_._1).toList shouldBe List(11L, 12L)
    walking.aStarEuclidean(3, 1, useDynamicWeights = false).get._2.map(_._1).toList shouldBe List(12L, 11L)
  }

  it should "retain isolated nodes when all connecting sidewalks are disabled" in {
    val graph = Graph.empty[NodeGraph, Double, EdgeGraph].addEdge(origin, middle, 1, blocked).addVertex(destination)
    val walking = CompactGraph.fromLoadedBidirectional(loaded(graph))
    walking.n shouldBe 3
    walking.aStarEuclidean(1, 2, useDynamicWeights = false) shouldBe None
    graph.filterEdgeLabels(_.sidewalk.enabled).vertices shouldBe Set(origin, middle, destination)
  }
}
