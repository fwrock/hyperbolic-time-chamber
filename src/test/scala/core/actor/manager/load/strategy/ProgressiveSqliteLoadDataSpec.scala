package org.interscity.htc
package core.actor.manager.load.strategy

import core.entity.actor.properties.Properties
import core.entity.configuration.{ ActorDataSource, DataSource }
import core.entity.event.control.load.{
  CreateActorsEvent,
  FinishCreationEvent,
  FinishLoadDataEvent,
  LoadActorsForTickRange,
  LoadDataSourceEvent,
  TickIndexBuiltEvent,
  TickRangeLoadedEvent
}
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum, EntityLifecycleEnum, LoadingStrategyEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorSystem, Props }
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.sql.DriverManager
import scala.concurrent.duration.*

/** End-to-end proof that `ProgressiveSqliteLoadData` speaks the same
  * `BuildTickIndex`/`TickIndexBuiltEvent`/`LoadActorsForTickRange`/`TickRangeLoadedEvent` protocol
  * `ProgressiveLoadDataManager` already expects from `ProgressiveJsonLoadData` — and that a tick
  * window only returns actors whose `start_tick` actually falls in range (the indexed
  * `WHERE start_tick BETWEEN ? AND ?` query this class exists to make possible), leaving
  * out-of-window actors for a later window.
  */
class ProgressiveSqliteLoadDataSpec
    extends TestKit(
      ActorSystem(
        "ProgressiveSqliteLoadDataSpec",
        ConfigFactory
          .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
          .withFallback(ConfigFactory.load())
      )
    )
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  Class.forName("org.sqlite.JDBC")

  private var tempFiles: List[Path] = List.empty

  override def afterAll(): Unit = {
    tempFiles.foreach(p => Files.deleteIfExists(p))
    TestKit.shutdownActorSystem(system)
  }

  private def buildDb(rows: List[(String, String, Long)]): String = {
    val path = Files.createTempFile("progressive-sqlite-load-data-spec", ".db")
    Files.delete(path)
    tempFiles = path :: tempFiles

    val conn = DriverManager.getConnection(s"jdbc:sqlite:${path.toString}")
    try {
      val stmt = conn.createStatement()
      stmt.execute(
        """CREATE TABLE actor_simulation (
          |  seq INTEGER PRIMARY KEY AUTOINCREMENT,
          |  id TEXT NOT NULL, name TEXT, type_actor TEXT NOT NULL,
          |  creation_type TEXT NOT NULL, start_tick INTEGER NOT NULL DEFAULT 0,
          |  pool_round_robin_pool INTEGER, pool_total_instances INTEGER,
          |  pool_max_instances_per_node INTEGER, pool_allow_local_routes INTEGER,
          |  pool_use_roles TEXT, data_type TEXT NOT NULL, data_content TEXT NOT NULL,
          |  relationships TEXT
          |)""".stripMargin
      )
      stmt.execute("CREATE INDEX idx_actor_simulation_start_tick ON actor_simulation(start_tick)")
      stmt.close()

      val insert = conn.prepareStatement(
        """INSERT INTO actor_simulation
          |  (id, name, type_actor, creation_type, start_tick, data_type, data_content)
          |VALUES (?, ?, ?, 'LoadBalancedDistributed', ?, 'PersonData', ?)""".stripMargin
      )
      rows.foreach {
        case (id, name, startTick) =>
          insert.setString(1, id)
          insert.setString(2, name)
          insert.setString(3, "person")
          insert.setLong(4, startTick)
          insert.setString(5, s"""{"startTick":$startTick}""")
          insert.executeUpdate()
      }
      insert.close()
    } finally conn.close()

    path.toString
  }

  "ProgressiveSqliteLoadData" should
    "build a tick index, then load only the actors whose start_tick falls in the requested window" in {
      val dbPath = buildDb(
        List(
          ("p1", "P1", 10L),
          ("p2", "P2", 20L),
          ("p3", "P3", 500L) // deliberately outside the first window requested below
        )
      )

      val managerProbe = TestProbe()
      val creatorProbe = TestProbe()
      val creatorPoolProbe = TestProbe()

      val loader = system.actorOf(
        Props(
          classOf[ProgressiveSqliteLoadData],
          Properties(entityId = "progressive-sqlite-loader-test", resourceId = "")
        )
      )

      val source = ActorDataSource(
        id = "person-source",
        classType = "person",
        creationType = CreationTypeEnum.LoadBalancedDistributed,
        dataSource = DataSource(sourceType = DataSourceTypeEnum.sqlite, info = Map("path" -> dbPath)),
        loadingStrategy = LoadingStrategyEnum.PROGRESSIVE,
        entityLifecycle = EntityLifecycleEnum.DYNAMIC
      )

      loader ! LoadDataSourceEvent(
        actorDataSource = source,
        managerRef = managerProbe.ref,
        creatorRef = creatorProbe.ref,
        creatorPoolRef = creatorPoolProbe.ref
      )

      val indexBuilt = managerProbe.expectMsgType[TickIndexBuiltEvent](10.seconds)
      indexBuilt.totalActors shouldBe 3
      indexBuilt.maxTick shouldBe 500L
      indexBuilt.tickCounts shouldBe Map(10L -> 1, 20L -> 1, 500L -> 1)

      // Window [0, 100]: only p1 (10) and p2 (20) qualify — p3 (500) must NOT be loaded yet.
      loader ! LoadActorsForTickRange(fromTick = 0L, toTick = 100L)

      val createEvent = creatorProbe.expectMsgType[CreateActorsEvent](10.seconds)
      createEvent.actors.map(_.actor.id).toSet shouldBe Set("p1", "p2")

      creatorProbe.reply(
        FinishCreationEvent(actorRef = creatorProbe.ref, batchId = createEvent.id, amount = 2)
      )

      val rangeLoaded = managerProbe.expectMsgType[TickRangeLoadedEvent](10.seconds)
      rangeLoaded.fromTick shouldBe 0L
      rangeLoaded.toTick shouldBe 100L
      rangeLoaded.actorsLoaded shouldBe 2

      // Next window [101, 500]: only p3 (500) qualifies.
      loader ! LoadActorsForTickRange(fromTick = 101L, toTick = 500L)

      val secondCreateEvent = creatorProbe.expectMsgType[CreateActorsEvent](10.seconds)
      secondCreateEvent.actors.map(_.actor.id).toSet shouldBe Set("p3")

      creatorProbe.reply(
        FinishCreationEvent(actorRef = creatorProbe.ref, batchId = secondCreateEvent.id, amount = 1)
      )

      val secondRangeLoaded = managerProbe.expectMsgType[TickRangeLoadedEvent](10.seconds)
      secondRangeLoaded.actorsLoaded shouldBe 1
    }
}
