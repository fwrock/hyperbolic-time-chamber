package org.interscity.htc
package core.actor

import com.typesafe.config.ConfigFactory
import core.actor.manager.report.NodeLocalReporters
import core.entity.actor.properties.Properties
import core.entity.event.SpontaneousEvent
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.routing.{ ActorRefRoutee, GetRoutees, Routees }
import org.apache.pekko.testkit.{ TestActor, TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class ReportingState(startTick: Long = 0L) extends BaseState(startTick, scheduleOnTimeManager = false)

class NodeLocalReportingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem =
    ActorSystem("NodeLocalReportingSpec",
      ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
        .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class LP(props: Properties) extends SimulationBaseActor[ReportingState](props) {
    def ready: Boolean = recoveryFinished
    override protected def onHorizonFinalization(horizon: Long): Unit =
      report(Map("observed_tick" -> currentTick), "horizon")
    override def actSpontaneous(event: SpontaneousEvent): Unit = onFinishSpontaneous(Some(100L))
  }

  private var identity = 900L

  /** Retire a truncated LP and return where its final report was addressed. */
  private def finalReportTarget(placement: String, warmResolution: Boolean, poolNamed: Boolean = true): (org.apache.pekko.actor.ActorRef, TestProbe, TestProbe) = {
    // `config` of an actor is the settings registry view, not the test ActorSystem's config; keep the override short-lived.
    core.api.SimulatorSettingsRegistry.set(NodeLocalReporters.PlacementKey, placement)
    try run(placement, warmResolution, poolNamed)
    finally core.api.SimulatorSettingsRegistry.clear(NodeLocalReporters.PlacementKey)
  }

  private def run(placement: String, warmResolution: Boolean, poolNamed: Boolean): (org.apache.pekko.actor.ActorRef, TestProbe, TestProbe) = {
    identity += 1L
    val global = TestProbe(s"global-$identity"); val owner = TestProbe(s"owner-$identity"); val localSink = TestProbe(s"localSink-$identity"); val creator = TestProbe(s"creator-$identity")
    val router = TestProbe(if (poolNamed) s"${core.util.ManagerConstantsUtil.POOL_REPORT_DATA_ACTOR_NAME_PREFIX}-parquet-$identity" else s"plain-sink-$identity")
    router.setAutoPilot((sender, message) => {
      message match {
        case GetRoutees => sender ! Routees(Vector(ActorRefRoutee(localSink.ref)))
        case _          => ()
      }
      TestActor.KeepRunning
    })
    owner.ignoreMsg {
      case report: PublishCommittedReport if report.event.label == "actor_placement" => true
      case _: PublishConservativePlacement => true
      case _ => false
    }
    val policy = TimeManagerTypeEnum.DISCRETE_EVENT
    val props = Properties(entityId = identity.toString, actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(global.ref), defaultTimeManagerType = policy,
      timeManagers = mutable.Map(policy -> owner.ref), reporters = mutable.Map(ReportTypeEnum.parquet -> router.ref))
    val lp = TestActorRef(new LP(props), identity.toString)
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent(identity.toString, creator.ref, InitializeData(ReportingState(), "test", creator.ref,
      mutable.Map(ReportTypeEnum.parquet -> router.ref), timeManagers = mutable.Map(policy -> owner.ref),
      runtimeCoordinator = Some(global.ref)))
    global.expectMsgType[JoinRuntime]
    global.send(lp, RuntimeJoined("epoch", global.ref, owner.ref, distributed = false))
    creator.expectMsgType[InitializeEntityAckEvent]
    val event = SpontaneousEvent(5L, owner.ref, generation = 1L, order = Some(EventOrder(5L, 0L, "wake")))
    owner.send(lp, ConservativeDelivery("epoch", 1L, event))
    owner.expectMsgType[ConservativeInputAccepted]
    owner.send(lp, ExecuteConservativeBatch(1L, 5L, 0L))
    owner.expectMsgType[RouteControl]
    owner.expectMsgType[ConservativeBatchProcessed]
    if (warmResolution) {
      val extension = NodeLocalReporters(system)
      val deadline = 5.seconds.fromNow
      while (!extension.isSettled(router.ref) && deadline.hasTimeLeft()) { extension.pick(router.ref, 1, "warm"); Thread.sleep(25) }
      extension.isSettled(router.ref) shouldBe true
    }
    owner.send(lp, RetireConservativeProcess(2L, true, Some(10L)))
    val published = owner.expectMsgType[PublishConservativeReport]
    owner.expectMsg(ConservativeProcessRetired(2L))
    (published.target, router, localSink)
  }

  "report placement" should "address a report to the sink on this JVM once the routees are resolved" in {
    val (target, _, localSink) = finalReportTarget("node-local", warmResolution = true)
    target shouldBe localSink.ref
  }

  it should "keep addressing the router before the routees are resolved" in {
    val (target, router, _) = finalReportTarget("node-local", warmResolution = false)
    target shouldBe router.ref
  }

  it should "address the router when the placement is cluster-pool" in {
    val (target, router, _) = finalReportTarget("cluster-pool", warmResolution = true)
    target shouldBe router.ref
  }

  it should "never ask a reporter that is not a report pool for its routees" in {
    val (target, router, _) = finalReportTarget("node-local", warmResolution = false, poolNamed = false)
    target shouldBe router.ref
    router.expectNoMessage(300.millis)
  }
}
