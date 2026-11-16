package org.interscity.htc
package core.serializer

import core.enumeration.CreationTypeEnum
import model.hybrid.entity.event.data.{ EnterLinkData, MicroUpdateData }
import model.hybrid.entity.event.data.bus.{ BusLoadPassengerData, BusRequestUnloadPassengerData }
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.entity.event.data.routing.*
import model.hybrid.entity.state.ModeChoiceWeights
import model.hybrid.entity.state.model.PrecomputedRouteItem
import model.hybrid.entity.state.plan.{ ConcreteMode, ModeDecisionRequest, WalkLeg }
import model.hybrid.decision.{ DecisionContext, NoViableJourney }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

/** Coverage for docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 3: `BaseEventData` (the
  * simulation event-data payload trait — `EnterLinkData`, `LinkInfoData`, every MICRO/bus/subway
  * message) is now bound to Kryo instead of jackson-cbor in application.conf. Confirms the
  * binding actually resolves to Kryo, and round-trips representative payload shapes: plain
  * fields + enums (`EnterLinkData`), `Option[String]` (`MicroUpdateData`), a
  * `mutable.Seq[Identify]` of protobuf-generated values (`BusLoadPassengerData` — the nesting
  * concern from §3 of the doc; `RequestRouteData`/`ForwardRouteData`, the other classes that had
  * it, turned out to be dead code and were removed by recommendation 4 instead), and a raw
  * `ActorRef` field (`BusRequestUnloadPassengerData`), which needs the pekko-kryo-serialization
  * library's built-in `ActorRefSerializer` (registered by `DefaultKryoInitializer`).
  *
  * Uses `serialization.serialize`/`.deserialize` (not `serializer.toBinary` directly) because
  * `ActorRef` serialization needs `Serialization.currentTransportInformation` set, which those
  * convenience methods wrap in — `serializer.toBinary` alone does not, and fails at that specific
  * point on a message containing an ActorRef, independent of Kryo.
  */
class KryoEventDataSerializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private var peer: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit = {
    _system = ActorSystem(
      "KryoEventDataSerializationSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )
    peer = ActorSystem("KryoEventDataColdPeerSpec", system.settings.config)
  }

  override def afterAll(): Unit = {
    _system.terminate()
    peer.terminate()
    ()
  }

  "BaseEventData serialization binding" should "resolve to the Kryo serializer, not jackson-cbor" in {
    val serialization = SerializationExtension(system)
    val data: AnyRef = EnterLinkData(
      actorType = ActorTypeEnum.Car,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed,
      actorSize = 4.5
    )
    serialization.findSerializerFor(data).getClass.getName shouldBe "io.altoo.serialization.kryo.pekko.PekkoKryoSerializer"
  }

  "a cold Link checkpoint" should "retain the queued entry reply obligation and typed lane data" in {
    val request=EnterLinkData(ActorTypeEnum.Car,CreationTypeEnum.LoadBalancedDistributed,5,
      nextLinkId=8,initialPosition=5.1,preferredLane=Some(1))
    val original=model.hybrid.entity.state.LinkState(0,from=1,to=2,length=100,lanes=2,
      speedLimit=50,capacity=100,freeSpeed=50,
      simulationMode=model.hybrid.entity.state.enumeration.SimulationModeEnum.MICRO,
      microEntry=model.hybrid.entity.state.model.MicroEntryConfig(enabled=true),
      pendingMicroEntries=List(model.hybrid.entity.state.model.PendingMicroEntry(42,"hybrid.actor.Car",12,77,request)))
    val writer=SerializationExtension(system)
    val serializer=writer.findSerializerFor(original)
    val manifest=serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val restored=SerializationExtension(peer).deserialize(writer.serialize(original).get,serializer.identifier,manifest).get
      .asInstanceOf[model.hybrid.entity.state.LinkState]
    restored.microEntry.enabled shouldBe true
    restored.pendingMicroEntries.head.requestSeq shouldBe 77L
    restored.pendingMicroEntries.head.data.preferredLane.get+1 shouldBe 2
    restored.pendingMicroEntries.head.data.initialPosition shouldBe 5.1
  }

