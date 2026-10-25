package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, AccountedOptimisticLocalTimeManager, ChannelClosedGlobalTimeManager, ChannelAwareLocalTimeManager}
import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.{BaseEventData, InitializeData}
import core.entity.state.BaseState
import core.enumeration.{CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum}
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class FamilyState(startTick: Long = 10L, depth: Int = 0, enabled: Boolean = true, trigger: Boolean = false)
  extends BaseState(startTick = startTick, scheduleOnTimeManager = !trigger)
private case object DisableFamily extends BaseEventData
private case object InjectFamilyStraggler
private case class FamilyExecuted(id: Long, enabled: Boolean)

class RuntimeBirthFamilySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("RuntimeBirthFamilySpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=60000")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class LP(props: Properties, observed: ActorRef) extends SimulationBaseActor[FamilyState](props) {
    def ready: Boolean = recoveryFinished
    override protected def internStateStrings(s: FamilyState): FamilyState = s
    override def receive: Receive = {
      val injection: Receive = { case InjectFamilyStraggler => scheduleEvent(5L) }
      injection.orElse(super.receive)
    }
    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      val id = getEntityId.toLong
      if (state.trigger) sendMessageTo(1L, getClass.getName, DisableFamily)
      else {
        if (state.enabled && state.depth < 2)
          spawnDynamicActor(getClass.getName, id + 1L, FamilyState(currentTick + 1L, state.depth + 1))
        report(Map("id" -> id, "enabled" -> state.enabled), "family-result")
        observed ! FamilyExecuted(id, state.enabled)
      }
      selfDestruct()
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = event.data match {
      case DisableFamily => state = state.copy(enabled = false)
      case _ => throw new IllegalArgumentException("unexpected family input")
    }
  }
  private class Initializer(owner: ActorRef, global: ActorRef, target: LogicalProcessId,
    origin: RuntimeBirthOrigin, data: RuntimeBirthData, observed: ActorRef) extends Actor {
    override def preStart(): Unit = {
      require(data.timeManagers.size == 1, "child must inherit only the selected policy")
      val managers = mutable.Map.from(data.timeManagers)
      val reporters = mutable.Map.from(data.reporters)
      val child = context.system.actorOf(Props(new LP(Properties(entityId = target.id.toString,
        actorType = CreationTypeEnum.PoolDistributed, runtimeCoordinator = Some(global),
        timeManagers = managers, reporters = reporters), observed)))
      child ! InitializeEvent(target.id.toString, self, InitializeData(data.stateJson, data.resourceId,
        self, reporters, mutable.Map.from(data.relationships), managers, Some(global), Some(origin)))
    }
    override def receive: Receive = {
      case _: InitializeEntityAckEvent => owner ! RuntimeBirthInitialized(target, sender())
      case StopRuntimeBirthInitializer => context.stop(self)
    }
  }
  private class OptimisticGTM(manager: ActorRef, sink: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    def ready: Boolean = recoveryFinished
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
  }
  private class ConservativeGTM(manager: ActorRef, sink: ActorRef) extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    def ready: Boolean = recoveryFinished
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
  }
  private class OptimisticWorker(manager: ActorRef, global: ActorRef, observed: ActorRef)
    extends AccountedOptimisticLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
    override protected def createRuntimeBirthInitializer(target: LogicalProcessId, origin: RuntimeBirthOrigin, data: RuntimeBirthData): ActorRef =
      context.actorOf(Props(new Initializer(self, global, target, origin, data, observed)))
  }
  private class ConservativeWorker(manager: ActorRef, global: ActorRef, observed: ActorRef)
    extends ChannelAwareLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
    override protected def createRuntimeBirthInitializer(target: LogicalProcessId, origin: RuntimeBirthOrigin, data: RuntimeBirthData): ActorRef =
      context.actorOf(Props(new Initializer(self, global, target, origin, data, observed)))
  }
  private def run(optimistic: Boolean, cancel: Boolean = false, legacyHandles: Boolean = false): Vector[Map[String, Any]] = {
    val manager = TestProbe(); val effects = TestProbe(); val creator = TestProbe(); val observed = TestProbe()
    val global: ActorRef = if (optimistic) {
      val ref = TestActorRef(new OptimisticGTM(manager.ref, effects.ref))
      creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis); ref
    } else {
      val ref = TestActorRef(new ConservativeGTM(manager.ref, effects.ref))
      creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis); ref
    }
    val workers = Vector.fill(2) {
      if (optimistic) {
        val ref = TestActorRef(new OptimisticWorker(manager.ref, global, observed.ref))
        creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis); ref: ActorRef
      } else {
        val ref = TestActorRef(new ConservativeWorker(manager.ref, global, observed.ref))
        creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis); ref: ActorRef
      }
    }
    val policy = if (optimistic) TimeManagerTypeEnum.TIME_WARP else TimeManagerTypeEnum.DISCRETE_EVENT
    def initialize(id: Long, state: FamilyState): ActorRef = {
      val managers = if (legacyHandles) {
        val alternate = if (optimistic) TimeManagerTypeEnum.DISCRETE_EVENT else TimeManagerTypeEnum.TIME_WARP
        mutable.LinkedHashMap(policy -> workers.head, alternate -> TestProbe().ref)
      } else mutable.Map(policy -> workers.head)
      val ref = TestActorRef(new LP(Properties(entityId = id.toString, actorType = CreationTypeEnum.PoolDistributed,
        runtimeCoordinator = Some(global), reporters = mutable.Map(ReportTypeEnum.parquet -> effects.ref),
        timeManagers = managers), observed.ref))
      creator.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      ref ! InitializeEvent(id.toString, creator.ref, InitializeData(state, "family", creator.ref,
        mutable.Map(ReportTypeEnum.parquet -> effects.ref), timeManagers = managers,
        runtimeCoordinator = Some(global)))
      creator.expectMsgType[InitializeEntityAckEvent]
      ref
    }
    val trigger = if (cancel) Some(initialize(99L, FamilyState(5L, trigger = true))) else None
    initialize(1L, FamilyState())
    global ! StartSimulationTimeEvent(startTick = 0L)
    if (optimistic) {
      observed.receiveN(3, 10.seconds).collect { case e: FamilyExecuted => e.id }.toSet shouldBe Set(1L, 2L, 3L)
      if (cancel) {
        trigger.get ! InjectFamilyStraggler
        observed.fishForMessage(10.seconds) { case FamilyExecuted(1L, false) => true; case _ => false }
      }
    }
    val heartbeat = if (optimistic) Some(system.scheduler.scheduleWithFixedDelay(1.millis, 10.millis,
      global, RequestRuntimeRound())(system.dispatcher)) else None
    val reports = Vector.newBuilder[Map[String, Any]]
    var drained = false
    effects.within(20.seconds) {
      while (!drained) effects.expectMsgType[AnyRef] match {
        case delivery: CommittedReportDelivery =>
          reports += delivery.event.data.asInstanceOf[Map[String, Any]]
          effects.send(delivery.ackTo, DeliveryAccepted(delivery.epoch, delivery.token))
        case drain: DrainRuntimeReports =>
          effects.send(global, RuntimeReportsFlushed(drain.round)); drained = true
        case other => fail(s"unexpected effect $other")
      }
    }
    manager.expectMsg(StopSimulationEvent())
    heartbeat.foreach(_.cancel())
    reports.result()
  }
  "runtime actor families" should "create and commit parent, child and grandchild under conservative execution" in {
    run(false).map(_("id").toString).sorted shouldBe Vector("1", "2", "3")
  }
  it should "create and commit the same family under optimistic execution" in {
    run(true).map(_("id").toString).sorted shouldBe Vector("1", "2", "3")
  }
  it should "cancel the creating event after both descendant generations executed without committing their reports" in {
    val reports = run(true, cancel = true)
    reports.map(_("id").toString) shouldBe Vector("1")
    reports.head("enabled") shouldBe false
  }
  for (optimistic <- Seq(false, true)) {
    it should s"pass only the selected policy to children when legacy handles exist (optimistic=$optimistic)" in {
      run(optimistic, legacyHandles = true).map(_("id").toString).sorted shouldBe Vector("1", "2", "3")
    }
  }

}
