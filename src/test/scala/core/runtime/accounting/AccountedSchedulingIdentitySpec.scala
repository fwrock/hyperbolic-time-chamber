package org.interscity.htc
package core.runtime.accounting

import core.actor.manager.time.OptimisticLocalTimeManager
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.runtime.EventOrder
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class AccountedSchedulingIdentitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("AccountedSchedulingIdentitySpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  "accounted scheduling" should "keep equal numeric IDs in different models independent on the same owner" in {
    val parent = TestProbe()
    val first = TestProbe()
    val second = TestProbe()
    val coordinator = parent.childActorOf(AccountedRuntimeCoordinator.props(1, 1000L, None))
    val owner = system.actorOf(OptimisticLocalTimeManager.props(1000L, parent.ref, None, Some(coordinator)))
    val a = Identify(id = 1L, classType = "example.Energy")
    val b = Identify(id = 1L, classType = "example.Mobility")
    val activation = EventOrder(5L, 3L, "external-activation")
    first.send(coordinator, JoinRuntime(a, first.ref, Some(activation)))
    second.send(coordinator, JoinRuntime(b, second.ref, Some(EventOrder.root(5L, b.classType, b.id))))
    val joined = first.expectMsgType[RuntimeJoined]
    second.expectMsgType[RuntimeJoined].owner shouldBe owner
    joined.owner shouldBe owner
    parent.send(coordinator, StartRuntime(0L))
    val d1 = first.expectMsgType[RuntimeDelivery]
    val d2 = second.expectMsgType[RuntimeDelivery]
    val s1 = d1.event.asInstanceOf[SpontaneousEvent]
    val s2 = d2.event.asInstanceOf[SpontaneousEvent]
    s1.generation shouldBe 1L
    s1.order shouldBe Some(activation)
    s2.generation shouldBe 1L
    first.send(coordinator, DeliveryAccepted(joined.epoch, d1.token))
    second.send(coordinator, DeliveryAccepted(joined.epoch, d2.token))
    first.send(coordinator, RouteControl(joined.epoch, owner,
      FinishEvent(first.ref, a, 5L, timeManager = owner, generation = s1.generation,
        scheduleRevision = 1L), 5L))
    val next = s2.order.get.child(10L, 1L)
    second.send(coordinator, RouteControl(joined.epoch, owner,
      FinishEvent(second.ref, b, 5L, scheduleTick = Some("10"), timeManager = owner,
        generation = s2.generation, scheduleRevision = 1L, nextOrder = Some(next)), 5L))
    val following = second.expectMsgType[RuntimeDelivery].event.asInstanceOf[SpontaneousEvent]
    following.tick shouldBe 10L
    following.generation shouldBe 2L
    following.order shouldBe Some(next)
    first.expectNoMessage(100.millis)
    parent.expectNoMessage(100.millis)
    parent.watch(coordinator)
    system.stop(coordinator)
    parent.expectTerminated(coordinator)
  }

  it should "retain a self-event beyond the horizon without dispatching it" in {
    val parent = TestProbe()
    val lp = TestProbe()
    val coordinator = parent.childActorOf(AccountedRuntimeCoordinator.props(1, 1000L, None))
    system.actorOf(OptimisticLocalTimeManager.props(1000L, parent.ref, None, Some(coordinator)))
    val identity = Identify(id = 2L, classType = "example.Energy")
    lp.send(coordinator, JoinRuntime(identity, lp.ref, Some(EventOrder.root(1002L, identity.classType, identity.id))))
    lp.expectMsgType[RuntimeJoined]
    parent.send(coordinator, StartRuntime(0L))
    lp.expectNoMessage(100.millis)
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, 1001L))
    lp.expectMsg(CommitRuntime(freeze.round, 1001L))
    lp.send(coordinator, RuntimeCommitted(freeze.round, false))
    lp.expectMsg(RetireRuntimeProcess(freeze.round, finalCut = true))
    lp.send(coordinator, RuntimeProcessRetired(freeze.round))
    parent.expectMsg(RuntimeFinished(1001L, true))
  }
}
