package org.interscity.htc
package model.test

import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, ChannelClosedGlobalTimeManager}
import core.runtime.accounting.*
import core.runtime.conservative.{ConservativeMembership, RegisterConservativeProcess}
import core.runtime.distributed.{RegisterLocalProcess, RuntimeMembership}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class RuntimeMembershipSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("RuntimeMembershipSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private val workerCount = 128
  private class Conservative(manager: ActorRef) extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = workerCount
    def ready: Boolean = recoveryFinished
  }
  private class Optimistic(manager: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = workerCount
    def ready: Boolean = recoveryFinished
  }

  "runtime ownership" should "use the published conservative membership for queued and later joins" in {
    val manager = TestProbe()
    val gtm = TestActorRef(new Conservative(manager.ref))
    manager.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    checkMembership(gtm, conservative = true)
    manager.expectNoMessage(50.millis)
  }

  it should "use the published optimistic membership for queued and later joins" in {
    val manager = TestProbe()
    val gtm = TestActorRef(new Optimistic(manager.ref))
    manager.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    checkMembership(gtm, conservative = false)
    manager.expectNoMessage(50.millis)
  }

  private def checkMembership(gtm: ActorRef, conservative: Boolean): Unit = {
    val workers = Vector.fill(workerCount)(TestProbe())
    val lp = TestProbe()
    def join(id: Long): JoinRuntime = JoinRuntime(Identify(id = id, classType = "example.Node"), lp.ref, None)
    val queued = join(Long.MinValue)
    lp.send(gtm, queued)
    // Deliberately register in the opposite order from the canonical path order.
    workers.sortBy(_.ref.path.toString).reverse.foreach(p => p.send(gtm, JoinRuntimeManager(p.ref)))
    val order = workers.map(_.ref).sortBy(_.path.toString)
    val byRef = workers.map(p => p.ref -> p).toMap
    var epoch = ""
    workers.foreach { worker =>
      if (conservative) {
        val membership = worker.expectMsgType[ConservativeMembership]
        membership.managers shouldBe order
        epoch = membership.epoch
      } else {
        val membership = worker.expectMsgType[RuntimeMembership]
        membership.managers shouldBe order
        epoch = membership.epoch
      }
    }
    def check(event: JoinRuntime): Unit = {
      val id = LogicalProcessId(event.identity.classType, event.identity.id)
      val owner = byRef(order(Math.floorMod(id.hashCode(), order.size)))
      if (conservative) owner.expectMsg(RegisterConservativeProcess(epoch, gtm, event))
      else owner.expectMsg(RegisterLocalProcess(epoch, event))
    }
    check(queued)
    (1L to 1024L).foreach { id =>
      val event = join(id)
      lp.send(gtm, event)
      check(event)
    }
    workers.foreach(_.expectNoMessage(Duration.Zero))
  }
}
