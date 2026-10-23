package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.actor.rollback.EventReadJournal
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.types.Tick
import model.hybrid.decision.*
import model.hybrid.entity.event.data.person.TripCompletedData
import model.hybrid.entity.state.PersonState
import model.hybrid.entity.state.plan.*
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

class PersonReplayInitializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "PersonReplayInitializationSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = local
      pekko.actor.fail-mixed-versions = off
      htc.time-warp.checkpoint-interval = 50
    """).withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  private class TestPerson(properties: Properties) extends Person(properties) {
    override protected def sendMessageTo(
      entityId: Long, shardId: String, data: AnyRef, eventType: String, actorType: CreationTypeEnum
    ): Unit = super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)

    def testRecoveryFinished: Boolean = recoveryFinished
    def testState: PersonState = state
    def testSnapshot: MigrationSnapshot = buildMigrationSnapshot()
    def testRollback(tick: Tick): Unit = { rollbackHandler.rollbackTo(tick); () }
  }

  private var nextId = 1000L
  private final case class Rig(person: TestActorRef[TestPerson], tm: TestProbe, vehicle: TestProbe,
                               vehicleId: Long, engine: ObservedEngine)

  private class ObservedEngine(val id: String) extends ModeDecisionEngine {
    var liveReads = 0
    override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] = Right(())
    override def decide(origin: Long, destination: Long, request: ModeDecisionRequest, ctx: DecisionContext)
      : Either[NoViableJourney, List[AtomicLeg]] = {
      val vehicle = EventReadJournal.read(s"$id:$origin:$destination") {
        liveReads += 1
        PrivateVehicleCandidates.available(origin, ctx.ownedVehicles, ctx.vehicleCurrentNode)("car")
      }
      Right(List(PrivateVehicleLeg(ConcreteMode.Car, vehicle)))
    }
  }

  private def newRig(): Rig = {
    nextId += 2
    val personId = nextId
    val vehicleId = nextId + 1
    val tm = TestProbe()
    val vehicle = TestProbe()
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => vehicle.ref.forward(msg) } }), vehicleId.toString)
    val engine = new ObservedEngine(s"person-replay-$personId")
    ModeDecisionEngineRegistry.register(engine.id, engine)
    val person = TestActorRef(new TestPerson(Properties(
      entityId = personId.toString, resourceId = "replay-test",
      actorType = CreationTypeEnum.PoolDistributed,
      reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref)
    )), personId.toString)
    tm.awaitAssert(person.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    val decision = PendingDecision(ModeDecisionRequest(Set(ConcreteMode.Car), engine.id))
    val plan = List(Activity("home", 1L, AtTick(10)), decision, Activity("work", 2L, AtTick(200)),
      decision, Activity("home", 1L, AtTick(800)))
    val ack = TestProbe()
    person ! InitializeEvent(personId.toString, ack.ref, InitializeData(
      data = PersonState(originalPlan = plan,
        ownedVehicles = Map("car" -> Identify(id = vehicleId, classType = "hybrid.actor.Car")),
        vehicleCurrentNode = Map("car" -> 1L)),
      resourceId = "replay-test", creatorManager = ack.ref, reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref)
    ))
    ack.expectMsgType[InitializeEntityAckEvent]
    tm.expectMsgType[RegisterActorEvent]
    Rig(person, tm, vehicle, vehicleId, engine)
  }

  private def wake(rig: Rig, tick: Tick): Unit = {
    rig.person ! SpontaneousEvent(tick, rig.tm.ref)
    rig.tm.expectMsgType[FinishEvent]
  }

  private def completion(rig: Rig, tick: Tick, requestSeq: Long): ActorInteractionEvent =
    ActorInteractionEvent(tick = tick, lamportTick = tick, actorRefId = rig.vehicleId,
      shardRefId = "hybrid.actor.Car", actorPathRef = rig.vehicleId.toString,
      actorClassType = "hybrid.actor.Car", eventType = "TripCompleted", resourceId = "replay-test",
      seq = tick, data = TripCompletedData(vehicleId = rig.vehicleId,
        personId = rig.person.path.name.toLong, distanceTraveled = 200.0, travelTime = tick - 10L,
        finalNode = 2L, completionTick = tick, completionReason = "reached_destination", requestSeq = requestSeq))

  "A Person initialized from scenario data" should "restore its seeded plan before the first event" in {
    val rig = newRig()
    val initialized = core.util.JsonUtil.fromJson[PersonState](core.util.JsonUtil.toJson(rig.person.underlyingActor.testState))
    initialized.cursor.remaining shouldBe RemainingQueue(initialized.originalPlan)
    wake(rig, 0L)

    rig.person.underlyingActor.testRollback(0L)

    rig.person.underlyingActor.testState shouldBe initialized
  }

  it should "replay a pending decision with the same observations and StartTrip identity" in {
    val rig = newRig()
    wake(rig, 0L)
    wake(rig, 10L)
    val request = rig.vehicle.expectMsgType[ActorInteractionEvent]
    val afterDeparture = rig.person.underlyingActor.testSnapshot
    rig.person ! completion(rig, 34L, request.seq)
    rig.tm.expectMsgType[FinishEvent]
    rig.person.underlyingActor.testState.completedTrips shouldBe 1

    rig.person.underlyingActor.testRollback(20L)

    rig.person.underlyingActor.testState shouldBe core.util.JsonUtil.fromJson[PersonState](afterDeparture.stateJson)
    rig.person.underlyingActor.testSnapshot.awaitedReplies shouldBe afterDeparture.awaitedReplies
    rig.engine.liveReads shouldBe 1
    rig.vehicle.expectNoMessage(100.millis)
    rig.tm.expectNoMessage(100.millis)

    rig.person ! completion(rig, 35L, request.seq)
    rig.tm.expectMsgType[FinishEvent]
    rig.person.underlyingActor.testState.completedTrips shouldBe 1
    rig.person.underlyingActor.testState.totalDistanceTraveled shouldBe 200.0
  }

  it should "cancel a completed trip through the runtime and accept its replacement after replay" in {
    val rig = newRig()
    wake(rig, 0L)
    wake(rig, 10L)
    val request = rig.vehicle.expectMsgType[ActorInteractionEvent]
    val afterDeparture = rig.person.underlyingActor.testSnapshot
    val completed = completion(rig, 34L, request.seq)
    rig.person ! completed
    rig.tm.expectMsgType[FinishEvent]

    rig.person ! completed.copy(isAntiMessage = true)

    rig.tm.expectMsgType[core.entity.event.control.execution.ReplaceScheduleEvent].awaitingReply shouldBe true
    rig.person.underlyingActor.testState shouldBe core.util.JsonUtil.fromJson[PersonState](afterDeparture.stateJson)
    rig.person.underlyingActor.testSnapshot.awaitedReplies shouldBe afterDeparture.awaitedReplies
    rig.engine.liveReads shouldBe 1
    rig.vehicle.expectNoMessage(100.millis)
    rig.person ! completed
    rig.tm.expectNoMessage(100.millis)

    rig.person ! completion(rig, 35L, request.seq)
    rig.tm.expectMsgType[FinishEvent]
    rig.person.underlyingActor.testState.completedTrips shouldBe 1
    rig.person.underlyingActor.testState.totalDistanceTraveled shouldBe 200.0
  }
}
