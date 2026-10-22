package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.enumeration.TimeManagerTypeEnum
import model.hybrid.entity.event.data.{ MicroEnterLinkData, MicroUpdateData }
import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum, SimulationModeEnum }
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

class CarMicroReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "CarMicroReplySpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class TestCar(properties: Properties) extends Car(properties) {
    def testPrepare(): Unit = {
      state = CarState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Car, size = 4.5)
      state.status = MovableStatusEnum.Parked
      startTick = state.startTick
      registerAwaitedReply(Movable.enterLinkAwaitKey(42L), "EnterLinkData")
    }
    def testRecoveryFinished: Boolean = recoveryFinished
    def testHasReplyObligation: Boolean = awaitedReplySendSeq(Movable.enterLinkAwaitKey(42L)).nonEmpty
    def testInitializeHistory(): Unit = rollbackHandler.initialize(startTick)
    def testRollback(tick: Long): Unit = { rollbackHandler.rollbackTo(tick); () }
    def testVelocity: Option[Double] = state.microState.map(_.velocity)
  }

  "A MICRO entry acknowledgement" should "resolve the pending EnterLink request before the car disengages" in {
    val tm = TestProbe()
    val car = TestActorRef(new TestCar(Properties(
      entityId = "13", reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref)
    )), "13")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.testPrepare()
    car ! SpontaneousEvent(tick = 0L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent].awaitingReply shouldBe true

    car ! ActorInteractionEvent(
      tick = 1L, lamportTick = 1L, actorRefId = 42L, shardRefId = "hybrid.actor.Link",
      actorPathRef = "42", actorClassType = "hybrid.actor.Link", resourceId = "test",
      data = MicroEnterLinkData(42L, SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 10),
      eventType = "MicroEnterLink"
    )
    tm.expectMsgType[FinishEvent].awaitingReply shouldBe false
    car.underlyingActor.testHasReplyObligation shouldBe false
  }

  "A replayed MICRO entry" should "use its original arrival speed after undoing a later velocity update" in {
    val tm = TestProbe()
    val car = TestActorRef(new TestCar(Properties(
      entityId = "15", reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref)
    )), "15")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.testPrepare()
    car.underlyingActor.testInitializeHistory()
    car ! SpontaneousEvent(tick = 0L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent]
    car ! ActorInteractionEvent(
      tick = 1L, lamportTick = 1L, actorRefId = 42L, shardRefId = "hybrid.actor.Link",
      actorPathRef = "42", actorClassType = "hybrid.actor.Link", resourceId = "test", seq = 1L,
      data = MicroEnterLinkData(42L, SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 10),
      eventType = "MicroEnterLink"
    )
    tm.expectMsgType[FinishEvent]
    val originalVelocity = car.underlyingActor.testVelocity
    car ! ActorInteractionEvent(
      tick = 2L, lamportTick = 2L, actorRefId = 42L, shardRefId = "hybrid.actor.Link",
      actorPathRef = "42", actorClassType = "hybrid.actor.Link", resourceId = "test", seq = 2L,
      data = MicroUpdateData(0, 10.0, 5.0, 1.0, 0, None, 100.0, 10.0, 10.0),
      eventType = "MicroUpdate"
    )
    car.underlyingActor.testVelocity shouldBe Some(5.0)

    car.underlyingActor.testRollback(2L)

    car.underlyingActor.testVelocity shouldBe originalVelocity
  }
}
