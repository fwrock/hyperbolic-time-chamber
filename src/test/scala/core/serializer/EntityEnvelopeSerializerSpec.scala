package org.interscity.htc
package core.serializer

import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }
import core.entity.event.control.load.CloseAndFinish
import model.hybrid.entity.event.data.link.LinkInfoData

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Round-trip coverage for the flattened `EntityEnvelopeEvent(entityId, ActorInteractionEvent(...))`
  * wire format (docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 1): a single `ActorInteraction`
  * protobuf frame carries `entityId` directly instead of nesting a second `EntityEnvelope` frame
  * around an already-serialized `ActorInteraction` blob. Also covers the untouched fallback path
  * (non-`ActorInteractionEvent` payloads) and the standalone `ActorInteractionEvent` serializer
  * used by `sendMessageToPool`, to make sure both still round-trip after sharing
  * `NestedPayloadCodec`.
  */
class EntityEnvelopeSerializerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "EntityEnvelopeSerializerSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  "EntityEnvelopeSerializer" should "round-trip EntityEnvelopeEvent(entityId, ActorInteractionEvent) via the flattened single-frame path" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "car-42",
      event = ActorInteractionEvent(
        tick = 123L,
        lamportTick = 7L,
        actorRefId = "car-42",
        // Real senders always set shardRefId = IdUtil.format(getShardId) = getClass.getName of the
        // *same* actor that produced actorClassType below — i.e. this value is never independent
        // of actorClassType in production traffic.
        shardRefId = "org.interscity.htc.model.Car",
        actorPathRef = "pekko://sys/user/car-42",
        actorClassType = "Car",
        eventType = "ENTER_LINK",
        data = LinkInfoData(linkLength = 100.0, linkCapacity = 10.0, linkNumberOfCars = 3, linkFreeSpeed = 13.9, linkLanes = 2),
        actorType = "LoadBalancedDistributed",
        resourceId = "res-1"
      )
    )

    val serializer = serialization.findSerializerFor(original)
    serializer shouldBe a[EntityEnvelopeSerializer]

    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original
  }

  it should "derive shardRefId from actorClassType on the wire, ignoring whatever value the sender happened to pass (recommendation 9)" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "car-42",
      event = ActorInteractionEvent(
        tick = 123L,
        lamportTick = 7L,
        actorRefId = "car-42",
        // Deliberately a stale/unrelated value: proves the wire no longer carries shardRefId
        // verbatim — the receiver reconstructs it from actorClassType instead.
        shardRefId = "this-value-must-not-survive-the-wire",
        actorPathRef = "pekko://sys/user/car-42",
        actorClassType = "Car",
        eventType = "ENTER_LINK",
        data = LinkInfoData(linkLength = 100.0, linkCapacity = 10.0, linkNumberOfCars = 3, linkFreeSpeed = 13.9, linkLanes = 2),
        actorType = "LoadBalancedDistributed",
        resourceId = "res-1"
      )
    )

    val serializer = serialization.findSerializerFor(original)
    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[EntityEnvelopeEvent]

    roundTripped.event.asInstanceOf[ActorInteractionEvent].shardRefId shouldBe "org.interscity.htc.model.Car"
  }

  it should "strip and reconstruct the htcaid_<type>_ prefix on entityId and actorRefId (recommendation 10)" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "htcaid_link_9001",
      event = ActorInteractionEvent(
        tick = 1L,
        lamportTick = 1L,
        actorRefId = "htcaid_car_42_v_car",
        shardRefId = "org.interscity.htc.model.Car",
        actorPathRef = "pekko://sys/user/car-42",
        actorClassType = "Car",
        eventType = "enter",
        data = LinkInfoData(linkLength = 100.0, linkCapacity = 10.0, linkNumberOfCars = 3, linkFreeSpeed = 13.9, linkLanes = 2),
        actorType = "LoadBalancedDistributed",
        resourceId = ""
      )
    )

    val serializer = serialization.findSerializerFor(original)
    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original

    val proto = org.htc.protobuf.core.entity.event.communication.ActorInteraction.parseFrom(bytes)
    proto.entityId shouldBe "9001"
    proto.actorRefId shouldBe "42_v_car"
    proto.actorRefIdPrefixStripped shouldBe true
  }

  it should "leave a non-matching entityId/actorRefId untouched on the wire (control-plane ids, best-effort only)" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "creator-load-data",
      event = ActorInteractionEvent(
        tick = 1L,
        lamportTick = 1L,
        actorRefId = "loader-123",
        shardRefId = "org.interscity.htc.model.Car",
        actorPathRef = "pekko://sys/user/loader-123",
        actorClassType = "Car",
        eventType = "default",
        data = LinkInfoData(linkLength = 1.0, linkCapacity = 1.0, linkNumberOfCars = 0, linkFreeSpeed = 1.0, linkLanes = 1),
        actorType = "LoadBalancedDistributed",
        resourceId = ""
      )
    )

    val serializer = serialization.findSerializerFor(original)
    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original
  }

  it should "produce a smaller wire payload than the pre-compaction shape for a representative EnterLinkData-style message" in {
    val serialization = SerializationExtension(system)

    val compact = EntityEnvelopeEvent(
      entityId = "htcaid_link_4922987596",
      event = ActorInteractionEvent(
        tick = 100L,
        lamportTick = 3L,
        actorRefId = "htcaid_car_42_v_car",
        shardRefId = "org.interscity.htc.model.Car",
        actorPathRef = "pekko://sys/user/car-42",
        actorClassType = "Car",
        eventType = "enter",
        data = LinkInfoData(linkLength = 100.0, linkCapacity = 10.0, linkNumberOfCars = 3, linkFreeSpeed = 13.9, linkLanes = 2),
        actorType = "LoadBalancedDistributed",
        resourceId = ""
      )
    )

    val serializer = serialization.findSerializerFor(compact)
    val compactBytes = serializer.toBinary(compact)

    // Pre-compaction shape: same logical message, but with the redundant shardRefId populated and
    // the full "htcaid_<type>_" prefix left on entityId/actorRefId — i.e. what recommendations 9/10
    // eliminated. Built directly against the proto to bypass the new serializer logic.
    val (actorClassTypeProto, _) = ActorInteractionCodec.encodeActorClassType("Car")
    val (eventTypeProto, _) = ActorInteractionCodec.encodeEventType("enter")
    val legacyShapeBytes = org.htc.protobuf.core.entity.event.communication
      .ActorInteraction(
        tick = 100L,
        lamportTick = 3L,
        actorRefId = "htcaid_car_42_v_car",
        shardRefId = "org.interscity.htc.model.Car",
        actorRef = "pekko://sys/user/car-42",
        actorClassType = actorClassTypeProto,
        eventType = eventTypeProto,
        data = com.google.protobuf.ByteString.copyFrom(
          NestedPayloadCodec.encode(serialization, compact.event.asInstanceOf[ActorInteractionEvent].data).get.bytes
        ),
        entityId = "htcaid_link_4922987596"
      )
      .toByteArray

    compactBytes.length should be < legacyShapeBytes.length
  }

  it should "round-trip EntityEnvelopeEvent wrapping a non-ActorInteractionEvent payload via the generic fallback path" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "loader-1",
      event = CloseAndFinish()
    )

    val serializer = serialization.findSerializerFor(original)
    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original
  }

  it should "round-trip an ActorInteractionEvent whose actorClassType/eventType are outside the known enum sets (OTHER + override fallback)" in {
    val serialization = SerializationExtension(system)

    val original = EntityEnvelopeEvent(
      entityId = "unknown-actor-1",
      event = ActorInteractionEvent(
        tick = 9L,
        lamportTick = 2L,
        actorRefId = "unknown-actor-1",
        shardRefId = "org.interscity.htc.model.SomeFutureManagerActor",
        actorPathRef = "pekko://sys/user/unknown-actor-1",
        actorClassType = "SomeFutureManagerActor",
        eventType = "SOME_FUTURE_EVENT_TYPE",
        data = LinkInfoData(linkLength = 1.0, linkCapacity = 1.0, linkNumberOfCars = 0, linkFreeSpeed = 1.0, linkLanes = 1),
        actorType = "SingletonDistributed",
        resourceId = ""
      )
    )

    val serializer = serialization.findSerializerFor(original)
    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original
  }

  "ActorInteractionSerializer" should "still round-trip a standalone ActorInteractionEvent (sendMessageToPool path)" in {
    val serialization = SerializationExtension(system)

    val original = ActorInteractionEvent(
      tick = 5L,
      lamportTick = 1L,
      actorRefId = "bus-1",
      shardRefId = "org.interscity.htc.model.Bus",
      actorPathRef = "pekko://sys/user/bus-1",
      actorClassType = "Bus",
      eventType = "default",
      data = LinkInfoData(linkLength = 50.0, linkCapacity = 5.0, linkNumberOfCars = 1, linkFreeSpeed = 8.3, linkLanes = 1),
      actorType = "PoolDistributed",
      resourceId = "res-2"
    )

    val serializer = serialization.findSerializerFor(original)
    serializer shouldBe a[ActorInteractionSerializer]

    val bytes = serializer.toBinary(original)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(original)
    val roundTripped = serialization.deserialize(bytes, serializer.identifier, manifest).get

    roundTripped shouldBe original
  }
}
