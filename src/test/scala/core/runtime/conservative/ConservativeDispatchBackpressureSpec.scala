package org.interscity.htc
package model.test

import core.actor.manager.time.ChannelAwareLocalTimeManager
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class ConservativeDispatchBackpressureSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ConservativeDispatchBackpressureSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private case class Held(ref: ActorRef, message: Any)
  private case class Release(message: Any)
  private case object StopLP
  private class LP(observer: ActorRef, owner: ActorRef) extends Actor {
    def receive: Receive = {
      case _: RuntimeJoined => ()
      case StopLP => context.stop(self)
      case Release(message) => owner ! message
      case message => observer ! Held(self, message)
    }
  }
  private class Worker(global: ActorRef, manager: ActorRef)
    extends ChannelAwareLocalTimeManager(100L, manager, global) {
    override protected def maxInFlight: Int = 2
    def recovered: Boolean = recoveryFinished
  }

  "conservative dispatch" should "bound deliveries and grants without advancing an unfinished causal batch" in {
    val global = TestProbe(); val manager = TestProbe(); val observer = TestProbe()
    val worker = TestActorRef(new Worker(global.ref, manager.ref))
    global.awaitAssert(worker.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    global.receiveWhile(idle = 50.millis) { case _ => () }
    global.send(worker, ConservativeMembership("bounded", Vector(worker)))
    (1L to 7L).foreach { id =>
      val ref = system.actorOf(Props(new LP(observer.ref, worker)))
      global.send(worker, RegisterConservativeProcess("bounded", global.ref,
        JoinRuntime(Identify(id = id, classType = "example.Node"), ref,
          Some(EventOrder.root(0L, "example.Node", id)))))
    }
    global.send(worker, CollectConservativeReady(1L))
    global.expectMsgType[ConservativeReady]
    val batch = ExecuteConservativeBatch(1L, 0L, 0L)
    global.send(worker, batch)
    val deliveries = scala.collection.mutable.Queue.from(Vector.fill(2)(observer.expectMsgType[Held]))
    observer.expectNoMessage(100.millis)
    var remaining = 5
    while (deliveries.nonEmpty) {
      val held = deliveries.dequeue()
      val delivery = held.message.asInstanceOf[ConservativeDelivery]
      held.ref ! Release(ConservativeInputAccepted("bounded", delivery.token, delivery.event.asInstanceOf[core.entity.event.SpontaneousEvent].order))
      if (remaining > 0) {
        deliveries.enqueue(observer.expectMsgType[Held])
        remaining -= 1
      }
    }
    val grants = scala.collection.mutable.Queue.from(Vector.fill(2)(observer.expectMsgType[Held]))
    observer.expectNoMessage(100.millis)
    global.expectNoMessage(100.millis)
    remaining = 5
    while (grants.nonEmpty) {
      val held = grants.dequeue()
      held.message shouldBe batch
      held.ref ! Release(ConservativeBatchProcessed(1L, None, 0))
      if (remaining > 0) {
        grants.enqueue(observer.expectMsgType[Held])
        remaining -= 1
      }
    }
    global.expectMsg(ConservativeBatchComplete(1L))
    observer.expectNoMessage(100.millis)
    global.expectNoMessage(100.millis)
    val stop = StopConservativeRuntime(2L, true, Some(100L))
    global.send(worker, stop)
    val retirements = scala.collection.mutable.Queue.from(Vector.fill(2)(observer.expectMsgType[Held]))
    observer.expectNoMessage(100.millis)
    remaining = 5
    while (retirements.nonEmpty) {
      val held = retirements.dequeue()
      held.message shouldBe RetireConservativeProcess(2L, true, Some(100L))
      held.ref ! Release(ConservativeProcessRetired(2L))
      if (remaining == 5) observer.expectNoMessage(100.millis)
      held.ref ! StopLP
      if (remaining > 0) {
        retirements.enqueue(observer.expectMsgType[Held])
        remaining -= 1
      }
    }
    global.expectMsg(ConservativeRuntimeStopped(2L, Set.empty))
    system.stop(worker)
  }
}
