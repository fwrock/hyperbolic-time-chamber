package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.time.{ChannelAwareLocalTimeManager, ChannelClosedGlobalTimeManager}
import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.load.{RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest}
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum}
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Terminated}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class ChannelState(startTick: Long = 0L, value: Int = 1)
  extends BaseState(startTick = startTick, scheduleOnTimeManager = true)
private case class HeldChannel(target: ActorRef, source: ActorRef, transfer: ConservativeTransfer)

class ConservativeChannelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ConservativeChannelSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off\nhtc.report-manager.default-strategy = json")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class GTM(manager: ActorRef, sink: ActorRef) extends ChannelClosedGlobalTimeManager(1000L, false, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
    def ready: Boolean = recoveryFinished
  }
  private class Worker(gtm: ActorRef, manager: ActorRef, gate: ActorRef)
    extends ChannelAwareLocalTimeManager(1000L, manager, gtm) {
    def ready: Boolean = recoveryFinished
    override protected def transferTo(destination: ActorRef, event: ConservativeTransfer): Unit =
      gate ! HeldChannel(destination, self, event)
  }
  private class LP(props: Properties, target: Long, operation: String, observer: ActorRef)
    extends SimulationBaseActor[ChannelState](props) {
    def ready: Boolean = recoveryFinished
    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      if (target == 0L && currentTick == 0L) {
        onFinishSpontaneous(Some(20L))
      } else if (target != 0L) {
        sendMessageTo(target, getClass.getName, operation, actorType = CreationTypeEnum.PoolDistributed)
        selfDestruct()
        observer ! ("source-finished", currentTick, state.value)
      } else {
        report(Map("value" -> state.value), "state")
        observer ! ("receiver-wake", currentTick, state.value)
        onFinishSpontaneous(None)
      }
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(value = event.data match {
        case "add" => state.value + 3
        case "multiply" => state.value * 2
      })
      report(Map("value" -> state.value), "state")
      observer ! ("receiver-input", currentTick, state.value)
    }
  }

  private var sequence = 0
  private def run(twoSources: Boolean, progressive: Boolean = false): Unit = {
    sequence += 1
    val manager = TestProbe()
    val reports = TestProbe()
    val sink = TestProbe()
    val observer = TestProbe()
    val gate = TestProbe()
    val creator = TestProbe()
    val producer = TestProbe()
    val gtm = TestActorRef(new GTM(manager.ref, reports.ref), s"gtm-$sequence")
    creator.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector("a", "b").map(n => TestActorRef(new Worker(gtm, manager.ref, gate.ref), s"worker-$sequence-$n"))
    creator.awaitAssert(workers.forall(_.underlyingActor.ready) shouldBe true, 5.seconds, 10.millis)
    if (progressive) gtm ! RegisterProgressiveLoadManagerEvent(producer.ref, 100L)
    val namespace = classOf[LP].getName
    val ids = Iterator.from(sequence * 100).map(_.toLong).take(50).toVector
    val first = ids.find(id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 0).get
    val second = ids.find(id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 1).get
    val receiver = ids.find(id => id != first && id != second).get
    def lp(id: Long, tick: Long, target: Long, operation: String): ActorRef = {
      val ref = TestActorRef(new LP(Properties(entityId = id.toString, resourceId = "channels",
        actorType = CreationTypeEnum.PoolDistributed, runtimeCoordinator = Some(gtm),
        reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> workers.head)),
        target, operation, observer.ref), s"lp-$sequence-$id")
      creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      ref ! InitializeEvent(id.toString, creator.ref, InitializeData(ChannelState(tick), "channels", creator.ref,
        mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> workers.head),
        runtimeCoordinator = Some(gtm)))
      creator.expectMsgType[InitializeEntityAckEvent]
      ref
    }
    lp(receiver, 0L, 0L, "")
    if (progressive) {
      gtm ! StartSimulationTimeEvent(startTick = 0L)
      producer.expectMsgType[TickWindowRequest].currentTick shouldBe 0L
      producer.send(gtm, TickWindowReady(5L, 1L))
      producer.expectMsgType[TickWindowRequest].currentTick shouldBe 6L
      observer.expectNoMessage(100.millis)
    }
    val sources = Vector(lp(first, 10L, receiver, "add")) ++
      (if (twoSources) Vector(lp(second, 10L, receiver, "multiply")) else Vector.empty)
    val watcher = TestProbe()
    sources.foreach(watcher.watch)
    if (progressive) producer.send(gtm, TickWindowReady(11L, 1L))
    else gtm ! StartSimulationTimeEvent(startTick = 0L)
    val held = Vector.fill(if (twoSources) 2 else 1)(gate.expectMsgType[HeldChannel](5.seconds))
    observer.receiveN(held.size, 3.seconds).foreach { event =>
      event.asInstanceOf[(String, Long, Int)]._1 shouldBe "source-finished"
    }
    observer.expectNoMessage(100.millis)
    manager.expectNoMessage(100.millis)
    watcher.expectNoMessage(100.millis)

    val ordered = held.sortBy(_.transfer.event.order.get)
    val expected = ordered.foldLeft(1) { (value, h) => if (h.transfer.event.data == "add") value + 3 else value * 2 }
    val reversed = ordered.reverse
    reversed.head.target.tell(reversed.head.transfer, reversed.head.source)
    if (twoSources) {
      observer.expectNoMessage(100.millis)
      reversed.tail.foreach(h => h.target.tell(h.transfer, h.source))
    }
    val inputs = observer.receiveN(held.size, 5.seconds).map(_.asInstanceOf[(String, Long, Int)])
    inputs.foreach { case (kind, tick, _) => kind shouldBe "receiver-input"; tick shouldBe 10L }
    val receipts = Vector.fill(held.size)(sink.expectMsgType[CommittedReportDelivery])
    observer.expectNoMessage(100.millis)
    receipts.foreach(d => sink.send(d.ackTo, DeliveryAccepted(d.epoch, d.token)))
    if (progressive) {
      producer.expectMsgType[TickWindowRequest].currentTick shouldBe 12L
      producer.send(gtm, TickWindowReady(100L, 0L))
    }
    observer.expectMsg(("receiver-wake", 20L, expected))
    watcher.receiveN(sources.size, 3.seconds).map { case Terminated(ref) => ref }.toSet shouldBe sources.toSet
    val finalReceipt = sink.expectMsgType[CommittedReportDelivery]
    manager.expectNoMessage(100.millis)
    sink.send(finalReceipt.ackTo, DeliveryAccepted(finalReceipt.epoch, finalReceipt.token))
    if (progressive) {
      producer.expectMsgType[TickWindowRequest].currentTick shouldBe 101L
      manager.expectNoMessage(100.millis)
      producer.send(gtm, TickWindowReady(Long.MaxValue, 0L))
      producer.send(gtm, TickWindowReady(100L, 0L))
    }
    val drain = reports.expectMsgType[DrainRuntimeReports](5.seconds)
    drain.targets shouldBe Set(sink.ref)
    manager.expectNoMessage(100.millis)
    reports.send(gtm, RuntimeReportsFlushed(drain.round))
    manager.expectMsg(StopSimulationEvent())
  }

  "conservative channel closure" should "hold the frontier after a fire-and-forget sender finishes until its delivery is accepted" in {
    run(false)
  }
  it should "sort reversed same-tick inputs before executing them across two LTMs" in {
    run(true)
  }
  it should "retain progressive input boundaries and wait for producer exhaustion before terminating" in {
    run(false, progressive = true)
  }
}
