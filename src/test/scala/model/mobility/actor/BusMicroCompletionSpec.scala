package org.interscity.htc
package model.mobility.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import model.mobility.entity.event.data.MicroLeaveLinkData
import model.mobility.entity.state.{BusState, MicroBusState}
import model.mobility.entity.state.enumeration.MovableStatusEnum.Moving
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

class BusMicroCompletionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private var systemInstance: ActorSystem = uninitialized
  private implicit def system: ActorSystem = systemInstance

  override def beforeAll(): Unit = {
    systemInstance = ActorSystem("BusMicroCompletionSpec", ConfigFactory.parseString(
      "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  }
  override def afterAll(): Unit = { systemInstance.terminate(); () }

  private class ObservedBus extends Bus(Properties(entityId = "4953303", resourceId = "test")) {
    val finishes = mutable.Buffer.empty[(Option[Tick], Boolean)]
    var leaves = 0
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean): Unit =
      finishes += ((scheduleTick, destruct))
    override protected def report(data: Any, label: String = null): Unit = ()
    override protected def reportMirrored(data: Any, label: String = null): Unit = ()
    override def leavingLink(): Unit = { leaves += 1; super.leavingLink() }
    def prepare(position: Double, routeContinues: Boolean): BusState = {
      val s = BusState(startTick = 0L, label = "line", capacity = 40, busStops = Map.empty,
        numberOfPorts = 2, origin = 1L, destination = 2L, size = 12.0)
      s.currentPath = Some(101L -> 2L)
      s.bestRoute = Some(if (routeContinues) mutable.Queue(102L -> 3L) else mutable.Queue.empty)
      s.activateMicroMode(MicroBusState(positionInLink = position, velocity = 0.0,
        acceleration = 0.0, currentLane = 0))
      s.status = Moving
      state = s
      currentLinkId = Some(101L)
      linkEntryTick = Some(0L)
      s
    }
  }

  "A moving MICRO bus" should "keep its path until the Link confirms exit, even at or beyond the link end" in {
    for ((position, index) <- Seq(0.0, 1000.0, 1001.0).zipWithIndex) {
      val bus = TestActorRef(new ObservedBus, s"waiting-bus-$index").underlyingActor
      val state = bus.prepare(position, routeContinues = true)

      bus.actSpontaneous(SpontaneousEvent(tick = 0L, actorRef = system.deadLetters))

      bus.leaves shouldBe 0
      state.currentPath shouldBe Some(101L -> 2L)
      state.bestRoute.get.toList shouldBe List(102L -> 3L)
      state.status shouldBe Moving
      state.isMicroMode shouldBe true
      state.distance shouldBe 0.0
      bus.finishes.toList shouldBe List((None, false))
    }
  }

  it should "process the authoritative exit once after a wake-up at the link end" in {
    val bus = TestActorRef(new ObservedBus, "completing-bus").underlyingActor
    val state = bus.prepare(1000.0, routeContinues = false)
    bus.actSpontaneous(SpontaneousEvent(tick = 0L, actorRef = system.deadLetters))
    bus.finishes.clear()
    val exit = ActorInteractionEvent(tick = 0L, lamportTick = 1L, actorRefId = 101L,
      shardRefId = "hybrid.actor.Link", actorPathRef = "101", actorClassType = "hybrid.actor.Link",
      data = MicroLeaveLinkData(linkId = 101L, finalPosition = 1000.0, finalVelocity = 0.0,
        travelTime = 100.0, distanceTraveled = 1000.0, averageSpeed = 10.0), resourceId = "test")

    bus.actInteractWith(exit)
    bus.actInteractWith(exit)

    state.distance shouldBe 1000.0
    state.isMicroMode shouldBe false
    bus.finishes.toList shouldBe List((None, true))
  }
}
