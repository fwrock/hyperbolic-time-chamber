package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, AccountedOptimisticLocalTimeManager}
import core.actor.rollback.EventReadJournal
import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum}
import core.runtime.OptimisticExecutionLimits
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class BoundedCounterState(startTick: Long = 0L, schedule: Boolean = false,
  requester: Boolean = false, target: Long = 0L, end: Long = 1000L, count: Long = 0L)
  extends BaseState(startTick = startTick, scheduleOnTimeManager = schedule)
private case class CountRequest(replyTo: Long, steps: Long)
private case class CountReply(value: Long)

class BoundedOptimisticRuntimeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("BoundedOptimisticRuntimeSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=5")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class GTM(manager: ActorRef, sink: ActorRef, observed: ActorRef, limits: OptimisticExecutionLimits)
    extends AccountedOptimisticGlobalTimeManager(2000L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
    override protected lazy val executionLimits: OptimisticExecutionLimits = limits
    override protected def recordRetention(round: Long, retained: RuntimeRetention): Unit = observed ! retained
    def ready: Boolean = recoveryFinished
  }
  private class Worker(manager: ActorRef, global: ActorRef)
    extends AccountedOptimisticLocalTimeManager(2000L, manager, global) {
    def ready: Boolean = recoveryFinished
  }
  private class CounterLP(props: Properties, dropFinish: Boolean) extends SimulationBaseActor[BoundedCounterState](props) {
    def ready: Boolean = recoveryFinished
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      if (state.requester) {
        sendMessageTo(state.target, getClass.getName, CountRequest(getEntityId.toLong, state.end),
          actorType = CreationTypeEnum.PoolDistributed)
        registerAwaitedReply("count", "count-result")
      } else {
        val step = EventReadJournal.read("step")(1L)
        state = state.copy(count = state.count + step)
        if (currentTick < state.end) onFinishSpontaneous(Some(currentTick + 1L))
        else {
          sendMessageTo(state.target, getClass.getName, CountReply(state.count), actorType = CreationTypeEnum.PoolDistributed)
          selfDestruct()
        }
      }
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = event.data match {
      case request: CountRequest =>
        state = state.copy(target = request.replyTo, end = request.steps)
        scheduleEvent(currentTick + 1L)
      case reply: CountReply =>
        consumeAwaitedReply("count")
        state = state.copy(count = reply.value)
        report(Map("count" -> state.count), "count-result")
        if (!dropFinish) selfDestruct()
    }
  }
  private var sequence = 0
  private def run(limits: OptimisticExecutionLimits, steps: Long = 1000L, dropFinish: Boolean = false): Vector[RuntimeRetention] = {
    sequence += 1
    val manager = TestProbe()
    val flush = TestProbe()
    val sink = TestProbe()
    val observed = TestProbe()
    val creator = TestProbe()
    val global = TestActorRef(new GTM(manager.ref, flush.ref, observed.ref, limits), s"gtm-$sequence")
    creator.awaitAssert(global.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector("a", "b").map(n => TestActorRef(new Worker(manager.ref, global), s"worker-$sequence-$n"))
    creator.awaitAssert(workers.forall(_.underlyingActor.ready) shouldBe true, 5.seconds, 10.millis)
    def lp(id: Long, state: BoundedCounterState): Unit = {
      val ref = TestActorRef(new CounterLP(Properties(entityId = id.toString, actorType = CreationTypeEnum.PoolDistributed,
        runtimeCoordinator = Some(global), reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref),
        timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> workers.head)), dropFinish), s"lp-$sequence-$id")
      creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      ref ! InitializeEvent(id.toString, creator.ref, InitializeData(state, "bounded-counter", creator.ref,
        mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> workers.head),
        runtimeCoordinator = Some(global)))
      creator.expectMsgType[InitializeEntityAckEvent]
    }
    lp(2L, BoundedCounterState(end = steps))
    lp(1L, BoundedCounterState(schedule = true, requester = true, target = 2L, end = steps))
    global ! StartSimulationTimeEvent(startTick = 0L)
    if (dropFinish) {
      manager.expectMsgType[RuntimeFailure](20.seconds).reason should include ("dispatch has neither queued input nor a reply obligation")
      manager.expectNoMessage(100.millis)
    } else {
      val delivery = sink.expectMsgType[CommittedReportDelivery](45.seconds)
      delivery.event.data.asInstanceOf[Map[String, Any]]("count") shouldBe steps
      sink.send(delivery.ackTo, DeliveryAccepted(delivery.epoch, delivery.token))
      val drain = flush.expectMsgType[DrainRuntimeReports](5.seconds)
      flush.send(global, RuntimeReportsFlushed(drain.round))
      manager.expectMsg(StopSimulationEvent())
    }
    observed.receiveWhile(2.seconds, 100.millis) { case retention: RuntimeRetention => retention }.toVector
  }

  "bounded optimistic execution" should "complete a thousand-tick deferred reply through a one-tick window with bounded retained history" in {
    val bounded = run(OptimisticExecutionLimits(1L, 4, 2, true))
    bounded.size should be > 100
    bounded.map(_.events).max should be <= 8L
    bounded.map(_.reads).max should be <= 6L
    bounded.map(_.processedIds).max should be <= 6L
    val reference = run(OptimisticExecutionLimits(2000L, 4000, 64, false))
    reference.map(_.events).max should be >= 1000L
    reference.map(_.reads).max should be >= 1000L
  }
  it should "fail an orphaned dispatch when its reply handler forgets completion" in {
    run(OptimisticExecutionLimits(1L, 4, 2, true), steps = 10L, dropFinish = true)
  }
}
