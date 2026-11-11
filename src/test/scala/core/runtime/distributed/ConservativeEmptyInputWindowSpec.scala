package org.interscity.htc
package model.test

import core.actor.manager.time.ChannelClosedGlobalTimeManager
import core.entity.event.control.load.{RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest}
import core.runtime.accounting.JoinRuntimeManager
import core.runtime.conservative.{CollectConservativeReady, ConservativeMembership, ConservativeReady}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class ConservativeEmptyInputWindowSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ConservativeEmptyInputWindowSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class Coordinator(manager: ActorRef) extends ChannelClosedGlobalTimeManager(86400L, false, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 128
    def recovered: Boolean = recoveryFinished
  }

  "conservative progressive input" should "advance an empty initial window only after all 128 frontiers reply" in {
    val manager = TestProbe()
    val producer = TestProbe()
    val global = TestActorRef(new Coordinator(manager.ref))
    manager.awaitAssert(global.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    val workers = Vector.fill(128)(TestProbe())
    workers.foreach(w => w.send(global, JoinRuntimeManager(w.ref)))
    workers.foreach(_.expectMsgType[ConservativeMembership])
    producer.send(global, RegisterProgressiveLoadManagerEvent(producer.ref, lookAheadTicks = 10000L))
    global ! StartSimulationTimeEvent(startTick = 0L)
    producer.expectMsg(TickWindowRequest(0L, 10000L))
    producer.send(global, TickWindowReady(10000L, 0L))
    val rounds = workers.map(_.expectMsgType[CollectConservativeReady].round)
    rounds.distinct.size shouldBe 1
    workers.dropRight(1).foreach(_.send(global, ConservativeReady(rounds.head, None, 0)))
    producer.expectNoMessage(100.millis)
    workers.last.send(global, ConservativeReady(rounds.head, None, 0))
    producer.expectMsg(TickWindowRequest(10001L, 20001L))
    producer.send(global, TickWindowReady(20001L, 0L))
    workers.foreach { w =>
      val request = w.expectMsgType[CollectConservativeReady]
      w.send(global, ConservativeReady(request.round, None, 0))
    }
    producer.expectMsg(TickWindowRequest(20002L, 30002L))
    system.stop(global)
  }
}
