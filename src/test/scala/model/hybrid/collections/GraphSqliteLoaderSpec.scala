package org.interscity.htc
package model.hybrid.collections

import model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.nio.file.{ Files, Path }
import java.sql.DriverManager

/** Locks in that `Graph.loadFromSqliteFile` produces the exact same `LoadedGraphData` as
  * `Graph.loadFromJsonFile` for the equivalent input — nodes, edge labels, weights, and directed
  * adjacency must all match, since `CityMapUtil` picks between the two purely by file extension
  * and every downstream consumer (routing, warmup indexes) must see an identical graph either
  * way.
  *
  * Builds the JSON and the `.db` directly (not via `tools/scenario-db-converter/convert.py`) so
  * this test has no dependency on that script.
  */
class GraphSqliteLoaderSpec extends AnyFlatSpec with Matchers {

  Class.forName("org.sqlite.JDBC")

  private val nodeIdExtractor: NodeGraph => String = _.id
  private val edgeLabelIdExtractor: EdgeGraph => String = _.id

  private def writeJson(path: Path): Unit = {
    val json =
      """{
        |  "nodes": [
        |    {"id":"n1","resourceId":"r1","classType":"Node","latitude":-23.5,"longitude":-46.6},
        |    {"id":"n2","resourceId":"r2","classType":"Node","latitude":-23.6,"longitude":-46.7},
        |    {"id":"n3","resourceId":"r3","classType":"Node","latitude":-23.7,"longitude":-46.8}
        |  ],
        |  "edges": [
        |    {"source_id":"n1","target_id":"n2","weight":12.5,"label":{"id":"e1","resourceId":"lr1","classType":"Link","length":100.0}},
        |    {"source_id":"n2","target_id":"n3","weight":null,"label":{"id":"e2","resourceId":"lr2","classType":"Link","length":50.0}}
        |  ],
        |  "directed": true
        |}""".stripMargin
    val writer = new PrintWriter(path.toFile)
    try writer.write(json)
    finally writer.close()
  }

  private def writeDb(path: Path): Unit = {
    Files.deleteIfExists(path)
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${path.toString}")
    try {
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE city_map_meta (directed INTEGER NOT NULL)")
      stmt.execute(
        "CREATE TABLE city_map_node (id TEXT PRIMARY KEY, resource_id TEXT, class_type TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL)"
      )
      stmt.execute(
        "CREATE TABLE city_map_edge (id TEXT PRIMARY KEY, resource_id TEXT, class_type TEXT, length REAL, source_id TEXT NOT NULL, target_id TEXT NOT NULL, weight REAL)"
      )
      stmt.execute("INSERT INTO city_map_meta (directed) VALUES (1)")
      stmt.close()

      val insertNode =
        conn.prepareStatement("INSERT INTO city_map_node VALUES (?, ?, ?, ?, ?)")
      List(
        ("n1", "r1", "Node", -23.5, -46.6),
        ("n2", "r2", "Node", -23.6, -46.7),
        ("n3", "r3", "Node", -23.7, -46.8)
      ).foreach {
        case (id, rid, ct, lat, lon) =>
          insertNode.setString(1, id)
          insertNode.setString(2, rid)
          insertNode.setString(3, ct)
          insertNode.setDouble(4, lat)
          insertNode.setDouble(5, lon)
          insertNode.executeUpdate()
      }
      insertNode.close()

      val insertEdge =
        conn.prepareStatement("INSERT INTO city_map_edge VALUES (?, ?, ?, ?, ?, ?, ?)")
      insertEdge.setString(1, "e1")
      insertEdge.setString(2, "lr1")
      insertEdge.setString(3, "Link")
      insertEdge.setDouble(4, 100.0)
      insertEdge.setString(5, "n1")
      insertEdge.setString(6, "n2")
      insertEdge.setDouble(7, 12.5)
      insertEdge.executeUpdate()

      insertEdge.setString(1, "e2")
      insertEdge.setString(2, "lr2")
      insertEdge.setString(3, "Link")
      insertEdge.setDouble(4, 50.0)
      insertEdge.setString(5, "n2")
      insertEdge.setString(6, "n3")
      insertEdge.setNull(7, java.sql.Types.REAL)
      insertEdge.executeUpdate()
      insertEdge.close()
    } finally conn.close()
  }

  "Graph.loadFromSqliteFile" should "produce the same LoadedGraphData as loadFromJsonFile for the equivalent graph" in {
    val jsonPath = Files.createTempFile("graph-sqlite-loader-spec", ".json")
    val dbPath = Files.createTempFile("graph-sqlite-loader-spec", ".db")
    try {
      writeJson(jsonPath)
      writeDb(dbPath)

      val jsonResult =
        Graph
          .loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
            jsonPath.toString,
            nodeIdExtractor,
            edgeLabelIdExtractor,
            0.0
          )
          .get
      val dbResult = Graph.loadFromSqliteFile(dbPath.toString).get

      dbResult.nodesById shouldBe jsonResult.nodesById
      dbResult.edgeLabelsById shouldBe jsonResult.edgeLabelsById
      dbResult.graph.edges shouldBe jsonResult.graph.edges

      // The NULL-weight edge (e2) must fall back to defaultWeightForUnweighted (0.0) in both.
      dbResult.graph.weight(jsonResult.nodesById("n2"), jsonResult.nodesById("n3")) shouldBe Some(0.0)
      dbResult.graph.weight(jsonResult.nodesById("n1"), jsonResult.nodesById("n2")) shouldBe Some(12.5)
    } finally {
      Files.deleteIfExists(jsonPath)
      Files.deleteIfExists(dbPath)
    }
  }
}
