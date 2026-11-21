package org.interscity.htc
package model.mobility.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.enumeration.TimeManagerTypeEnum
import model.mobility.entity.event.data.{ LeaveLinkData, MicroEnterLinkData, MicroUpdateData }
import model.mobility.entity.state.CarState
import model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum, SimulationModeEnum }
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
    val sentMessages = mutable.Buffer.empty[(Long, AnyRef)]
    override protected def sendMessageTo(entityId: Long, shardId: String, data: AnyRef, eventType: String,
                                         actorType: core.enumeration.CreationTypeEnum): Unit = {
      sentMessages += ((entityId, data)); ()
    }
    def testRetainOn(link: Long): Unit = state.journey.retainedMicroExitLinkId = Some(link)
    def testDestruct(): Unit = onDestruct(org.htc.protobuf.core.entity.event.control.execution.DestructEvent(actorRef = null))
    def testRetained: Option[Long] = state.journey.retainedMicroExitLinkId
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
      tick = 1L, lamportTick = 1L, actorRefId = 42L, shardRefId = "mobility.actor.Link",
      actorPathRef = "42", actorClassType = "mobility.actor.Link", resourceId = "test",
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
      tick = 1L, lamportTick = 1L, actorRefId = 42L, shardRefId = "mobility.actor.Link",
      actorPathRef = "42", actorClassType = "mobility.actor.Link", resourceId = "test", seq = 1L,
      data = MicroEnterLinkData(42L, SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 10),
      eventType = "MicroEnterLink"
    )
    tm.expectMsgType[FinishEvent]
    val originalVelocity = car.underlyingActor.testVelocity
    car ! ActorInteractionEvent(
      tick = 2L, lamportTick = 2L, actorRefId = 42L, shardRefId = "mobility.actor.Link",
      actorPathRef = "42", actorClassType = "mobility.actor.Link", resourceId = "test", seq = 2L,
      data = MicroUpdateData(0, 10.0, 5.0, 1.0, 0, None, 100.0, 10.0, 10.0),
      eventType = "MicroUpdate"
    )
    car.underlyingActor.testVelocity shouldBe Some(5.0)

    car.underlyingActor.testRollback(2L)

    car.underlyingActor.testVelocity shouldBe originalVelocity
  }

  "A delayed MICRO acknowledgement" should "ignore a superseded request identity without consuming the current wait" in {
    val tm=TestProbe()
    val car=TestActorRef(new TestCar(Properties(entityId="17",reporters=mutable.Map.empty,
      timeManagers=mutable.Map(TimeManagerTypeEnum.TIME_WARP->tm.ref))),"17")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true,5.seconds,10.millis)
    car.underlyingActor.testPrepare()
    car.underlyingActor.testInitializeHistory()
    car ! SpontaneousEvent(tick=0,actorRef=tm.ref)
    tm.expectMsgType[FinishEvent].awaitingReply shouldBe true
    car ! ActorInteractionEvent(tick=1,lamportTick=1,actorRefId=42,shardRefId="mobility.actor.Link",
      actorPathRef="42",actorClassType="mobility.actor.Link",resourceId="test",seq=1,
      data=MicroEnterLinkData(42,SimulationModeEnum.MICRO,0,200,50,1,.1,10,
        initialPosition=5.1,queuedWaitSeconds=.1,requestSeq=99),eventType="MicroEnterLink")
    tm.expectNoMessage(50.millis)
    car.underlyingActor.testHasReplyObligation shouldBe true
    car.underlyingActor.testVelocity shouldBe None
  }

  "A retained footprint (G03)" should "be released on the next Link's admission ACK, exactly once" in {
    val tm = TestProbe()
    val car = TestActorRef(new TestCar(Properties(entityId = "19", reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref))), "19")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.testPrepare()
    car.underlyingActor.testRetainOn(7L)
    car ! SpontaneousEvent(tick = 0L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent]
    car.underlyingActor.sentMessages.collect { case (_, d: LeaveLinkData) => d } shouldBe empty
    def ack(tick: Long) = car ! ActorInteractionEvent(tick = tick, lamportTick = tick, actorRefId = 42L,
      shardRefId = "mobility.actor.Link", actorPathRef = "42", actorClassType = "mobility.actor.Link", resourceId = "test",
      data = MicroEnterLinkData(42L, SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 10), eventType = "MicroEnterLink")
    ack(1L)
    tm.expectMsgType[FinishEvent]
    car.underlyingActor.sentMessages.collect { case (7L, d: LeaveLinkData) => d } should have size 1
    car.underlyingActor.testRetained shouldBe None
    car.underlyingActor.sentMessages.clear()
    ack(2L) // a repeated ACK must not release a second time
    car.underlyingActor.sentMessages.collect { case (_, d: LeaveLinkData) => d } shouldBe empty
  }

  it should "be restored by rollback when the admission ACK is undone" in {
    val tm = TestProbe()
    val car = TestActorRef(new TestCar(Properties(entityId = "21", reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref))), "21")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.testPrepare()
    car.underlyingActor.testRetainOn(7L)
    car.underlyingActor.testInitializeHistory()
    car ! SpontaneousEvent(tick = 0L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent]
    car ! ActorInteractionEvent(tick = 1L, lamportTick = 1L, actorRefId = 42L, shardRefId = "mobility.actor.Link",
      actorPathRef = "42", actorClassType = "mobility.actor.Link", resourceId = "test", seq = 1L,
      data = MicroEnterLinkData(42L, SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 10), eventType = "MicroEnterLink")
    tm.expectMsgType[FinishEvent]
    car.underlyingActor.testRetained shouldBe None
    car.underlyingActor.testRollback(1L)
    car.underlyingActor.testRetained shouldBe Some(7L)
  }

  it should "release the footprint exactly once if the Car is destroyed before the next Link admitted it" in {
    val tm = TestProbe()
    val car = TestActorRef(new TestCar(Properties(entityId = "23", reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref))), "23")
    tm.awaitAssert(car.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.testPrepare()
    car.underlyingActor.testRetainOn(7L)
    car.underlyingActor.testDestruct()
    car.underlyingActor.sentMessages.collect { case (7L, d: LeaveLinkData) => d } should have size 1
    car.underlyingActor.testRetained shouldBe None
    car.underlyingActor.sentMessages.clear()
    car.underlyingActor.testDestruct() // a repeated destruction must not release again
    car.underlyingActor.sentMessages.collect { case (_, d: LeaveLinkData) => d } shouldBe empty
  }
}
