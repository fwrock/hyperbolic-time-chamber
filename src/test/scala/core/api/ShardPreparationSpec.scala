package org.interscity.htc
package core.api

import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import core.actor.manager.loadbalance.allocation.{ AllocationRuntime, SpatialShardIdRegistry }
import core.entity.configuration.Simulation
import core.util.SimulationUtil
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDateTime
import scala.concurrent.duration.*

class ShardPreparationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ShardPreparationSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider=cluster
      pekko.actor.fail-mixed-versions=off
      pekko.remote.artery.canonical.hostname="127.0.0.1"
      pekko.remote.artery.canonical.port=0
      pekko.cluster.seed-nodes=[]
      htc.load-balance-manager.enabled=true
      htc.load-balance-manager.strategy=default
    """).withFallback(ConfigFactory.load()))

  override def beforeAll(): Unit = {
    LoadBalanceTestIsolation.acquire()
    SimulatorSettingsRegistry.clearAll()
    SpatialShardIdRegistry.clear()
    Cluster(system).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).selfMember.status shouldBe MemberStatus.Up, 10.seconds, 20.millis)
    ApiConfigRegistry.set(Simulation(name = "startup", description = "", startTick = 0L,
      startRealTime = LocalDateTime.of(2025, 1, 1, 0, 0), timeUnit = "seconds", timeStep = 1L,
      duration = 10L, dynamicActorTypes = List("hybrid.actor.Node")))
    SimulationController.initialize(system, apiEnabled = true)
  }

  override def afterAll(): Unit = try {
    TestKit.shutdownActorSystem(system, 15.seconds)
    SimulatorSettingsRegistry.clearAll()
    ApiConfigRegistry.clear()
    SpatialShardIdRegistry.clear()
  } finally LoadBalanceTestIsolation.release()

  "API shard preparation" should "apply saved settings before registering regions even with a scenario configured at boot" in {
    SimulationUtil.startShardsAtBoot(system, apiWaitEnabled = true)
    ClusterSharding(system).shardTypeNames shouldBe empty

    SimulatorSettingsRegistry.set("htc.load-balance-manager.enabled", "false")
    SimulationController.prepare() shouldBe Right(())
    SimulationController.status shouldBe SimulationStatus.Idle
    AllocationRuntime(system).enabled shouldBe false
    SpatialShardIdRegistry.publishedSize shouldBe 0

    val probe = TestProbe()
    val region = ClusterSharding(system).shardRegion("org.interscity.htc.model.hybrid.actor.Node")
    probe.send(region, ShardRegion.StartEntity("99"))
    probe.expectMsgType[ShardRegion.StartEntityAck](10.seconds).entityId shouldBe "99"
    SpatialShardIdRegistry.publishedSize shouldBe 0
  }

  it should "reject a changed routing mode before starting loaders instead of mixing shard and creator policies" in {
    SimulatorSettingsRegistry.set("htc.load-balance-manager.enabled", "true")
    val result = SimulationController.start()
    result.isLeft shouldBe true
    result.swap.toOption.get should include("reset the engine JVM")
    SimulationController.status shouldBe SimulationStatus.Idle
    AllocationRuntime(system).enabled shouldBe false
  }
}
