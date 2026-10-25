package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick
import model.hybrid.entity.event.data.person.TripCompletedData
import model.hybrid.entity.state.{ PersonState, TripExecutionState }
import model.hybrid.entity.state.plan.{ Activity, AtTick, ConcreteMode, PlanCursor, PrivateVehicleLeg, RemainingQueue }
import model.hybrid.support.person.PersonPrivateVehicleTripHandler
import org.htc.protobuf.core.entity.actor.Identify

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `Person.actInteractWith`'s `TripCompletedData` branch gaining the same
  * `AwaitedReply` identity check `CarSignalHandler`/`BusSignalHandler`/`BicycleSignalHandler`/
  * `MotorcycleSignalHandler`/`Subway` already have (`docs/TIME_WARP_DESIGN.md`'s 2026-09-06
  * live-incident entry, extended here to the Person <-> owned-vehicle `StartTrip` ->
  * `TripCompleted` exchange).
  *
  * Before this fix, Person only ever gated `TripCompletedData` on `state.tripExecution` shape
  * (`PersonPlanManager.handleVehicleTripCompleted`'s pattern match). Under Time Warp, a rollback
  * that reverts `tripExecution` (independently of whether the reply in hand is still the one
  * that guard was set up for) makes this an unreliable way to tell a genuine reply apart from a
  * rollback-orphaned one to an earlier, since-retracted `StartTrip` -- exactly the "silently
  * accept/reject the wrong instance" shape the `sendSeq`/`requestSeq` identity check exists to
  * close, per `AwaitedReply`'s own doc.
  */
class PersonAwaitedReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "PersonAwaitedReplySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestPerson(properties: Properties) extends Person(properties) {
    val finishCalls: scala.collection.mutable.ArrayBuffer[Option[Tick]] = scala.collection.mutable.ArrayBuffer.empty

    override protected def onFinishSpontaneous(scheduleTick: Option[Tick] = None, destruct: Boolean = false): Unit =
      finishCalls += scheduleTick
    override protected def report(data: Any, label: String = null): Unit = ()

    def testSetState(s: PersonState): Unit = state = s
    def testSetCurrentTick(tick: Tick): Unit = currentTick = tick
    def testRegisterAwaitedReply(key: String, kind: String): Unit = registerAwaitedReply(key, kind)
    def testInteract(event: ActorInteractionEvent): Unit = actInteractWith(event)
    def testTripExecution: model.hybrid.entity.state.TripExecutionState = state.tripExecution
    def testCompletedTrips: Int = state.completedTrips
  }

  private var nextEntitySuffix = 0

  private def newTestPerson(entityId: String, timeManagerType: String): TestPerson = {
    nextEntitySuffix += 1
    TestActorRef(
      new TestPerson(Properties(entityId = entityId, defaultTimeManagerType = timeManagerType)),
      s"$entityId-$nextEntitySuffix"
    ).underlyingActor
  }

  private val vehicle = Identify(id = 501L, classType = "hybrid.actor.Car")

  private def travelingState(): PersonState = {
    val leg = PrivateVehicleLeg(ConcreteMode.Car, vehicle)
    val nextActivity = Activity("work", 2L, AtTick(500))
    PersonState(
      cursor = PlanCursor(executed = List(leg), remaining = RemainingQueue(List(nextActivity))),
      originalPlan = List(leg, nextActivity),
      ownedVehicles = Map("car" -> vehicle),
      tripExecution = TripExecutionState.Traveling(
        physicalNodeId = 1L,
        tripId = "1:trip:1",
        legStartTick = 100L,
        ptWait = None,
        replanStrategyId = "travel-time",
        replanAllowedModes = ConcreteMode.values.toSet
      )
    )
  }

  private def tripCompletedEvent(requestSeq: Long, eventSeq: Long = 0L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 150L,
      lamportTick = 150L,
      actorRefId = vehicle.id,
      shardRefId = vehicle.classType,
      actorPathRef = s"/user/vehicle-${vehicle.id}",
      actorClassType = vehicle.classType,
      eventType = "TripCompleted",
      data = TripCompletedData(
        vehicleId = vehicle.id,
        personId = 1L,
        distanceTraveled = 1234.0,
        travelTime = 50L,
        finalNode = 2L,
        completionTick = 150L,
        completionReason = "reached_destination",
        requestSeq = requestSeq
      ),
      resourceId = "res-1",
      seq = eventSeq
    )

  private def awaitKey(): String = new PersonPrivateVehicleTripHandler(
    personId = "1",
    sendMessageFn = (_, _, _, _, _) => (),
    logDebug = _ => ()
  ).vehicleTripAwaitKey(vehicle.id)

  /** `registerAwaitedReply` captures whatever this actor's send-sequence counter currently holds
    * (`0L` in these tests -- nothing else here ever sends a real message) as the awaited entry's
    * identity; a reply must echo that same value back on `requestSeq` to be accepted.
    */
  "a Time Warp Person" should "accept a genuine TripCompleted reply matching the outstanding StartTrip" in {
    val person = newTestPerson("person-1", TimeManagerTypeEnum.TIME_WARP)
    person.testSetCurrentTick(100L)
    person.testSetState(travelingState())
    person.testRegisterAwaitedReply(awaitKey(), "TripCompletedData")

    person.testInteract(tripCompletedEvent(requestSeq = 0L))

    person.finishCalls should not be empty
    person.testCompletedTrips shouldBe 1
  }

  /** `requestSeq = 99L` doesn't match the registered entry's `sendSeq` (`0L`) -- the "reply to a
    * since-retracted/different request instance" shape the check exists to reject, per
    * `AwaitedReply`'s own doc.
    */
  it should "ignore a rollback-orphaned TripCompleted reply whose requestSeq doesn't match the current StartTrip" in {
    val person = newTestPerson("person-2", TimeManagerTypeEnum.TIME_WARP)
    person.testSetCurrentTick(100L)
    val stateBefore = travelingState()
    person.testSetState(stateBefore)
    person.testRegisterAwaitedReply(awaitKey(), "TripCompletedData")

    person.testInteract(tripCompletedEvent(requestSeq = 99L))

    person.finishCalls shouldBe empty
    person.testTripExecution shouldBe stateBefore.tripExecution
  }

  it should "ignore a TripCompleted reply with no outstanding StartTrip registered at all" in {
    val person = newTestPerson("person-3", TimeManagerTypeEnum.TIME_WARP)
    person.testSetCurrentTick(100L)
    val stateBefore = travelingState()
    person.testSetState(stateBefore)

    person.testInteract(tripCompletedEvent(requestSeq = 0L))

    person.finishCalls shouldBe empty
    person.testTripExecution shouldBe stateBefore.tripExecution
  }

  /** No `registerAwaitedReply` call at all here -- conservative mode never consults it. */
  "a conservative-mode Person" should "keep accepting TripCompleted purely on tripExecution shape (unchanged)" in {
    val person = newTestPerson("person-4", TimeManagerTypeEnum.DISCRETE_EVENT)
    person.testSetCurrentTick(100L)
    person.testSetState(travelingState())

    person.testInteract(tripCompletedEvent(requestSeq = 0L))

    person.finishCalls should not be empty
  }
}
