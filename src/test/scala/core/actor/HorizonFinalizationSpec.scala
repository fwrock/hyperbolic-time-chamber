package org.interscity.htc
package core.actor

import com.typesafe.config.ConfigFactory
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class HorizonState(startTick: Long = 0L) extends BaseState(startTick, scheduleOnTimeManager = false)

class HorizonFinalizationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("HorizonFinalizationSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.report-manager.default-strategy=json")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class LP(props: Properties, illegalEffect: Boolean, waiting: Boolean) extends SimulationBaseActor[HorizonState](props) {
    def ready: Boolean = recoveryFinished
    override protected def onHorizonFinalization(horizon: Long): Unit = {
      if (illegalEffect) scheduleEvent(horizon + 1L)
      report(Map("observed_tick" -> currentTick), "horizon")
    }
    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      if (waiting) {
        sendMessageTo(99L, "example.Peer", "request")
        registerAwaitedReply("peer", "request")
      }
      onFinishSpontaneous(if (waiting) None else Some(100L))
    }
  }
  private var identity = 500L
  private case class Rig(lp: TestActorRef[LP], global: TestProbe, owner: TestProbe, sink: TestProbe)
  private def rig(policy: String, illegalEffect: Boolean = false, waiting: Boolean = false): Rig = {
    identity += 1L
    val global = TestProbe(); val owner = TestProbe(); val sink = TestProbe(); val creator = TestProbe()
    val props = Properties(entityId = identity.toString, actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(global.ref), defaultTimeManagerType = policy,
      timeManagers = mutable.Map(policy -> owner.ref), reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref))
    val lp = TestActorRef(new LP(props, illegalEffect, waiting), identity.toString)
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent(identity.toString, creator.ref, InitializeData(HorizonState(), "test", creator.ref,
      mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(policy -> owner.ref),
      runtimeCoordinator = Some(global.ref)))
    global.expectMsgType[JoinRuntime]
    global.send(lp, RuntimeJoined("epoch", global.ref, owner.ref, distributed = policy == TimeManagerTypeEnum.TIME_WARP))
    creator.expectMsgType[InitializeEntityAckEvent]
    val event = SpontaneousEvent(5L, owner.ref, generation = 1L, order = Some(EventOrder(5L, 0L, "wake")))
    if (policy == TimeManagerTypeEnum.TIME_WARP) {
      owner.send(lp, SetRuntimeHorizon(10L))
      owner.send(lp, SetOptimisticExecutionLimits(0L, 10L, core.runtime.OptimisticExecutionLimits()))
      owner.send(lp, ResumeRuntime(0L))
      owner.send(lp, RuntimeDelivery("epoch", 1L, event))
      owner.expectMsg(DeliveryAccepted("epoch", 1L))
      if (waiting) owner.expectMsgType[RouteInteraction]
      owner.expectMsgType[RouteControl]
      owner.send(lp, FreezeRuntime(1L))
      owner.expectMsg(RuntimeFrozen(1L))
    } else {
      owner.send(lp, ConservativeDelivery("epoch", 1L, event))
      owner.expectMsgType[ConservativeInputAccepted]
      owner.send(lp, ExecuteConservativeBatch(1L, 5L, 0L))
      if (waiting) owner.expectMsgType[RouteInteraction]
      owner.expectMsgType[RouteControl]
      owner.expectMsgType[ConservativeBatchProcessed]
    }
    Rig(lp, global, owner, sink)
  }

  "optimistic horizon reporting" should "publish at the cap only inside a commit strictly beyond it" in {
    val r = rig(TimeManagerTypeEnum.TIME_WARP)
    r.owner.send(r.lp, CommitRuntime(1L, 11L, Some(10L)))
    r.global.expectNoMessage(100.millis)
    val report = r.owner.expectMsgType[PublishCommittedReport].event
    report.tick shouldBe 10L
    report.data.asInstanceOf[Map[String, Any]]("observed_tick") shouldBe 10L
    r.owner.expectMsgType[RuntimeCommitted]
    r.sink.expectNoMessage(50.millis)
    r.owner.send(r.lp, RetireRuntimeProcess(1L, finalCut = true))
    r.owner.expectMsg(RuntimeProcessRetired(1L))
  }

  it should "reject reporting at GVT and attempts to schedule model work during final reporting" in {
    val boundary = rig(TimeManagerTypeEnum.TIME_WARP)
    boundary.owner.send(boundary.lp, CommitRuntime(1L, 10L, Some(10L)))
    boundary.global.expectMsgType[RuntimeFailure].reason should include ("safe final cut")
    val effect = rig(TimeManagerTypeEnum.TIME_WARP, illegalEffect = true)
    effect.owner.send(effect.lp, CommitRuntime(1L, 11L, Some(10L)))
    effect.global.expectMsgType[RuntimeFailure].reason should include ("model scheduling during horizon reporting")
  }

  "conservative horizon reporting" should "publish before its final retirement acknowledgement" in {
    val r = rig(TimeManagerTypeEnum.DISCRETE_EVENT)
    r.owner.send(r.lp, RetireConservativeProcess(2L, true, Some(10L)))
    r.global.expectNoMessage(100.millis)
    r.owner.expectMsgType[PublishConservativeReport].event.tick shouldBe 10L
    r.owner.expectMsg(ConservativeProcessRetired(2L))
  }

  it should "censor an outstanding reply without treating its old send tick as unfinished work" in {
    val r = rig(TimeManagerTypeEnum.DISCRETE_EVENT, waiting = true)
    r.owner.send(r.lp, RetireConservativeProcess(2L, true, Some(10L)))
    r.global.expectNoMessage(100.millis)
    r.owner.expectMsgType[PublishConservativeReport].event.tick shouldBe 10L
    r.owner.expectMsg(ConservativeProcessRetired(2L))
  }
}
