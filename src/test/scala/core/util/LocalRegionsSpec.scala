package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import core.entity.event.EntityEnvelopeEvent
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class LocalRegionsSpec extends TestKit(ActorSystem("LocalRegionsSpec",
  ConfigFactory.parseString(
    """pekko.actor.provider=local
      |pekko.actor.fail-mixed-versions=off
      |htc.runtime.mode=single-node""".stripMargin).withFallback(ConfigFactory.load())))
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private class Entity extends Actor {
    private var id = ""
    override def receive: Receive = {
      case ShardRegion.StartEntity(entityId) => id = entityId
      case payload                           => sender() ! (id, self.path.name, payload)
    }
  }

  "A local region" should "be the same ref for the same type and resolvable through getShardRef" in {
    val region = LocalRegions.getOrCreate(system, "model.A", Props(new Entity))
    LocalRegions.getOrCreate(system, "model.A", Props(new Entity)) shouldBe region
    DistributedUtil.getShardRef("model.A", system) shouldBe region
  }

  it should "ack StartEntity and deliver it to the entity before later messages" in {
    val region = LocalRegions.getOrCreate(system, "model.B", Props(new Entity))
    region ! ShardRegion.StartEntity("42")
    expectMsg(3.seconds, ShardRegion.StartEntityAck("42", "local"))
    region ! EntityEnvelopeEvent("42", "hello")
    expectMsg(3.seconds, ("42", "42", "hello"))
  }

  it should "create the entity on first envelope and keep one actor per entity id" in {
    val region = LocalRegions.getOrCreate(system, "model.C", Props(new Entity))
    region ! EntityEnvelopeEvent("a/b", "x")
    expectMsg(3.seconds, ("", "a%2Fb", "x"))
    region ! EntityEnvelopeEvent("a/b", "y")
    expectMsg(3.seconds, ("", "a%2Fb", "y"))
  }

  it should "answer Passivate by sending the stop message back so the entity stops" in {
    val region = LocalRegions.getOrCreate(system, "model.D", Props(new Actor {
      override def receive: Receive = {
        case "passivate" => context.parent ! ShardRegion.Passivate("stop")
        case "stop"      => context.stop(self)
      }
    }))
    region ! EntityEnvelopeEvent("p1", "passivate")
    awaitAssert(
      {
        val probe = org.apache.pekko.testkit.TestProbe()
        system.actorSelection(region.path / "p1").tell(org.apache.pekko.actor.Identify(1), probe.ref)
        probe.expectMsgType[org.apache.pekko.actor.ActorIdentity](1.second).ref shouldBe None
      },
      5.seconds,
      100.millis
    )
  }

  "A local entity router" should "keep one sender's messages in order when the entity is born mid-stream" in {
    val region = LocalRegions.getOrCreate(system, "model.E", Props(new Actor {
      override def receive: Receive = {
        case _: ShardRegion.StartEntity =>
        case n: Int                      => sender() ! n
      }
    }))
    val router = new LocalEntityRouter(system)
    val total = 500
    (1 to total).foreach { n =>
      if (!router.tryDirect("model.E", "seq", Int.box(n), testActor))
        region.tell(EntityEnvelopeEvent("seq", Int.box(n)), testActor)
    }
    receiveN(total, 10.seconds).map(_.asInstanceOf[Int]) shouldBe (1 to total)
    LocalRegions.entityIndex(system, "model.E").get("seq") should not be null
    router.tryDirect("model.E", "seq", Int.box(0), testActor) shouldBe false
  }

  it should "go direct for an entity that was already published" in {
    val region = LocalRegions.getOrCreate(system, "model.G", Props(new Actor {
      override def receive: Receive = {
        case _: ShardRegion.StartEntity =>
        case n: Int                      => sender() ! n
      }
    }))
    region ! ShardRegion.StartEntity("live")
    expectMsgType[ShardRegion.StartEntityAck](3.seconds)
    val router = new LocalEntityRouter(system)
    (1 to 50).foreach(n => router.tryDirect("model.G", "live", Int.box(n), testActor) shouldBe true)
    receiveN(50, 5.seconds).map(_.asInstanceOf[Int]) shouldBe (1 to 50)
  }

  it should "never go direct in cluster mode" in {
    val clusterSystem = ActorSystem("ClusterModeRouter", ConfigFactory.parseString(
      "pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.runtime.mode=cluster").withFallback(ConfigFactory.load()))
    try new LocalEntityRouter(clusterSystem).tryDirect("model.E", "seq", "x", testActor) shouldBe false
    finally TestKit.shutdownActorSystem(clusterSystem)
  }

  "The entity index" should "be absent for a region that was never started" in {
    LocalRegions.entityIndex(system, "model.Nope") shouldBe null
  }

  it should "drop an entity from the index once it stops" in {
    val region = LocalRegions.getOrCreate(system, "model.F", Props(new Actor {
      override def receive: Receive = {
        case "passivate" => context.parent ! ShardRegion.Passivate("stop")
        case "stop"      => context.stop(self)
        case _           =>
      }
    }))
    val index = LocalRegions.entityIndex(system, "model.F")
    region ! EntityEnvelopeEvent("gone", "hello")
    awaitAssert(index.get("gone") should not be null, 5.seconds, 50.millis)
    index.get("gone") ! "passivate"
    awaitAssert(index.get("gone") shouldBe null, 5.seconds, 50.millis)
  }

  "getShardRef" should "fail clearly for a type that was never started" in {
    an[IllegalStateException] should be thrownBy DistributedUtil.getShardRef("model.Missing", system)
  }
}
