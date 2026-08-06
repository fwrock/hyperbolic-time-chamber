package org.interscity.htc
package core.serializer

import core.enumeration.CreationTypeEnum

import org.htc.protobuf.core.entity.event.communication.{ ActorClassType, ActorEventType }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit coverage for [[ActorInteractionCodec]] — the string<->protobuf-enum mapping backing
  * docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 2. Every known value here mirrors a real
  * `sendMessageTo`/`ActorInteractionEvent` call site found by grepping the codebase; this spec
  * guards against silently dropping one of those mappings (which would push a currently-compact
  * value onto the string-fallback path) and against the OTHER/override fallback ever losing
  * information for values outside the known set.
  */
class ActorInteractionCodecSpec extends AnyFlatSpec with Matchers {

  private val knownEventTypes = Seq(
    "default",
    "enter",
    "leave",
    "LineNotOperational",
    "PTLineNotOperational",
    "TripCompleted",
    "ReceiveEnterLinkInfo",
    "ReceiveLeaveLinkInfo",
    "RegisterLinkCapacity",
    "DT_UPDATE",
    "DT_COMMAND",
    "DT_ANOMALY",
    "DT_STATE_UPDATE"
  )

  private val knownActorClassTypes = Seq(
    "Bicycle", "Bus", "BusStation", "BusStop", "Car", "Link", "RailLink", "Motorcycle",
    "Node", "Person", "Subway", "SubwayStation", "TrafficSignal", "DigitalTwinManager"
  )

  "encodeEventType/decodeEventType" should "round-trip every known eventType without using the OTHER fallback" in {
    knownEventTypes.foreach { value =>
      val (proto, overrideValue) = ActorInteractionCodec.encodeEventType(value)
      proto should not be ActorEventType.EVENT_TYPE_OTHER
      overrideValue shouldBe empty
      ActorInteractionCodec.decodeEventType(proto, overrideValue) shouldBe value
    }
  }

  it should "map the default parameter value to the proto3 zero-value (skips wire encoding)" in {
    ActorInteractionCodec.encodeEventType("default")._1 shouldBe ActorEventType.EVENT_TYPE_DEFAULT
    ActorEventType.EVENT_TYPE_DEFAULT.value shouldBe 0
  }

  it should "fall back to OTHER + override string for an unmapped eventType, and still round-trip losslessly" in {
    val (proto, overrideValue) = ActorInteractionCodec.encodeEventType("SOME_FUTURE_EVENT_TYPE")
    proto shouldBe ActorEventType.EVENT_TYPE_OTHER
    overrideValue shouldBe "SOME_FUTURE_EVENT_TYPE"
    ActorInteractionCodec.decodeEventType(proto, overrideValue) shouldBe "SOME_FUTURE_EVENT_TYPE"
  }

  "encodeActorClassType/decodeActorClassType" should "round-trip every known actor class without using the OTHER fallback" in {
    knownActorClassTypes.foreach { value =>
      val (proto, overrideValue) = ActorInteractionCodec.encodeActorClassType(value)
      proto should not be ActorClassType.ACTOR_CLASS_OTHER
      overrideValue shouldBe empty
      ActorInteractionCodec.decodeActorClassType(proto, overrideValue) shouldBe value
    }
  }

  it should "fall back to OTHER + override string for an actor class not yet enumerated" in {
    val (proto, overrideValue) = ActorInteractionCodec.encodeActorClassType("SomeFutureManagerActor")
    proto shouldBe ActorClassType.ACTOR_CLASS_OTHER
    overrideValue shouldBe "SomeFutureManagerActor"
    ActorInteractionCodec.decodeActorClassType(proto, overrideValue) shouldBe "SomeFutureManagerActor"
  }

  "encodeCreationType/decodeCreationType" should "round-trip every CreationTypeEnum value" in {
    CreationTypeEnum.values.foreach { value =>
      val proto = ActorInteractionCodec.encodeCreationType(value.toString)
      ActorInteractionCodec.decodeCreationType(proto) shouldBe value.toString
    }
  }

  it should "map the default CreationTypeEnum (LoadBalancedDistributed) to the proto3 zero-value" in {
    ActorInteractionCodec.encodeCreationType(CreationTypeEnum.LoadBalancedDistributed.toString).value shouldBe 0
  }
}
