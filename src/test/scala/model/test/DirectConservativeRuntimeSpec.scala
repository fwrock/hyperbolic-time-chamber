package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.api.SimulatorSettingsRegistry
import core.actor.manager.time.{ ChannelClosedGlobalTimeManager, DirectConservativeLocalTimeManager }
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.load.{ PostLoadRegistrationAckEvent, PostLoadRegistrationEvent }
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.metrics.core.SimulationMetricSet
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class DirectTestState(startTick: Long = 0L, active: Boolean = false, value: Int = 0)
    extends BaseState(startTick = startTick, scheduleOnTimeManager = active)
private case class HeldDirect(source: ActorRef, target: Long, delivery: DirectExecute)
private case class HeldCompletion(source: ActorRef, receipt: DirectProcessed)
private case class ObservedDirect(id: Long, tick: Long, kind: String)
private case class ReleaseCompletion(receipt: DirectProcessed)
private case class DirectNoOpReply(requestSeq: Long)
private case class BootstrapObserved(id: Long, value: Int, kind: String)

class DirectConservativeRuntimeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "DirectConservativeRuntimeSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class Global(manager: ActorRef, reporter: ActorRef)
      extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override protected def expectedWorkers: Int = 2
    override protected def simulationMetrics: SimulationMetricSet = new SimulationMetricSet(
      new CollectorRegistry()
    )
    override protected lazy val reportManagerRef: ActorRef = reporter
    override def onStart(): Unit = ()
  }

  private class DefaultFactory(manager: ActorRef, observer: ActorRef)
      extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override def onStart(): Unit = observer ! localTimeManagerProps().actorClass()
  }

  "the conservative default" should "select direct workers without a configuration override" in {
    SimulatorSettingsRegistry.catalog
      .find(_.configPath == "htc.time-manager.conservative-transport")
      .get
      .defaultValue shouldBe "direct"
    val observer = TestProbe()
    val global = system.actorOf(Props(new DefaultFactory(TestProbe().ref, observer.ref)))
    observer.expectMsg(classOf[DirectConservativeLocalTimeManager])
    system.stop(global)
  }

  private class LP(
    props: Properties,
    target: Long,
    gate: ActorRef,
    observer: ActorRef,
    holdCompletion: Boolean,
    wakeAfterInput: Boolean
  ) extends SimulationBaseActor[DirectTestState](props) {
    override protected def internStateStrings(value: DirectTestState): DirectTestState = value
    override protected def deliverDirectInteraction(
      receiver: LogicalProcessId,
      kind: CreationTypeEnum,
      delivery: DirectExecute
    ): Unit = gate ! HeldDirect(self, receiver.id, delivery)
    override protected def publishDirectCompletion(receipt: DirectProcessed): Unit =
      if (
        holdCompletion && receipt.completion.work.parent.isEmpty && receipt.completion.work.tick == 10L
      )
        gate ! HeldCompletion(self, receipt)
      else super.publishDirectCompletion(receipt)
    override protected def handleEvent: Receive = {
      case ReleaseCompletion(receipt) => super.publishDirectCompletion(receipt)
    }
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      observer ! ObservedDirect(getEntityId.toLong, currentTick, "wake")
      if (currentTick == 10L) {
        sendMessageTo(
          target,
          getClass.getName,
          "notify",
          "notify",
          CreationTypeEnum.PoolDistributed
        )
        onFinishSpontaneous(Some(20L))
      } else onFinishSpontaneous(None)
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(value = state.value + 1)
      observer ! ObservedDirect(getEntityId.toLong, currentTick, "input")
      if (target != 0L)
        sendMessageTo(
          target,
          getClass.getName,
          "notify",
          "notify",
          CreationTypeEnum.PoolDistributed
        )
      if (wakeAfterInput) scheduleEvent(currentTick + 5L)
    }
  }

  private var nextRun = 0
  private def exercise(earlyCompletion: Boolean): Unit = {
    nextRun += 1
    val manager = TestProbe(); val reporter = TestProbe(); val observer = TestProbe();
    val gate = TestProbe()
    val creator = TestProbe()
    val global =
      system.actorOf(Props(new Global(manager.ref, reporter.ref)), s"direct-global-$nextRun")
    val workers = Vector.tabulate(2)(
      index =>
        system.actorOf(
          DirectConservativeLocalTimeManager.props(100L, manager.ref, global),
          s"direct-owner-$nextRun-$index"
        )
    )
    val namespace = classOf[LP].getName
    val candidates = (nextRun * 1000L until nextRun * 1000L + 100L).toVector
    def bucket(id: Long): Int = Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2)
    val first = candidates.find(bucket(_) == 0).get
    val second = candidates.find(bucket(_) == 1).get
    val third = candidates
      .find(
        id => id != first && bucket(id) == 0
      )
      .get
    def create(id: Long, target: Long, active: Boolean): ActorRef = {
      val props = Properties(
        entityId = id.toString,
        resourceId = "direct-test",
        actorType = CreationTypeEnum.PoolDistributed,
        runtimeCoordinator = Some(global),
        timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> workers.head)
      )
      val actor = system.actorOf(
        Props(
          new LP(
            props,
            target,
            gate.ref,
            observer.ref,
            holdCompletion = active && earlyCompletion,
            wakeAfterInput = id == second && !earlyCompletion
          )
        ),
        id.toString
      )
      actor ! InitializeEvent(
        id.toString,
        creator.ref,
        InitializeData(
          DirectTestState(if (active) 10L else 0L, active),
          "direct-test",
          creator.ref,
          mutable.Map.empty,
          timeManagers = props.timeManagers,
          runtimeCoordinator = Some(global)
        )
      )
      creator.expectMsgType[InitializeEntityAckEvent](10.seconds)
      actor
    }
    val receiver = create(second, if (earlyCompletion) 0L else third, false)
    val downstream = create(third, 0L, false)
    val source = create(first, second, true)
    global ! StartSimulationTimeEvent(startTick = 0L)
    observer.expectMsg(ObservedDirect(first, 10L, "wake"))
    val held = gate.expectMsgType[HeldDirect](5.seconds)
    observer.expectNoMessage(100.millis)
    held.target shouldBe second
    if (earlyCompletion) {
      val completion = gate.expectMsgType[HeldCompletion]
      receiver.tell(held.delivery, held.source)
      observer.expectMsg(ObservedDirect(second, 10L, "input"))
      // Duplicate direct delivery must resend accounting completion without repeating the model.
      receiver.tell(held.delivery, held.source)
      observer.expectNoMessage(150.millis)
      source ! ReleaseCompletion(completion.receipt)
    } else {
      receiver.tell(held.delivery, held.source)
      observer.expectMsg(ObservedDirect(second, 10L, "input"))
      val child = gate.expectMsgType[HeldDirect]
      child.target shouldBe third
      observer.expectNoMessage(150.millis)
      manager.expectNoMessage(100.millis)
      downstream.tell(child.delivery, child.source)
      observer.expectMsg(ObservedDirect(third, 10L, "input"))
      observer.expectMsg(ObservedDirect(second, 15L, "wake"))
    }
    observer.expectMsg(5.seconds, ObservedDirect(first, 20L, "wake"))
    val drain = reporter.expectMsgType[DrainRuntimeReports](5.seconds)
    manager.expectNoMessage(100.millis)
    reporter.send(global, RuntimeReportsFlushed(drain.round))
    manager.expectMsgType[StopSimulationEvent](5.seconds)
    system.stop(global); workers.foreach(system.stop)
  }

  "direct conservative execution" should "wait for delayed cross-owner descendants before releasing a later tick" in {
    exercise(false)
  }

  it should "accept B completion before A finish and execute a duplicate delivery only once" in {
    exercise(true)
  }

  private class RequestLP(props: Properties, target: Long, answer: Boolean, observer: ActorRef)
      extends SimulationBaseActor[DirectTestState](props) {
    override protected def internStateStrings(value: DirectTestState): DirectTestState = value
    override protected def actSpontaneous(event: SpontaneousEvent): Unit =
      requestInteraction(
        target,
        getClass.getName,
        "ask",
        "ask",
        "answer",
        "answer",
        CreationTypeEnum.PoolDistributed
      )
    override def actInteractWith(event: ActorInteractionEvent): Unit = event.data match {
      case "ask" if answer => replyInteraction(event, DirectNoOpReply(event.seq), "answer")
      case "ask"           => ()
      case response: DirectNoOpReply =>
        require(
          isAwaitingReply("answer", "answer", response.requestSeq),
          "response lost its request identity"
        )
        consumeAwaitedReply("answer")
        observer ! "answered"
        onFinishSpontaneous(None)
      case other => throw new IllegalArgumentException(s"unknown request test payload: $other")
    }
  }

  private def exerciseRequest(answer: Boolean): Unit = {
    nextRun += 1
    val manager = TestProbe(); val reporter = TestProbe(); val creator = TestProbe();
    val observer = TestProbe()
    val global =
      system.actorOf(Props(new Global(manager.ref, reporter.ref)), s"request-global-$nextRun")
    val workers = Vector.tabulate(2)(
      index =>
        system.actorOf(
          DirectConservativeLocalTimeManager.props(100L, manager.ref, global),
          s"request-owner-$nextRun-$index"
        )
    )
    val namespace = classOf[RequestLP].getName
    val ids = (nextRun * 1000L until nextRun * 1000L + 100L).toVector
    val first = ids
      .find(
        id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 0
      )
      .get
    val second = ids
      .find(
        id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 1
      )
      .get
    val actors = Vector(first, second).map {
      id =>
        val props = Properties(
          entityId = id.toString,
          resourceId = "request-test",
          actorType = CreationTypeEnum.PoolDistributed,
          runtimeCoordinator = Some(global),
          timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> workers.head)
        )
        val actor =
          system.actorOf(Props(new RequestLP(props, second, answer, observer.ref)), id.toString)
        actor ! InitializeEvent(
          id.toString,
          creator.ref,
          InitializeData(
            DirectTestState(0L, id == first),
            "request-test",
            creator.ref,
            mutable.Map.empty,
            timeManagers = props.timeManagers,
            runtimeCoordinator = Some(global)
          )
        )
        creator.expectMsgType[InitializeEntityAckEvent](10.seconds)
        actor
    }
    global ! StartSimulationTimeEvent(startTick = 0L)
    if (answer) {
      observer.expectMsg("answered")
      val drain = reporter.expectMsgType[DrainRuntimeReports]
      reporter.send(global, RuntimeReportsFlushed(drain.round))
      manager.expectMsgType[StopSimulationEvent]
    } else {
      manager.expectMsgType[RuntimeFailure].reason should include("unresolved replies")
      observer.expectNoMessage(100.millis)
    }
    actors.foreach(system.stop); workers.foreach(system.stop); system.stop(global)
  }

  it should "complete an explicitly correlated request even when the answer makes no model change" in {
    exerciseRequest(true)
  }

  it should "fail a missing business response rather than declaring successful completion" in {
    exerciseRequest(false)
  }

  private class BootstrapLP(props: Properties, target: Long, observer: ActorRef)
      extends SimulationBaseActor[DirectTestState](props) {
    override protected def internStateStrings(value: DirectTestState): DirectTestState = value
    override protected def handlePostLoadRegistration(): Unit =
      sendMessageTo(
        target,
        getClass.getName,
        "register",
        "register",
        CreationTypeEnum.PoolDistributed
      )
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      observer ! BootstrapObserved(getEntityId.toLong, state.value, "wake")
      onFinishSpontaneous(None)
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(value = state.value + 1)
      observer ! BootstrapObserved(getEntityId.toLong, state.value, "registered")
    }
  }

  it should "process accepted startup registrations before releasing the first model wakes" in {
    nextRun += 1
    val manager = TestProbe(); val reporter = TestProbe(); val creator = TestProbe()
    val observer = TestProbe(); val registration = TestProbe()
    val global =
      system.actorOf(Props(new Global(manager.ref, reporter.ref)), s"bootstrap-global-$nextRun")
    val workers = Vector.tabulate(2)(
      index =>
        system.actorOf(
          DirectConservativeLocalTimeManager.props(100L, manager.ref, global),
          s"bootstrap-owner-$nextRun-$index"
        )
    )
    val namespace = classOf[BootstrapLP].getName
    val ids = (nextRun * 1000L until nextRun * 1000L + 100L).toVector
    val first = ids
      .find(
        id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 0
      )
      .get
    val second = ids
      .find(
        id => Math.floorMod(LogicalProcessId(namespace, id).hashCode(), 2) == 1
      )
      .get
    val actors = Vector(first, second).map {
      id =>
        val props = Properties(
          entityId = id.toString,
          resourceId = "bootstrap-test",
          actorType = CreationTypeEnum.PoolDistributed,
          runtimeCoordinator = Some(global),
          timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> workers.head)
        )
        val actor = system.actorOf(Props(new BootstrapLP(props, second, observer.ref)), id.toString)
        actor ! InitializeEvent(
          id.toString,
          creator.ref,
          InitializeData(
            DirectTestState(0L, true),
            "bootstrap-test",
            creator.ref,
            mutable.Map.empty,
            timeManagers = props.timeManagers,
            runtimeCoordinator = Some(global)
          )
        )
        creator.expectMsgType[InitializeEntityAckEvent](10.seconds)
        actor
    }
    actors.head ! PostLoadRegistrationEvent(registration.ref)
    registration.expectMsgType[PostLoadRegistrationAckEvent]
    observer.expectNoMessage(100.millis)
    global ! StartSimulationTimeEvent(startTick = 0L)
    val observed = observer.receiveN(3, 5.seconds).map(_.asInstanceOf[BootstrapObserved])
    observed should contain(BootstrapObserved(second, 1, "registered"))
    observed should contain(BootstrapObserved(first, 0, "wake"))
    observed should contain(BootstrapObserved(second, 1, "wake"))
    val drain = reporter.expectMsgType[DrainRuntimeReports]
    reporter.send(global, RuntimeReportsFlushed(drain.round))
    manager.expectMsgType[StopSimulationEvent]
    system.stop(global); workers.foreach(system.stop)
  }
}
