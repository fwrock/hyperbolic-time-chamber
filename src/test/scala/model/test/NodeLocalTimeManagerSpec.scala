package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.time.ChannelClosedGlobalTimeManager
import core.actor.manager.time.partition.NodeLocalTimeManager
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
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

private object NodeLocalFixtures {
  case class State(startTick: Long = 10L, active: Boolean = true)
      extends BaseState(startTick = startTick, scheduleOnTimeManager = active)
  case class Wake(id: Long, tick: Long)
  case class Receipt(id: Long, tick: Long, owner: String)
  case class Held(source: ActorRef, target: Long, delivery: DirectExecute)
}

class NodeLocalTimeManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  import NodeLocalFixtures.*
  private implicit val system: ActorSystem = ActorSystem(
    "NodeLocalTimeManagerSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class Global(manager: ActorRef, reporter: ActorRef)
      extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override protected lazy val nodeLocalScheduling: Boolean = true
    override protected def expectedWorkers: Int = 1
    override protected def simulationMetrics: SimulationMetricSet = new SimulationMetricSet(
      new CollectorRegistry()
    )
    override protected lazy val reportManagerRef: ActorRef = reporter
    override def onStart(): Unit = ()
  }

  private class LP(
    props: Properties,
    observer: ActorRef,
    gate: ActorRef,
    target: Long,
    mode: String
  ) extends SimulationBaseActor[State](props) {
    override protected def internStateStrings(value: State): State = value
    override protected def deliverDirectInteraction(
      receiver: LogicalProcessId,
      kind: CreationTypeEnum,
      delivery: DirectExecute
    ): Unit = gate ! Held(self, receiver.id, delivery)
    override protected def publishDirectCompletion(receipt: DirectProcessed): Unit = {
      if (receipt.completion.work.parent.isEmpty)
        observer ! Receipt(
          getEntityId.toLong,
          receipt.completion.work.tick,
          receipt.accountingOwner.path.name
        )
      super.publishDirectCompletion(receipt)
    }
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      observer ! Wake(getEntityId.toLong, currentTick)
      if (currentTick == 10L && mode == "schedules") {
        scheduleEvent(11L)
        scheduleEvent(11L)
        onFinishSpontaneous(Some(12L))
      } else if (currentTick == 10L && mode == "continuation") {
        scheduleEvent(currentTick)
        scheduleEvent(currentTick)
        onFinishSpontaneous(None)
      } else if (currentTick == 10L && mode == "cascade") {
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
      observer ! Wake(getEntityId.toLong, currentTick)
      scheduleEvent(11L)
    }
  }

  private var sequence = 0
  private def run(count: Int, mode: String, delayed: Boolean): Unit = {
    sequence += 1
    val manager = TestProbe(); val reporter = TestProbe(); val observer = TestProbe()
    val creator = TestProbe(); val gate = TestProbe()
    val global = system.actorOf(Props(new Global(manager.ref, reporter.ref)))
    val node = system.actorOf(NodeLocalTimeManager.props(100L, manager.ref, global, 3))
    val ids = Vector.tabulate(count)(
      index => sequence * 1000L + index
    )
    val actors = ids.map {
      id =>
        val props = Properties(
          entityId = id.toString,
          resourceId = "node-local-test",
          actorType = CreationTypeEnum.PoolDistributed,
          runtimeCoordinator = Some(global),
          timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> node)
        )
        val actor =
          system.actorOf(Props(new LP(props, observer.ref, gate.ref, ids.last, mode)), id.toString)
        actor ! InitializeEvent(
          id.toString,
          creator.ref,
          InitializeData(
            State(active = !delayed || id == ids.head),
            "node-local-test",
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
    if (delayed) {
      observer.expectMsg(Wake(ids.head, 10L))
      observer.expectMsgType[Receipt]
      val held = gate.expectMsgType[Held](5.seconds)
      observer.expectNoMessage(150.millis)
      reporter.expectNoMessage(100.millis)
      actors.last.tell(held.delivery, held.source)
      observer.expectMsg(Wake(ids.last, 10L))
      actors.last.tell(held.delivery, held.source)
    }
    val drain = reporter.expectMsgType[DrainRuntimeReports](10.seconds)
    manager.expectNoMessage(100.millis)
    reporter.send(global, RuntimeReportsFlushed(drain.round))
    manager.expectMsgType[StopSimulationEvent](5.seconds)
    val observed = observer.receiveWhile(500.millis) {
      case value => value
    }
    if (mode == "schedules" || mode == "continuation") {
      ids.foreach {
        id =>
          observed.collect {
            case Wake(`id`, tick) => tick
          }.sorted shouldBe (if (mode == "continuation") Vector(10L, 11L)
                             else Vector(10L, 11L, 12L))
      }
      val initial = observed.collect {
        case Receipt(_, 10L, owner) => owner
      }
      initial.groupMapReduce(identity)(
        _ => 1
      )(_ + _) shouldBe
        Map(
          "partition-0" -> (count / 3),
          "partition-1" -> (count / 3),
          "partition-2" -> (count / 3)
        )
    } else {
      observed.collect {
        case Wake(id, tick) => id -> tick
      } shouldBe Vector(ids.last -> 11L, ids.head -> 20L)
    }
    actors.foreach(system.stop)
    system.stop(node)
    system.stop(global)
  }

  "node-local scheduling" should "balance each tick, coalesce wakes and preserve schedules across finishes" in {
    run(6, "schedules", delayed = false)
  }

  it should "hold a later tick for a delayed descendant and execute a duplicate direct input once" in {
    run(2, "cascade", delayed = true)
  }

  it should "continue claiming after a partition fills its dispatch window" in {
    run(99, "schedules", delayed = false)
  }

  it should "normalize a current-tick continuation after dispatch without losing or duplicating the wake" in {
    run(3, "continuation", delayed = false)
  }

  it should "fail the run when a local partition is lost" in {
    val manager = TestProbe()
    val global = system.actorOf(Props(new Global(manager.ref, TestProbe().ref)))
    val node = system.actorOf(NodeLocalTimeManager.props(100L, manager.ref, global, 3))
    val partition = TestProbe()
    system
      .actorSelection(node.path / "partition-0")
      .tell(org.apache.pekko.actor.Identify("worker"), partition.ref)
    val ref = partition.expectMsgType[org.apache.pekko.actor.ActorIdentity].ref.get
    system.stop(ref)
    manager.expectMsgType[RuntimeFailure](5.seconds).reason should include("partition lost")
    system.stop(node)
    system.stop(global)
  }
}
