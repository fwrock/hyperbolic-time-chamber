package org.interscity.htc
package core.actor.manager.report

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorSystem, ExtendedActorSystem }
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.apache.pekko.routing.{ ActorRefRoutee, GetRoutees, Routees }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class NodeLocalReportersSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem =
    ActorSystem("NodeLocalReportersSpec", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load()))
  private val extension = NodeLocalReporters(system)
  override def afterAll(): Unit = { TestKit.shutdownActorSystem(system); () }

  /** A pool router stand-in that answers GetRoutees with the given sinks. */
  private def routerOf(sinks: Seq[TestProbe]): TestProbe = {
    val router = TestProbe()
    router.setAutoPilot((sender, message) => {
      message match {
        case GetRoutees => sender ! Routees(sinks.map(s => ActorRefRoutee(s.ref)).toVector)
        case _          => ()
      }
      org.apache.pekko.testkit.TestActor.KeepRunning
    })
    router
  }

  "node-local reporters" should "return the router until its routees have been resolved, then a stable local sink" in {
    val sinks  = Seq(TestProbe(), TestProbe(), TestProbe())
    val router = routerOf(sinks)
    extension.pick(router.ref, expectedRoutees = 3, key = "a") shouldBe router.ref
    val deadline = 5.seconds.fromNow
    while (!extension.isSettled(router.ref) && deadline.hasTimeLeft()) {
      extension.pick(router.ref, 3, "a")
      Thread.sleep(50)
    }
    extension.isSettled(router.ref) shouldBe true
    val first = extension.pick(router.ref, 3, "entity-1")
    sinks.map(_.ref) should contain(first)
    (1 to 20).map(_ => extension.pick(router.ref, 3, "entity-1")).toSet shouldBe Set(first)
  }

  it should "spread different entities over the local sinks" in {
    val sinks  = Seq(TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val router = routerOf(sinks)
    extension.settle(router.ref, sinks.map(_.ref).toVector, expected = 4, local = _ => true)
    val used = (1 to 400).map(i => extension.pick(router.ref, 4, s"entity-$i")).toSet
    used shouldBe sinks.map(_.ref).toSet
  }

  it should "keep only the sinks that are local to this JVM" in {
    val sinks  = Seq(TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val router = routerOf(sinks)
    val local  = Set(sinks(1).ref, sinks(3).ref)
    extension.settle(router.ref, sinks.map(_.ref).toVector, expected = 4, local = local.contains)
    (1 to 200).map(i => extension.pick(router.ref, 4, s"entity-$i")).toSet shouldBe local
  }

  it should "ignore a snapshot taken before every expected routee exists" in {
    val sinks  = Seq(TestProbe(), TestProbe())
    val router = routerOf(sinks)
    extension.settle(router.ref, Vector(sinks.head.ref), expected = 2, local = _ => true)
    extension.isSettled(router.ref) shouldBe false
    extension.settle(router.ref, Vector.empty, expected = 1, local = _ => true)
    extension.isSettled(router.ref) shouldBe false
  }

  it should "fall back to the router when this JVM hosts no routee" in {
    val sinks  = Seq(TestProbe(), TestProbe())
    val router = routerOf(sinks)
    extension.settle(router.ref, sinks.map(_.ref).toVector, expected = 2, local = _ => false)
    extension.isSettled(router.ref) shouldBe true
    extension.pick(router.ref, 2, "entity-1") shouldBe router.ref
  }

  "expected routees" should "be the configured total capped by what the members can host" in {
    val config = ConfigFactory.parseString(
      """htc.report-manager.parquet { number-of-instances = 64, number-of-instances-per-node = 16 }""")
    val typed = system.asInstanceOf[ExtendedActorSystem]
    NodeLocalReporters.expectedRoutees(config, core.enumeration.ReportTypeEnum.parquet, typed) shouldBe 16
  }

  "report placement default" should "be node-local in the configuration and in the settings catalog" in {
    ConfigFactory.load().getString(NodeLocalReporters.PlacementKey) shouldBe NodeLocalReporters.NodeLocal
    core.api.SimulatorSettingsRegistry.catalog
      .find(_.configPath == NodeLocalReporters.PlacementKey)
      .map(_.defaultValue) shouldBe Some(NodeLocalReporters.NodeLocal)
  }
}
