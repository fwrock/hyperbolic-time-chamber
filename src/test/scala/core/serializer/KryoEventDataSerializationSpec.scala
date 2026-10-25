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
      model.hybrid.entity.event.data.link.SubscribeMicroBoundaryData(),
      model.hybrid.entity.event.data.link.MicroBoundaryUpdateData(
        model.hybrid.entity.state.model.MicroBoundarySnapshot(7, 12, 3, 9.03,
          Some(model.hybrid.entity.state.model.MicroBoundaryVehicle(42, 0.026, 0.26, 4.5)))),
      model.hybrid.entity.event.node.LinkAccessData(
        model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green, 12L, requestSeq = 77L,
        junctionLink = Some(PrecomputedRouteItem(700L, 2L))),
      model.hybrid.entity.event.data.MicroUpdateData(9, 3.0, 5.0, 0.0, 0, None, 10.0, 5.0, 5.0,
        junctionPosition = Some(model.hybrid.entity.state.model.MetricPoint(3.0, 4.0))),
      model.hybrid.entity.event.data.vehicle.RequestLinkAccessData(7L, signalAlreadyCleared = true, approachLinkId = 50L),
      EnterLinkData(ActorTypeEnum.Car, CreationTypeEnum.LoadBalancedDistributed, 4.5, 12.5,
        nextLinkId = 7L, initialRemainderSeconds = 0.04),
      model.hybrid.entity.event.data.MicroEnterLinkData(7L,
        model.hybrid.entity.state.enumeration.SimulationModeEnum.MICRO, 0, 200.0, 50.0, 1, 0.1, 1, Some(12.5),
        initialPosition = 0.5),
      model.hybrid.entity.event.data.MicroLeaveLinkData(7L, 200.0, 12.5, 15.1, 200.0, 12.0,
        remainingStepSeconds = 0.04),
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
