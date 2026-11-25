package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.time.{ ChannelClosedGlobalTimeManager, DirectConservativeLocalTimeManager }
import core.metrics.core.SchedulingMetrics
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.metrics.core.SimulationMetricSet
import core.runtime.DelegatedWake
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

private case class DelegState(startTick: Long = 0L, active: Boolean = false)
    extends BaseState(startTick = startTick, scheduleOnTimeManager = active)
private case class DelegObserved(id: Long, tick: Long)

/** A delegate that lives on a different time manager than the LP it wakes. */
class DelegatedSchedulingCrossOwnerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "DelegatedSchedulingCrossOwnerSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class Global(manager: ActorRef, reporter: ActorRef)
      extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override protected lazy val nodeLocalScheduling: Boolean = false
    override protected def expectedWorkers: Int = 2
    override protected def simulationMetrics: SimulationMetricSet = new SimulationMetricSet(
      new CollectorRegistry()
    )
    override protected lazy val reportManagerRef: ActorRef = reporter
    override def onStart(): Unit = ()
  }

  private val directory = mutable.Map.empty[Long, ActorRef]

  private trait Routed { this: SimulationBaseActor[DelegState] =>
    override protected def internStateStrings(value: DelegState): DelegState = value
    override protected def deliverDirectInteraction(
      receiver: LogicalProcessId,
      kind: CreationTypeEnum,
      delivery: DirectExecute
    ): Unit = directory(receiver.id).tell(delivery, self)
  }

  /** Hands its wake to the delegate and ends its callback without a wake of its own. */
  private class Waiter(props: Properties, delegate: Long, observer: ActorRef, delay: Long)
      extends SimulationBaseActor[DelegState](props)
      with Routed {
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      observer ! DelegObserved(getEntityId.toLong, currentTick)
      if (currentTick == 10L) {
        val wake = delegateWake()
        sendMessageTo(
          delegate,
          classOf[Delegate].getName,
          (wake, delay),
          "delegate",
          CreationTypeEnum.PoolDistributed
        )
        finishAwaitingDelegate(wake)
      } else onFinishSpontaneous(None)
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = ()
  }

  private class Delegate(props: Properties) extends SimulationBaseActor[DelegState](props) with Routed {
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = onFinishSpontaneous(None)
    override def actInteractWith(event: ActorInteractionEvent): Unit = event.data match {
      case (wake: DelegatedWake, delay: Long) => scheduleDelegated(wake, currentTick + delay)
      case other                              => throw new IllegalArgumentException(s"unexpected payload: $other")
    }
  }

  private var run = 0

  private def exercise(waiters: Int, delay: Long): Unit = {
    run += 1
    val manager = TestProbe(); val reporter = TestProbe(); val observer = TestProbe(); val creator = TestProbe()
    val global = system.actorOf(Props(new Global(manager.ref, reporter.ref)), s"deleg-global-$run")
    val owners = Vector.tabulate(2)(
      index =>
        system.actorOf(
          DirectConservativeLocalTimeManager.props(100L, manager.ref, global),
          s"deleg-owner-$run-$index"
        )
    )
    def bucket(model: String, id: Long): Int = Math.floorMod(LogicalProcessId(model, id).hashCode(), 2)
    val ids = (run * 1000L until run * 1000L + 400L).toVector
    val delegateId = ids.find(bucket(classOf[Delegate].getName, _) == 0).get
    val waiterIds = ids.filter(id => id != delegateId && bucket(classOf[Waiter].getName, id) == 1).take(waiters)
    waiterIds.size shouldBe waiters

    def create(id: Long, active: Boolean, build: Properties => SimulationBaseActor[DelegState]): ActorRef = {
      val props = Properties(
        entityId = id.toString,
        resourceId = "delegated-test",
        actorType = CreationTypeEnum.PoolDistributed,
        runtimeCoordinator = Some(global),
        timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> owners.head)
      )
      val actor = system.actorOf(Props(build(props)), s"${build.hashCode}-$id-$run")
      directory(id) = actor
      actor ! InitializeEvent(
        id.toString,
        creator.ref,
        InitializeData(
          DelegState(if (active) 10L else 0L, active),
          "delegated-test",
          creator.ref,
          mutable.Map.empty,
          timeManagers = props.timeManagers,
          runtimeCoordinator = Some(global)
        )
      )
      creator.expectMsgType[InitializeEntityAckEvent](10.seconds)
      actor
    }

    create(delegateId, active = false, props => new Delegate(props))
    waiterIds.foreach(id => create(id, active = true, props => new Waiter(props, delegateId, observer.ref, delay)))
    val batchesBefore = SchedulingMetrics.delegatedBatch.get()
    global ! StartSimulationTimeEvent(startTick = 0L)

    // Order inside a tick is not part of the contract; every LP must wake at both ticks.
    val first = waiterIds.map(id => DelegObserved(id, 10L))
    val second = waiterIds.map(id => DelegObserved(id, 10L + delay))
    observer.receiveN(waiters, 10.seconds).toSet shouldBe first.toSet
    observer.receiveN(waiters, 10.seconds).toSet shouldBe second.toSet
    val drain = reporter.expectMsgType[DrainRuntimeReports](10.seconds)
    reporter.send(global, RuntimeReportsFlushed(drain.round))
    manager.expectMsgType[StopSimulationEvent](5.seconds)
    val batches = SchedulingMetrics.delegatedBatch.get() - batchesBefore
    batches should (be >= 1.0 and be <= waiters.toDouble)
    system.stop(global); owners.foreach(system.stop)
  }

  "a delegated wake" should "reach an LP owned by another time manager" in {
    exercise(waiters = 1, delay = 5L)
  }

  it should "reach every LP when several are woken in the same tick" in {
    exercise(waiters = 8, delay = 5L)
  }

  it should "reach every LP when the delay is a single tick" in {
    exercise(waiters = 8, delay = 1L)
  }
}
