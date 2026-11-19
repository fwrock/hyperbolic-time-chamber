package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.routing.Broadcast
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class RouterPoolSpec extends TestKit(ActorSystem("RouterPoolSpec",
  ConfigFactory.parseString(
    """pekko.actor.provider=local
      |pekko.actor.fail-mixed-versions=off
      |htc.runtime.mode=single-node""".stripMargin).withFallback(ConfigFactory.load())))
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private class Routee extends Actor {
    override def receive: Receive = { case _ => sender() ! self.path.name }
  }

  private def routees(total: Int, perNode: Int, name: String): Set[String] = {
    val pool = ActorCreatorUtil.createRouterPool(system, system, Props(new Routee), name, total, perNode)
    (1 to 60).foreach(_ => pool ! "who")
    receiveN(60, 5.seconds).map(_.toString).toSet
  }

  "createRouterPool in single-node mode" should "cap the global count by the per-node cap" in {
    routees(total = 8, perNode = 3, name = "pool-cap") should have size 3
  }

  it should "use the global count when it is below the cap" in {
    routees(total = 2, perNode = 10, name = "pool-total") should have size 2
  }

  it should "always keep at least one routee" in {
    routees(total = 0, perNode = 0, name = "pool-floor") should have size 1
  }

  it should "round-robin and support Broadcast like the cluster router did" in {
    val pool = ActorCreatorUtil.createRouterPool(system, system, Props(new Routee), "pool-broadcast", 4, 4)
    pool ! Broadcast("all")
    receiveN(4, 5.seconds).map(_.toString).toSet should have size 4
  }
}
