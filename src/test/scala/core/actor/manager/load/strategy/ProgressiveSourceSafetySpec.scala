package org.interscity.htc
package core.actor.manager.load.strategy

import core.entity.actor.properties.Properties
import core.entity.configuration.{ActorDataSource, DataSource}
import core.entity.event.control.load.*
import core.enumeration.{CreationTypeEnum, DataSourceTypeEnum, EntityLifecycleEnum, LoadingStrategyEnum}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.concurrent.duration.*

class ProgressiveSourceSafetySpec extends TestKit(ActorSystem("ProgressiveSourceSafetySpec",
  ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load())))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  private var files = List.empty[Path]
  override def afterAll(): Unit = { TestKit.shutdownActorSystem(system); files.foreach(Files.deleteIfExists); () }
  private val records = List((1L, 0L, "LoadBalancedDistributed"), (2L, 0L, "PoolDistributed"), (3L, 100L, "LoadBalancedDistributed"))
  private def write(path: Path, sqlite: Boolean, rows: List[(Long, Long, String)]): Unit = if (!sqlite) {
    val json = rows.map { case (id, tick, creation) =>
      s"""{"id":$id,"typeActor":"person","creationType":"$creation","data":{"dataType":"PersonData","content":{"startTick":$tick}}}"""
    }.mkString("[", ",", "]")
    Files.writeString(path, json)
    ()
  } else {
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$path")
    try {
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE IF NOT EXISTS actor_simulation (seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT,name TEXT,type_actor TEXT,creation_type TEXT,start_tick INTEGER,pool_round_robin_pool INTEGER,pool_total_instances INTEGER,pool_max_instances_per_node INTEGER,pool_allow_local_routes INTEGER,pool_use_roles TEXT,data_type TEXT,data_content TEXT,relationships TEXT)")
      stmt.execute("DELETE FROM actor_simulation")
      stmt.close()
      val insert = conn.prepareStatement("INSERT INTO actor_simulation(id,type_actor,creation_type,start_tick,data_type,data_content) VALUES (?,'person',?,?,'PersonData',?)")
      try rows.foreach { case (id, tick, creation) =>
        insert.setString(1, id.toString); insert.setString(2, creation); insert.setLong(3, tick)
        insert.setString(4, s"""{"startTick":$tick}"""); insert.executeUpdate()
      } finally insert.close()
    } finally conn.close()
  }
  private class Fixture(sqlite: Boolean, missing: Boolean = false) {
    val path = Files.createTempFile("progressive-source-safety", if (sqlite) ".db" else ".json")
    files ::= path
    if (missing) Files.delete(path) else write(path, sqlite, records)
    val manager = TestProbe()
    val creator = TestProbe()
    val pool = TestProbe()
    val properties = Properties(entityId = "progressive-source-safety", resourceId = "")
    val loader = system.actorOf(if (sqlite) Props(new ProgressiveSqliteLoadData(properties)) else Props(new ProgressiveJsonLoadData(properties)))
    loader ! LoadDataSourceEvent(ActorDataSource(id = "test", classType = "person", creationType = CreationTypeEnum.LoadBalancedDistributed,
      dataSource = DataSource(if (sqlite) DataSourceTypeEnum.sqlite else DataSourceTypeEnum.json, Map("path" -> path.toString)),
      loadingStrategy = LoadingStrategyEnum.PROGRESSIVE, entityLifecycle = EntityLifecycleEnum.DYNAMIC), manager.ref, creator.ref, pool.ref)
    def indexed(): Unit = manager.expectMsgType[TickIndexBuiltEvent](10.seconds).totalActors shouldBe 3
    def ack(probe: TestProbe, event: CreateActorsEvent): Unit =
      probe.reply(FinishCreationEvent(probe.ref, event.id, event.actors.size))
  }
  for (sqlite <- List(false, true)) {
    val format = if (sqlite) "SQLite" else "JSON"
    s"$format empty progressive sources" should "acknowledge an empty indexed range without creating actors" in {
      val path = Files.createTempFile("progressive-empty-source", if (sqlite) ".db" else ".json")
      files ::= path
      write(path, sqlite, Nil)
      val manager = TestProbe()
      val creator = TestProbe()
      val properties = Properties(entityId = "empty-source", resourceId = "")
      val loader = system.actorOf(if (sqlite) Props(new ProgressiveSqliteLoadData(properties)) else Props(new ProgressiveJsonLoadData(properties)))
      loader ! LoadDataSourceEvent(ActorDataSource(id = "empty", classType = "person", creationType = CreationTypeEnum.LoadBalancedDistributed,
        dataSource = DataSource(if (sqlite) DataSourceTypeEnum.sqlite else DataSourceTypeEnum.json, Map("path" -> path.toString)),
        loadingStrategy = LoadingStrategyEnum.PROGRESSIVE, entityLifecycle = EntityLifecycleEnum.DYNAMIC), manager.ref, creator.ref, creator.ref)
      manager.expectMsgType[TickIndexBuiltEvent](10.seconds).totalActors shouldBe 0
      loader ! LoadActorsForTickRange(0L, 10L)
      manager.expectMsg(TickRangeLoadedEvent("empty", 0L, 10L, 0L))
      creator.expectNoMessage(100.millis)
    }
    s"$format progressive windows" should "wait for both creator acknowledgement paths and retain later cohorts" in {
      val f = new Fixture(sqlite)
      f.indexed()
      f.loader ! LoadActorsForTickRange(0L, 10L)
      val sharded = f.creator.expectMsgType[CreateActorsEvent](10.seconds)
      val pooled = f.pool.expectMsgType[CreateActorsEvent](10.seconds)
      sharded.actors.map(_.actor.id) shouldBe List(1L)
      pooled.actors.map(_.actor.id) shouldBe List(2L)
      f.ack(f.creator, sharded)
      f.manager.expectNoMessage(100.millis)
      f.pool.reply(FinishCreationEvent(f.pool.ref, "unknown", 1))
      f.manager.expectNoMessage(100.millis)
      f.ack(f.pool, pooled)
      f.manager.expectMsg(TickRangeLoadedEvent("test", 0L, 10L, 2L))
      f.ack(f.pool, pooled)
      f.loader ! LoadActorsForTickRange(11L, 100L)
      val later = f.creator.expectMsgType[CreateActorsEvent](10.seconds)
      later.actors.map(_.actor.id) shouldBe List(3L)
      f.ack(f.creator, later)
      f.manager.expectMsg(TickRangeLoadedEvent("test", 11L, 100L, 1L))
    }
    it should "report an index error instead of empty successful input" in {
      val f = new Fixture(sqlite, missing = true)
      f.manager.expectMsgType[ProgressiveSourceFailed](10.seconds).reason should include ("index")
      f.manager.expectNoMessage(100.millis)
    }
    it should "report a source removed after indexing instead of advancing coverage" in {
      val f = new Fixture(sqlite)
      f.indexed()
      Files.delete(f.path)
      f.loader ! LoadActorsForTickRange(0L, 10L)
      f.manager.expectMsgType[ProgressiveSourceFailed](10.seconds)
      f.manager.expectNoMessage(100.millis)
    }
    it should "reject a range whose record count no longer matches its index" in {
      val f = new Fixture(sqlite)
      f.indexed()
      write(f.path, sqlite, records.filterNot(_._1 == 2L))
      f.loader ! LoadActorsForTickRange(0L, 10L)
      val created = f.creator.expectMsgType[CreateActorsEvent](10.seconds)
      f.ack(f.creator, created)
      f.manager.expectMsgType[ProgressiveSourceFailed](10.seconds).reason should include ("count mismatch")
      f.manager.expectNoMessage(100.millis)
    }
  }
}
