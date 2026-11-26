package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.BicycleState
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum.{Available, Full}
import model.hybrid.entity.state.enumeration.MovableStatusEnum
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{WaitingCapacity, WaitingSignal, WaitingSignalState}
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green, Red}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

class BicycleReplyWaitSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private var systemInstance: ActorSystem = uninitialized
  private implicit def system: ActorSystem = systemInstance

  override def beforeAll(): Unit = {
    systemInstance = ActorSystem("BicycleReplyWaitSpec", ConfigFactory.parseString(
      "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  }
  override def afterAll(): Unit = { systemInstance.terminate(); () }

  private class ObservedBicycle extends Bicycle(Properties(entityId = "3277992", resourceId = "test")) {
    val finishes = mutable.Buffer.empty[Option[Tick]]
    val schedules = mutable.Buffer.empty[Tick]
    val warnings = mutable.Buffer.empty[String]
    var leaves = 0
    override protected def registerOnTimeManager(tick: Tick): Unit = ()
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit = {
      finishes += scheduleTick
      super.onFinishSpontaneous(scheduleTick, destruct)
    }
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean): Unit = schedules += tick
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def logWarn(message: String): Unit = warnings += message
    override def leavingLink(): Unit = leaves += 1

    def prepare(mode: String, status: MovableStatusEnum): BicycleState = {
      currentTimeManagerType = mode
      currentTick = 100L
      val s = BicycleState(startTick = 0L, origin = 1L, destination = 3L,
        actorType = ActorTypeEnum.Bicycle, size = 1.8, distance = 25.0)
      s.status = status
      s.currentPath = Some(101L -> 2L)
      s.bestRoute = Some(mutable.Queue(102L -> 3L))
      state = s
      registerAwaitedReply("link-access:102", "LinkAccessData")
      s
    }
    def pending: Option[Long] = awaitedReplySendSeq("link-access:102")
    def waitUntil: Option[Tick] = signalWaitUntilTick
    def needsReverification: Boolean = signalWaitNeedsReverify
    def wake(tick: Tick): Unit = {
      currentTick = tick
      actSpontaneous(SpontaneousEvent(tick = tick, actorRef = system.deadLetters))
    }
    def reply(data: LinkAccessData, tick: Tick): Unit = {
      currentTick = tick
      actInteractWith(ActorInteractionEvent(tick = tick, lamportTick = tick, actorRefId = 2L,
        shardRefId = "hybrid.actor.Node", actorPathRef = "2", actorClassType = "hybrid.actor.Node",
        data = data, resourceId = "test"))
    }
  }

  private var nextActor = 0
  private def bicycle(): ObservedBicycle = {
    nextActor += 1
    TestActorRef(new ObservedBicycle, (3277992L + nextActor).toString).underlyingActor
  }

  for (mode <- Seq(TimeManagerTypeEnum.DISCRETE_EVENT, TimeManagerTypeEnum.TIME_WARP)) {
    s"Bicycle reply waits ($mode)" should "absorb queued wakes without polling, moving, or losing the pending request" in {
      for (status <- Seq(WaitingSignalState, WaitingCapacity)) {
        val bike = bicycle()
        val state = bike.prepare(mode, status)
        val pending = bike.pending

        bike.wake(100L)
        bike.wake(101L)

        bike.finishes.toList shouldBe List(None, None)
        bike.schedules shouldBe empty
        bike.warnings shouldBe empty
        bike.leaves shouldBe 0
        bike.pending shouldBe pending
        state.status shouldBe status
        state.currentPath shouldBe Some(101L -> 2L)
        state.bestRoute.get.toList shouldBe List(102L -> 3L)
        state.distance shouldBe 25.0
      }
    }

    it should "resume at the green reply tick without an extra tick of delay" in {
      val bike = bicycle()
      val state = bike.prepare(mode, WaitingSignalState)
      val seq = bike.pending.get
      bike.wake(100L)
      bike.reply(LinkAccessData(Green, 100L, requestSeq = seq), 100L)

      bike.pending shouldBe None
      state.status shouldBe WaitingSignal
      bike.schedules.toList shouldBe List(100L)
      bike.needsReverification shouldBe false
      bike.wake(100L)
      bike.leaves shouldBe 1
    }

    it should "preserve the red deadline and require signal re-verification" in {
      val bike = bicycle()
      val state = bike.prepare(mode, WaitingSignalState)
      val seq = bike.pending.get
      bike.wake(100L)
      bike.reply(LinkAccessData(Red, 130L, requestSeq = seq), 100L)

      state.status shouldBe WaitingSignal
      bike.waitUntil shouldBe Some(130L)
      bike.needsReverification shouldBe true
      bike.schedules.toList shouldBe List(130L)
      bike.wake(129L)
      bike.finishes.last shouldBe Some(130L)
      bike.leaves shouldBe 0
    }

    it should "wait without polling on Full and resume on the later capacity grant" in {
      val bike = bicycle()
      val state = bike.prepare(mode, WaitingSignalState)
      val seq = bike.pending.get
      bike.reply(LinkAccessData(Green, 100L, capacityState = Full, requestSeq = seq), 100L)
      state.status shouldBe WaitingCapacity
      bike.wake(101L)

      bike.finishes.toList shouldBe List(None)
      bike.pending shouldBe Some(seq)
      bike.schedules shouldBe empty
      bike.leaves shouldBe 0

      bike.reply(LinkAccessData(Green, 105L, capacityState = Available, requestSeq = seq), 105L)
      bike.pending shouldBe None
      state.status shouldBe WaitingSignal
      bike.schedules.toList shouldBe List(105L)
      bike.wake(105L)
      bike.leaves shouldBe 1
    }
  }
}