  "a cold MESO checkpoint" should "retain flow credit, scheduled wake and pending reply identities" in {
    import model.hybrid.entity.state.model.*
    val original=model.hybrid.entity.state.LinkState(0,from=1,to=2,length=100,lanes=1,
      speedLimit=36,capacity=20,freeSpeed=36,mesoCapacity=Some(MesoCapacityConfig(1800,3)),
      mesoCapacityState=MesoCapacityState(credit= -0.5,creditTick=Some(10),wakeTick=Some(12),
        originVehicles=List(9007199254740993L),destinationVehicles=List(9007199254740995L),entries=List(PendingMesoEntry(43,"hybrid.actor.Car",10,77,
          EnterLinkData(ActorTypeEnum.Car,CreationTypeEnum.LoadBalancedDistributed,5,mesoOriginAtEnd=true))),
        exits=List(PendingMesoExit(44,"hybrid.actor.Car",9,78,
          model.hybrid.entity.event.data.LeaveLinkData(ActorTypeEnum.Car,5,CreationTypeEnum.LoadBalancedDistributed)))))
    val writer=SerializationExtension(system)
    val serializer=writer.findSerializerFor(original)
    val manifest=serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val restored=SerializationExtension(peer).deserialize(writer.serialize(original).get,serializer.identifier,manifest).get
      .asInstanceOf[model.hybrid.entity.state.LinkState]
    restored.mesoCapacity shouldBe Some(MesoCapacityConfig(1800,3))
    restored.mesoCapacityState.credit shouldBe -0.5
    restored.mesoCapacityState.wakeTick shouldBe Some(12)
    restored.mesoCapacityState.originVehicles.head+1 shouldBe 9007199254740994L
    restored.mesoCapacityState.destinationVehicles.head+1 shouldBe 9007199254740996L
    restored.mesoCapacityState.entries.head.data.mesoOriginAtEnd shouldBe true
    restored.mesoCapacityState.entries.head.requestSeq shouldBe 77L
    restored.mesoCapacityState.exits.head.requestSeq shouldBe 78L
  }

  "a cold Node checkpoint" should "retain route-aware destination lane configuration" in {
    val original=model.hybrid.entity.state.NodeState(0,latitude=0,longitude=0,links=Nil,
      routeAwareJunctionLanes=true,junctionMovements=List(
        model.hybrid.entity.state.model.JunctionMovement(5,7,700,Some(0),Some(1),
          List(model.hybrid.entity.state.model.LaneConnection(9,1,0)))))
    val writer=SerializationExtension(system)
    val serializer=writer.findSerializerFor(original)
    val manifest=serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val restored=SerializationExtension(peer).deserialize(writer.serialize(original).get,serializer.identifier,manifest).get
      .asInstanceOf[model.hybrid.entity.state.NodeState]
    restored.routeAwareJunctionLanes shouldBe true
    restored.junctionMovements.head.continuationLanes.head.fromLane+1 shouldBe 2
    restored.junctionMovements.head.outgoingLane shouldBe Some(1)
  }

  it should "round-trip EnterLinkData (plain fields + Scala enums)" in {
    val serialization = SerializationExtension(system)
    val original = EnterLinkData(
      actorType = ActorTypeEnum.Car,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed,
      actorSize = 4.5
    )

    val bytes = serialization.serialize(original).get
    val serializer = serialization.findSerializerFor(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, "").get

    roundTripped shouldBe original
  }

  it should "round-trip MicroUpdateData (Option[String] field, both Some and None)" in {
    val serialization = SerializationExtension(system)

    val withLeader = MicroUpdateData(
      subTick = 3,
      position = 12.5,
      velocity = 8.0,
      acceleration = 0.5,
      currentLane = 1,
      leaderVehicle = Some(99L),
      gapToLeader = 5.0,
      leaderVelocity = 7.5,
      safeVelocity = 8.2
    )
    val withoutLeader = withLeader.copy(leaderVehicle = None)

    Seq(withLeader, withoutLeader).foreach { original =>
      val bytes = serialization.serialize(original).get
      val serializer = serialization.findSerializerFor(original)
      val roundTripped = serialization.deserialize(bytes, serializer.identifier, "").get
      roundTripped shouldBe original
    }
  }

  it should "round-trip BusLoadPassengerData (mutable.Seq[Identify], a protobuf-generated nested type)" in {
    val serialization = SerializationExtension(system)

    val original = BusLoadPassengerData(
      people = mutable.Buffer(
        Identify(id = 1L, classType = "Person"),
        Identify(id = 2L, classType = "Person")
      )
    )

    val bytes = serialization.serialize(original).get
    val serializer = serialization.findSerializerFor(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, "").get

    roundTripped shouldBe original
  }

  it should "round-trip BusRequestUnloadPassengerData (a raw ActorRef field)" in {
    val serialization = SerializationExtension(system)

    val original = BusRequestUnloadPassengerData(
      nodeId = 1L,
      nodeRef = system.deadLetters
    )

    val bytes = serialization.serialize(original).get
    val serializer = serialization.findSerializerFor(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, "").get

    roundTripped shouldBe original
  }

