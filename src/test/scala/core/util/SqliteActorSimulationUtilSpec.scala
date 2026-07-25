package org.interscity.htc
package core.util

import core.entity.actor.ShardActorId
import core.enumeration.CreationTypeEnum

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{ Connection, DriverManager }

/** Locks in that `SqliteActorSimulationUtil.fromResultSet` reconstructs an `ActorSimulation` from
  * an `actor_simulation` row exactly as `JsonStreamingUtil`/Jackson would from the equivalent JSON
  * record — this is what makes `SqliteLoadData`/`ProgressiveSqliteLoadData` drop-in alternatives
  * to the JSON strategies for every downstream consumer of `ActorSimulation`.
  *
  * Uses an in-memory SQLite database (`jdbc:sqlite::memory:`) populated with the same DDL as
  * `tools/scenario-db-converter/convert.py`, so this test has no dependency on that script or on
  * any file on disk.
  */
class SqliteActorSimulationUtilSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  Class.forName("org.sqlite.JDBC")

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  private val DDL =
    """
      |CREATE TABLE actor_simulation (
      |  seq                          INTEGER PRIMARY KEY AUTOINCREMENT,
      |  id                           TEXT NOT NULL,
      |  name                         TEXT,
      |  type_actor                   TEXT NOT NULL,
      |  creation_type                TEXT NOT NULL,
      |  start_tick                   INTEGER NOT NULL DEFAULT 0,
      |  pool_round_robin_pool        INTEGER,
      |  pool_total_instances         INTEGER,
      |  pool_max_instances_per_node  INTEGER,
      |  pool_allow_local_routes      INTEGER,
      |  pool_use_roles               TEXT,
      |  data_type                    TEXT NOT NULL,
      |  data_content                 TEXT NOT NULL,
      |  relationships                TEXT
      |)
      |""".stripMargin

  private var conn: Connection = _

  override def beforeEach(): Unit = {
    conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    val stmt = conn.createStatement()
    stmt.execute(DDL)
    stmt.close()
  }

  override def afterEach(): Unit =
    if (conn != null) conn.close()

  "SqliteActorSimulationUtil.fromResultSet" should "reconstruct a PoolDistributed actor with relationships and pool roles" in {
    val insert = conn.prepareStatement(
      """INSERT INTO actor_simulation
        |  (id, name, type_actor, creation_type, start_tick,
        |   pool_round_robin_pool, pool_total_instances, pool_max_instances_per_node,
        |   pool_allow_local_routes, pool_use_roles, data_type, data_content, relationships)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    insert.setString(1, "person-1")
    insert.setString(2, "Person 1")
    insert.setString(3, "person")
    insert.setString(4, "PoolDistributed")
    insert.setLong(5, 120L)
    insert.setInt(6, 1)
    insert.setInt(7, 50)
    insert.setInt(8, 5)
    insert.setInt(9, 1)
    insert.setString(10, """["worker"]""")
    insert.setString(11, "PersonData")
    insert.setString(12, """{"startTick":120,"home":"node-1","work":"node-2"}""")
    insert.setString(13, """{"vehicle":{"id":"car-1","classType":"Car","resourceId":"shard-1"}}""")
    insert.executeUpdate()
    insert.close()

    val rs = conn.createStatement().executeQuery("SELECT * FROM actor_simulation")
    rs.next() shouldBe true

    val actor = SqliteActorSimulationUtil.fromResultSet(rs)

    actor.id shouldBe "person-1"
    actor.name shouldBe "Person 1"
    actor.typeActor shouldBe "person"
    actor.creationType shouldBe CreationTypeEnum.PoolDistributed
    actor.poolConfiguration.roundRobinPool shouldBe 1
    actor.poolConfiguration.totalInstances shouldBe 50
    actor.poolConfiguration.maxInstancesPerNode shouldBe 5
    actor.poolConfiguration.allowLocalRoutes shouldBe true
    actor.poolConfiguration.useRoles shouldBe Set("worker")
    actor.data.dataType shouldBe "PersonData"
    // `content` is `Any` — normalize via `convertValue` instead of asserting a concrete container
    // type, since that's an internal Jackson/DefaultScalaModule detail this test isn't about.
    val normalizedContent = mapper.convertValue(actor.data.content, classOf[java.util.Map[String, Any]])
    normalizedContent.get("startTick") shouldBe 120
    normalizedContent.get("home") shouldBe "node-1"
    actor.relationships should not be null
    actor.relationships("vehicle") shouldBe ShardActorId(entityId = "car-1", classType = "Car", shardBucket = "shard-1")
  }

  it should "default poolConfiguration and relationships when the columns are NULL" in {
    val insert = conn.prepareStatement(
      """INSERT INTO actor_simulation
        |  (id, name, type_actor, creation_type, start_tick, data_type, data_content, relationships)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    insert.setString(1, "node-1")
    insert.setString(2, "Node 1")
    insert.setString(3, "node")
    insert.setString(4, "LoadBalancedDistributed")
    insert.setLong(5, 0L)
    insert.setString(6, "NodeData")
    insert.setString(7, "{}")
    insert.setNull(8, java.sql.Types.VARCHAR)
    insert.executeUpdate()
    insert.close()

    val rs = conn.createStatement().executeQuery("SELECT * FROM actor_simulation")
    rs.next() shouldBe true

    val actor = SqliteActorSimulationUtil.fromResultSet(rs)

    actor.creationType shouldBe CreationTypeEnum.LoadBalancedDistributed
    actor.poolConfiguration.totalInstances shouldBe 100
    actor.poolConfiguration.useRoles shouldBe null
    actor.relationships shouldBe null
  }
}
