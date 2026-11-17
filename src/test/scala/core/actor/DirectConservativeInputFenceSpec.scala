package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class DirectFenceState(startTick: Long = 0L, value: Int = 0)
    extends BaseState(startTick, scheduleOnTimeManager = true)

class DirectConservativeInputFenceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "DirectConservativeInputFenceSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class LP(props: Properties, observer: ActorRef)
      extends SimulationBaseActor[DirectFenceState](props) {
    override protected def internStateStrings(value: DirectFenceState): DirectFenceState = value
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = onFinishSpontaneous(
      Some(20L)
    )
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(value = state.value + 1)
      observer ! state.value
      onFinishSpontaneous(Some(30L))
    }
  }

  "the direct input fence" should "reject old deliveries after a canceled wake advanced runtime time without advancing model time" in {
    val global = TestProbe(); val owner = TestProbe(); val creator = TestProbe();
    val observer = TestProbe()
    owner.ignoreMsg { case _: PublishConservativePlacement => true; case _ => false }
    val id = LogicalProcessId(classOf[LP].getName, 771L)
    val props = Properties(
      entityId = "771",
      actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(global.ref),
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> owner.ref)
    )
    val actor = system.actorOf(Props(new LP(props, observer.ref)), "771")
    actor ! InitializeEvent(
      "771",
      creator.ref,
      InitializeData(
        DirectFenceState(),
        "fence",
        creator.ref,
        mutable.Map.empty,
        timeManagers = props.timeManagers,
        runtimeCoordinator = Some(global.ref)
      )
    )
    global.expectMsgType[JoinRuntime](5.seconds)
    global.send(actor, RuntimeJoined("run", global.ref, owner.ref, directConservative = true))
    creator.expectMsgType[InitializeEntityAckEvent]
    val root = DirectWork(DirectWorkId(id, "root", -1L), None, id, 0L)
    owner.send(
      actor,
      DirectExecute(
        "run",
        root,
        owner.ref,
        SpontaneousEvent(
          0L,
          owner.ref,
          generation = 1L,
          scheduleRevision = 0L,
          order = Some(EventOrder(0L, 0L, "root"))
        )
      )
    )
    owner.expectMsgType[DirectProcessed]
    val source = LogicalProcessId("example.Source", 99L)
    val interaction = ActorInteractionEvent(
      10L,
      1L,
      99L,
      source.model,
      "99",
      source.model,
      data = "increment",
      resourceId = "fence",
      sourceEpoch = "run",
      sourceIncarnation = "source"
    )
    val child = DirectWork(DirectWorkId(source, "source", 1L), Some(root.id), id, 10L)
    val delivery = DirectExecute("run", child, owner.ref, interaction)
    owner.send(actor, delivery)
    observer.expectMsg(1)
    owner.expectMsgType[DirectProcessed]
    val canceled = root.copy(id = root.id.copy(sequence = -2L), tick = 20L)
    owner.send(
      actor,
      DirectExecute(
        "run",
        canceled,
        owner.ref,
        SpontaneousEvent(
          20L,
          owner.ref,
          generation = 2L,
          scheduleRevision = 1L,
          order = Some(EventOrder(20L, 0L, "obsolete"))
        )
      )
    )
    val canceledReceipt = owner.expectMsgType[DirectProcessed]
    canceledReceipt.controls.collect {
      case finish: core.entity.event.FinishEvent => finish.discardedDispatch
    } shouldBe Vector(true)
    owner.send(actor, delivery)
    observer.expectNoMessage(100.millis)
    owner.expectNoMessage(100.millis)
    global.expectNoMessage(100.millis)
    system.stop(actor)
  }
}