  it should "decode accounted routing payloads on an independent cold peer in a different order" in {
    val serialization = SerializationExtension(system)
    val reader = SerializationExtension(peer)
    val context = DecisionContext(
      ModeChoiceWeights(includedModes = Set("walk")), Map.empty, Map.empty, 12L, "42")
    val payloads: Seq[AnyRef] = Seq(
      model.hybrid.entity.event.data.link.LinkInfoData(0,20,0,36,1,77,
        mesoOriginAtEnd=true,mesoEntryWaitSeconds=12,mesoExitWaitSeconds=3),
      model.hybrid.entity.event.data.signal.TrafficDetectionData(501,11.9),
      model.hybrid.entity.event.data.link.LinkSignalStateData(
        model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Yellow,12,Some(7),List(1),
        List(model.hybrid.entity.event.data.signal.MovementAspectUpdate(8,List(0),
          model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red,12))),
      model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData(
        model.hybrid.entity.state.model.SignalState(model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green,3,12),12,91,
        List(model.hybrid.entity.event.data.signal.SignalAspectUpdate(92,
          model.hybrid.entity.state.model.SignalState(model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red,3,12),12))),
      model.hybrid.entity.event.data.link.SubscribeMicroBoundaryData(),
      model.hybrid.entity.event.data.link.MicroBoundaryUpdateData(
        model.hybrid.entity.state.model.MicroBoundarySnapshot(7, 12, 3, 9.03,
          Some(model.hybrid.entity.state.model.MicroBoundaryVehicle(42, 0.026, 0.26, 4.5)))),
      model.hybrid.entity.event.node.LinkAccessData(
        model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green, 12L, requestSeq = 77L,
        junctionLink = Some(PrecomputedRouteItem(700L, 2L)), outgoingLane = Some(1)),
      model.hybrid.entity.event.data.MicroUpdateData(9, 3.0, 5.0, 0.0, 0, None, 10.0, 5.0, 5.0,
        junctionPosition = Some(model.hybrid.entity.state.model.MetricPoint(3.0, 4.0))),
      model.hybrid.entity.event.data.vehicle.RequestLinkAccessData(7L, signalAlreadyCleared = true, approachLinkId = 50L, approachLane = Some(1), followingLinkId = 9007199254740993L),
      EnterLinkData(ActorTypeEnum.Car, CreationTypeEnum.LoadBalancedDistributed, 4.5, 12.5,
        nextLinkId = 7L, initialRemainderSeconds = 0.04, maxAcceleration = Some(1.5),
        maxDeceleration = Some(3.7), initialPosition = 12.0, arrivalPosition = Some(150.0),preferredLane=Some(1),
        minGap=Some(3.5),reactionTime=Some(1.7),maxSpeed=Some(7.0),speedFactor=1.2,desiredMaxSpeed=Some(5),
        sigma=.5,randomSeed=9007199254740993L,
        lc2013=model.hybrid.entity.state.model.Lc2013Parameters(strategic=2,cooperative=.3,keepRight=0)),
      model.hybrid.entity.event.data.MicroEnterLinkData(7L,
        model.hybrid.entity.state.enumeration.SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 1, Some(12.5),
        initialPosition = 0.5,queuedWaitSeconds=2.3,requestSeq=77L),
      model.hybrid.entity.event.data.MicroLeaveLinkData(7L, 200.0, 12.5, 15.1, 200.0, 12.0,
        remainingStepSeconds = 0.04, retainedInLane = true,finalLane=Some(1)),
      model.hybrid.entity.event.data.person.PassengerBoardedVehicleData(77L, "hybrid.actor.Bus", 1200.0),
      LinkCostChangedData(7L, 123.0, 12L),
      RouteQueryData(1L, 4L, 500000),
      RouteReplyData(9L, Some(RouteResult(400.0,
        List(PrecomputedRouteItem(7L, 3L), PrecomputedRouteItem(11L, 4L))))),
      ModeDecisionQueryData(1L, 4L,
        ModeDecisionRequest(Set(ConcreteMode.Walk), "travel-time"), context),
      ModeDecisionReplyData(10L, Right(List(WalkLeg(1L, 4L, Some(Nil))))),
      ModeDecisionReplyData(11L, Left(NoViableJourney("unreachable")))
    )

    val encoded = payloads.map { original =>
      val bytes = serialization.serialize(original).get
      val serializer = serialization.findSerializerFor(original)
      (original, bytes, serializer.identifier)
    }
    encoded.reverse.foreach { case (original, bytes, identifier) =>
      reader.deserialize(bytes, identifier, "").get shouldBe original
    }
  }
}
