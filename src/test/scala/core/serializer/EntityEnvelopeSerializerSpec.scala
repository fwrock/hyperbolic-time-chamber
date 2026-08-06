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
        shardRefId = "link-9",
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
        shardRefId = "shard-x",
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
      shardRefId = "stop-3",
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
