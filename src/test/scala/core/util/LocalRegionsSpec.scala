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

  "getShardRef" should "fail clearly for a type that was never started" in {
    an[IllegalStateException] should be thrownBy DistributedUtil.getShardRef("model.Missing", system)
  }
}
