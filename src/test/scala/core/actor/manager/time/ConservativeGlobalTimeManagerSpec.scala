package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.{ QueryNextTickEvent, TimeManagerRegisterEvent }
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.routing.Broadcast
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.{ LocalTimeReportEvent, StartSimulationTimeEvent, StopSimulationEvent, UpdateGlobalTimeEvent }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class ConservativeGlobalTimeManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "ConservativeGlobalTimeManagerSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = local
      pekko.actor.fail-mixed-versions = off
      pekko.coordinated-shutdown.phases.actor-system-terminate.enabled = off
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
    """)
      .withFallback(ConfigFactory.load())
  )

  override def afterAll(): Unit = { system.terminate(); () }

  private class TestManager(simulationManager: ActorRef)
      extends ConservativeGlobalTimeManager(1000L, false, simulationManager) {
    override def onStart(): Unit = ()
    def testRecoveryFinished: Boolean = recoveryFinished
    def testSetPool(pool: ActorRef): Unit = timeManagersPool = pool
  }

  private def startedManager(): (TestActorRef[TestManager], TestProbe, TestProbe, TestProbe) = {
    val pool = TestProbe()
    val manager = TestActorRef(new TestManager(TestProbe().ref))
    pool.awaitAssert(manager.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    manager.underlyingActor.testSetPool(pool.ref)
    val personLtm = TestProbe()
    val carLtm = TestProbe()
    manager ! TimeManagerRegisterEvent(personLtm.ref)
    manager ! TimeManagerRegisterEvent(carLtm.ref)
    manager ! StartSimulationTimeEvent(startTick = 0L)
    pool.expectMsgType[Broadcast].message shouldBe a[StartSimulationTimeEvent]
    (manager, pool, personLtm, carLtm)
  }

  "Conservative termination" should "wait for an interaction-driven activation without keeping a tick batch open" in {
    val (manager, pool, personLtm, carLtm) = startedManager()
    personLtm.send(manager, LocalTimeReportEvent(tick = 10L, hasPendingWork = true))
    carLtm.send(manager, LocalTimeReportEvent(tick = 10L))
    pool.expectNoMessage(150.millis)
    personLtm.expectNoMessage(150.millis)
    carLtm.expectNoMessage(150.millis)

    carLtm.send(manager, LocalTimeReportEvent(tick = 11L, hasScheduled = true))
    carLtm.expectMsgType[UpdateGlobalTimeEvent].tick shouldBe 11L
    personLtm.expectNoMessage(150.millis)
    pool.expectNoMessage(150.millis)
  }

  it should "probe and terminate only after the final pending obligation has been resolved" in {
    val (manager, pool, personLtm, carLtm) = startedManager()
    personLtm.send(manager, LocalTimeReportEvent(tick = 10L, hasPendingWork = true))
    carLtm.send(manager, LocalTimeReportEvent(tick = 10L))
    personLtm.send(manager, LocalTimeReportEvent(tick = 20L))
    personLtm.expectMsg(QueryNextTickEvent)
    carLtm.expectMsg(QueryNextTickEvent)
    personLtm.send(manager, LocalTimeReportEvent(tick = 20L))
    carLtm.send(manager, LocalTimeReportEvent(tick = 20L))
    pool.expectMsgType[Broadcast].message shouldBe a[StopSimulationEvent]
  }

  it should "cancel a termination probe when an outstanding request is reported" in {
    val (manager, pool, personLtm, carLtm) = startedManager()
    personLtm.send(manager, LocalTimeReportEvent(tick = 10L))
    carLtm.send(manager, LocalTimeReportEvent(tick = 10L))
    personLtm.expectMsg(QueryNextTickEvent)
    carLtm.expectMsg(QueryNextTickEvent)

    personLtm.send(manager, LocalTimeReportEvent(tick = 10L, hasPendingWork = true))
    carLtm.send(manager, LocalTimeReportEvent(tick = 10L))
    pool.expectNoMessage(150.millis)
    personLtm.expectNoMessage(150.millis)
    carLtm.expectNoMessage(150.millis)
  }
}
