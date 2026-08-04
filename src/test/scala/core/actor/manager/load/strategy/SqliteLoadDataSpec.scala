package org.interscity.htc
package core.actor.manager.load.strategy

import core.entity.actor.ActorSimulationCreation
import core.entity.actor.properties.Properties
import core.entity.configuration.{ ActorDataSource, DataSource }
import core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, FinishLoadDataEvent, LoadDataSourceEvent }
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

/** End-to-end proof that `SqliteLoadData` is a real drop-in replacement for `JsonLoadData`: given
  * a `.db` with the schema `tools/scenario-db-converter/convert.py` produces, it must speak the
  * exact same `LoadDataSourceEvent` -> `CreateActorsEvent` -> `FinishCreationEvent` ->
  * `FinishLoadDataEvent` protocol `LoadDataManager`/`CreatorLoadData` already expect from
  * `JsonLoadData`, including opening the SQLite connection with the `immutable=1`/`query_only=ON`
  * settings against a real file on disk (not just the in-memory DB
  * `SqliteActorSimulationUtilSpec` uses).
  *
  * `pekko.actor.provider = local`: `LoadDataStrategy` actors are plain (non-sharded) actors, so no
  * cluster is needed to exercise this protocol in isolation.
  */
class SqliteLoadDataSpec
    extends TestKit(
      ActorSystem(
        "SqliteLoadDataSpec",
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
    val path = Files.createTempFile("sqlite-load-data-spec", ".db")
    Files.delete(path) // sqlite-jdbc creates the file itself
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

  "SqliteLoadData" should "stream every row through CreateActorsEvent and report FinishLoadDataEvent with the total count" in {
    val dbPath = buildDb(List(("p1", "P1", 10L), ("p2", "P2", 20L), ("p3", "P3", 30L)))

    val managerProbe = TestProbe()
    val creatorProbe = TestProbe()
    val creatorPoolProbe = TestProbe()

    val loader = system.actorOf(
      Props(
        classOf[SqliteLoadData],
        Properties(entityId = "sqlite-loader-test", resourceId = "")
      )
    )

    val source = ActorDataSource(
      id = "person-source",
      classType = "person",
      creationType = CreationTypeEnum.LoadBalancedDistributed,
      dataSource = DataSource(sourceType = DataSourceTypeEnum.sqlite, info = Map("path" -> dbPath)),
      loadingStrategy = LoadingStrategyEnum.EAGER,
      entityLifecycle = EntityLifecycleEnum.DYNAMIC
    )

    loader ! LoadDataSourceEvent(
      actorDataSource = source,
      managerRef = managerProbe.ref,
      creatorRef = creatorProbe.ref,
      creatorPoolRef = creatorPoolProbe.ref
    )

    val createEvent = creatorProbe.expectMsgType[CreateActorsEvent](10.seconds)
    createEvent.actors.map(_.actor.id).toSet shouldBe Set("p1", "p2", "p3")
    createEvent.actors should have size 3

    creatorProbe.reply(
      FinishCreationEvent(actorRef = creatorProbe.ref, batchId = createEvent.id, amount = 3)
    )

    val finishEvent = managerProbe.expectMsgType[FinishLoadDataEvent](10.seconds)
    finishEvent.amount shouldBe 3
    finishEvent.actorClassType shouldBe "person"
  }
}
