package org.interscity.htc
package core.serializer

import core.enumeration.CreationTypeEnum
import model.hybrid.entity.event.data.{ EnterLinkData, MicroUpdateData, RequestRouteData }
import model.hybrid.entity.state.enumeration.ActorTypeEnum

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
  * simulation event-data payload trait — `EnterLinkData`, `LinkInfoData`, every MICRO/route/bus/
  * subway message) is now bound to Kryo instead of jackson-cbor in application.conf. Confirms the
  * binding actually resolves to Kryo, and round-trips representative payload shapes: plain
  * fields + enums (`EnterLinkData`), `Option[String]` (`MicroUpdateData`), and the trickiest case
  * — `mutable.Queue[(Identify, Identify)]` (a protobuf-generated nested type, see §3 of the doc)
  * plus a raw `ActorRef` field (`RequestRouteData`), which needs the pekko-kryo-serialization
  * library's built-in `ActorRefSerializer` (registered by `DefaultKryoInitializer`).
  *
  * Uses `serialization.serialize`/`.deserialize` (not `serializer.toBinary` directly) because
  * `ActorRef` serialization needs `Serialization.currentTransportInformation` set, which those
  * convenience methods wrap in — `serializer.toBinary` alone does not, and fails at that specific
  * point on a message containing an ActorRef, independent of Kryo.
  */
class KryoEventDataSerializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "KryoEventDataSerializationSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  "BaseEventData serialization binding" should "resolve to the Kryo serializer, not jackson-cbor" in {
    val serialization = SerializationExtension(system)
    val data: AnyRef = EnterLinkData(
      shardId = "link-1",
      actorId = "car-1",
      actorType = ActorTypeEnum.Car,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed,
      actorSize = 4.5
    )
    serialization.findSerializerFor(data).getClass.getName shouldBe "io.altoo.serialization.kryo.pekko.PekkoKryoSerializer"
  }

  it should "round-trip EnterLinkData (plain fields + Scala enums)" in {
    val serialization = SerializationExtension(system)
    val original = EnterLinkData(
      shardId = "link-1",
      actorId = "car-1",
      actorType = ActorTypeEnum.Car,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed,
      actorSize = 4.5,
      maxAcceleration = 2.6,
      maxDeceleration = 4.5
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
      leaderVehicle = Some("car-99"),
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

  it should "round-trip RequestRouteData (mutable.Queue[(Identify, Identify)] + a raw ActorRef field)" in {
    val serialization = SerializationExtension(system)

    val path = mutable.Queue(
      (Identify(id = "link-1", classType = "Link"), Identify(id = "node-1", classType = "Node")),
      (Identify(id = "link-2", classType = "Link"), Identify(id = "node-2", classType = "Node"))
    )
    val original = RequestRouteData(
      requester = system.deadLetters,
      requesterId = "car-1",
      requesterClassType = "Car",
      targetNodeId = "node-9",
      currentCost = 42.0,
      originNodeId = "node-0",
      path = path,
      label = "default"
    )

    val bytes = serialization.serialize(original).get
    val serializer = serialization.findSerializerFor(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, "").get

    roundTripped shouldBe original
  }
}
