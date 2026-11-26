package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.{BicycleState, BusState, CarState, MotorcycleState, MovableState}
import model.hybrid.entity.state.enumeration.{ActorTypeEnum, EventTypeEnum, MovableStatusEnum}
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{Moving, Waiting, WaitingCapacity, WaitingSignal, WaitingSignalState}
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.compiletime.uninitialized

class VehicleQueuedWakeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private var systemInstance: ActorSystem = uninitialized
  private implicit def system: ActorSystem = systemInstance
  override def beforeAll(): Unit = {
    systemInstance = ActorSystem("hybrid-VehicleQueuedWakeSpec", ConfigFactory.parseString(
      "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  }
  override def afterAll(): Unit = { systemInstance.terminate(); () }

  private def key(status: MovableStatusEnum): String =
    if (status == Waiting) Movable.enterLinkAwaitKey(101L) else "link-access:102"
  private def kind(status: MovableStatusEnum): String =
    if (status == Waiting) "EnterLinkData" else "LinkAccessData"
  private def configure(state: MovableState, status: MovableStatusEnum): Unit = {
    state.movableStatus = status
    state.movableCurrentPath = Some(101L -> 2L)
    state.movableBestRoute = Some(mutable.Queue(102L -> 3L))
  }

  private case class Probe(
    state: MovableState,
    finishes: mutable.Buffer[Option[Tick]],
    schedules: mutable.Buffer[Tick],
    pending: () => Option[Long],
    wake: Tick => Unit,
    reply: ActorInteractionEvent => Unit
  )

  private class ObservedCar extends Car(Properties(entityId = "3277992", resourceId = "test",
    reporters = mutable.Map.empty)) {
    val finishes = mutable.Buffer.empty[Option[Tick]]
    val schedules = mutable.Buffer.empty[Tick]
    override protected def registerOnTimeManager(tick: Tick): Unit = ()
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit = {
      finishes += scheduleTick
      super.onFinishSpontaneous(scheduleTick, destruct)
    }
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean): Unit = schedules += tick
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def reportMirrored(data: Any, label: String = null): Unit = ()
    def probe(mode: String, status: MovableStatusEnum): Probe = {
      currentTimeManagerType = mode
      currentTick = 100L
      state = CarState(startTick = 0L, origin = 1L, destination = 3L, actorType = ActorTypeEnum.Car, size = 2.0)
      configure(state, status)
      registerAwaitedReply(key(status), kind(status))
      Probe(state, finishes, schedules, () => awaitedReplySendSeq(key(status)),
        tick => {
          currentTick = tick
          actSpontaneous(SpontaneousEvent(tick = tick, actorRef = system.deadLetters))
        }, event => {
          currentTick = event.tick
          actInteractWith(event)
        })
    }
  }

  private class ObservedBus extends Bus(Properties(entityId = "3277992", resourceId = "test",
    reporters = mutable.Map.empty)) {
    val finishes = mutable.Buffer.empty[Option[Tick]]
    val schedules = mutable.Buffer.empty[Tick]
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit = {
      finishes += scheduleTick
      super.onFinishSpontaneous(scheduleTick, destruct)
    }
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean): Unit = schedules += tick
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def reportMirrored(data: Any, label: String = null): Unit = ()
    def probe(mode: String, status: MovableStatusEnum): Probe = {
      currentTimeManagerType = mode
      currentTick = 100L
      state = BusState(startTick = 0L, label = "line", capacity = 40, busStops = Map.empty, numberOfPorts = 2, origin = 1L, destination = 3L, size = 12.0)
      configure(state, status)
      registerAwaitedReply(key(status), kind(status))
      Probe(state, finishes, schedules, () => awaitedReplySendSeq(key(status)),
        tick => {
          currentTick = tick
          actSpontaneous(SpontaneousEvent(tick = tick, actorRef = system.deadLetters))
        }, event => {
          currentTick = event.tick
          actInteractWith(event)
        })
    }
  }

  private class ObservedMotorcycle extends Motorcycle(Properties(entityId = "3277992", resourceId = "test",
    reporters = mutable.Map.empty)) {
    val finishes = mutable.Buffer.empty[Option[Tick]]
    val schedules = mutable.Buffer.empty[Tick]
    override protected def registerOnTimeManager(tick: Tick): Unit = ()
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit = {
      finishes += scheduleTick
      super.onFinishSpontaneous(scheduleTick, destruct)
    }
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean): Unit = schedules += tick
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def reportMirrored(data: Any, label: String = null): Unit = ()
    def probe(mode: String, status: MovableStatusEnum): Probe = {
      currentTimeManagerType = mode
      currentTick = 100L
      state = MotorcycleState(startTick = 0L, origin = 1L, destination = 3L, actorType = ActorTypeEnum.Motorcycle, size = 2.0)
      configure(state, status)
      registerAwaitedReply(key(status), kind(status))
      Probe(state, finishes, schedules, () => awaitedReplySendSeq(key(status)),
        tick => {
          currentTick = tick
          actSpontaneous(SpontaneousEvent(tick = tick, actorRef = system.deadLetters))
        }, event => {
          currentTick = event.tick
          actInteractWith(event)
        })
    }
  }

  private class ObservedBicycle extends Bicycle(Properties(entityId = "3277992", resourceId = "test",
    reporters = mutable.Map.empty)) {
    val finishes = mutable.Buffer.empty[Option[Tick]]
    val schedules = mutable.Buffer.empty[Tick]
    override protected def registerOnTimeManager(tick: Tick): Unit = ()
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit = {
      finishes += scheduleTick
      super.onFinishSpontaneous(scheduleTick, destruct)
    }
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean): Unit = schedules += tick
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def reportMirrored(data: Any, label: String = null): Unit = ()
    def probe(mode: String, status: MovableStatusEnum): Probe = {
      currentTimeManagerType = mode
      currentTick = 100L
      state = BicycleState(startTick = 0L, origin = 1L, destination = 3L, actorType = ActorTypeEnum.Bicycle, size = 2.0)
      configure(state, status)
      registerAwaitedReply(key(status), kind(status))
      Probe(state, finishes, schedules, () => awaitedReplySendSeq(key(status)),
        tick => {
          currentTick = tick
          actSpontaneous(SpontaneousEvent(tick = tick, actorRef = system.deadLetters))
        }, event => {
          currentTick = event.tick
          actInteractWith(event)
        })
    }
  }

  private var sequence = 4000000L
  private def newProbe(actor: String, mode: String, status: MovableStatusEnum): Probe = {
    sequence += 1
    val name = sequence.toString
    actor match {
      case "Car" => TestActorRef(new ObservedCar, name).underlyingActor.probe(mode, status)
      case "Bus" => TestActorRef(new ObservedBus, name).underlyingActor.probe(mode, status)
      case "Motorcycle" => TestActorRef(new ObservedMotorcycle, name).underlyingActor.probe(mode, status)
      case "Bicycle" => TestActorRef(new ObservedBicycle, name).underlyingActor.probe(mode, status)
    }
  }

  for {
    actor <- Seq("Car", "Bus", "Motorcycle", "Bicycle")
    mode <- Seq(TimeManagerTypeEnum.DISCRETE_EVENT, TimeManagerTypeEnum.TIME_WARP)
    status <- Seq(Waiting, WaitingSignalState, WaitingCapacity)
  } {
    s"$actor under $mode in $status" should "keep its route and pending reply across more than 100 queued wakes, then resume on the reply" in {
      val p = newProbe(actor, mode, status)
      val requestSeq = p.pending().get
      for (tick <- 100L to 201L) p.wake(tick)

      p.finishes.toList shouldBe List.fill(102)(None)
      p.schedules shouldBe empty
      p.pending() shouldBe Some(requestSeq)
      p.state.movableStatus shouldBe status
      p.state.movableCurrentPath shouldBe Some(101L -> 2L)
      p.state.movableBestRoute.get.toList shouldBe List(102L -> 3L)

      val reply = ActorInteractionEvent(tick = 205L, lamportTick = 205L,
        actorRefId = if (status == Waiting) 101L else 2L,
        shardRefId = if (status == Waiting) "hybrid.actor.Link" else "hybrid.actor.Node",
        actorPathRef = "", actorClassType = "test",
        eventType = if (status == Waiting) EventTypeEnum.ReceiveEnterLinkInfo.toString else EventTypeEnum.ReceiveLinkAccess.toString,
        data = if (status == Waiting) LinkInfoData(linkLength = 300.0, linkCapacity = 20.0,
          linkNumberOfCars = 1, linkFreeSpeed = 30.0, linkLanes = 1, requestSeq = requestSeq)
          else LinkAccessData(Green, 205L, requestSeq = requestSeq), resourceId = "test")
      p.reply(reply)

      p.pending() shouldBe None
      p.state.movableCurrentPath shouldBe Some(101L -> 2L)
      p.state.movableBestRoute.get.toList shouldBe List(102L -> 3L)
      if (status == Waiting) {
        p.state.movableStatus shouldBe Moving
        p.finishes.last.get should be > 205L
      } else {
        p.state.movableStatus shouldBe WaitingSignal
        p.schedules.toList shouldBe List(205L)
      }
    }
  }
}
