package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class LocalSingletonsSpec extends TestKit(ActorSystem("LocalSingletonsSpec",
  ConfigFactory.parseString(
    """pekko.actor.provider=local
      |pekko.actor.fail-mixed-versions=off
      |htc.runtime.mode=single-node""".stripMargin).withFallback(ConfigFactory.load())))
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private class Echo extends Actor {
    override def receive: Receive = { case message => sender() ! message }
  }

  "RuntimeMode" should "resolve single-node from config" in {
    RuntimeMode.from(system.settings.config) shouldBe RuntimeMode.SingleNode
  }

  "createSingletonManager in single-node mode" should "create a plain top-level actor and hand out its own ref as proxy" in {
    val manager = ActorCreatorUtil.createSingletonManager(system, Props(new Echo), "echo-direct", "stop")
    manager.path.parent.name shouldBe "user"
    DistributedUtil.createSingletonProxy(system, "echo-direct") shouldBe manager
    manager ! "ping"
    expectMsg(3.seconds, "ping")
  }

  "A proxy requested before the singleton exists" should "buffer in order, keep the sender, and deliver once it appears" in {
    val proxy = DistributedUtil.createSingletonProxy(system, "echo-late")
    val probe = TestProbe()
    proxy.tell("first", probe.ref)
    proxy.tell("second", probe.ref)
    probe.expectNoMessage(200.millis)

    ActorCreatorUtil.createSingletonManager(system, Props(new Echo), "echo-late", "stop")

    probe.expectMsg(3.seconds, "first")
    probe.expectMsg(3.seconds, "second")
  }
}
