package org.interscity.htc
package core.serializer

import core.util.StringUtil

import com.google.protobuf.ByteString
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, SerializerWithStringManifest }
import org.htc.protobuf.core.entity.event.communication.{ ActorInteraction, EntityEnvelope }
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }

import scala.util.{ Failure, Success, Try }

/** Serializes [[EntityEnvelopeEvent]] for cluster-sharded messaging.
  *
  * Sharded simulation traffic (`sendMessageToShard`) always wraps an [[ActorInteractionEvent]]:
  * `EntityEnvelopeEvent(entityId, ActorInteractionEvent(...))`. That case is written as a single
  * `ActorInteraction` protobuf frame (entityId folded into it, see
  * `communication.proto#ActorInteraction.entityId`) instead of nesting a second `EntityEnvelope`
  * frame — with its own manifest/serializer-id bytes — around an already self-describing
  * `ActorInteraction` blob. See docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 1.
  *
  * Any other payload (e.g. `InitializeEvent`, control/load/migration events sent straight to a
  * shard region) is comparatively rare — setup/rebalance traffic, not the per-tick hot path — and
  * keeps the original generic two-layer `EntityEnvelope` framing, since it has no fixed shape to
  * fold into a dedicated proto.
  */
class EntityEnvelopeSerializer(
  val system: ExtendedActorSystem
) extends SerializerWithStringManifest {

  private val GenericManifest = classOf[EntityEnvelopeEvent].getName
  private val ActorInteractionManifest = GenericManifest + "$ActorInteraction"
  private lazy val serialization = SerializationExtension(system)

  override def identifier: Int = 10042004

  override def manifest(o: AnyRef): String = o match {
    case EntityEnvelopeEvent(_, _: ActorInteractionEvent) => ActorInteractionManifest
    case _                                                => GenericManifest
  }

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case EntityEnvelopeEvent(entityId, aie: ActorInteractionEvent) =>
        toBinaryActorInteraction(entityId, aie)

      case EntityEnvelopeEvent(entityId, payload) =>
        toBinaryGeneric(entityId, payload)

      case other =>
        throw new IllegalArgumentException(
          s"Cannot serialize object of type [${other.getClass.getName}]. " +
            s"This serializer only handles [${classOf[EntityEnvelopeEvent].getName}]."
        )
    }

  private def toBinaryActorInteraction(entityId: String, aie: ActorInteractionEvent): Array[Byte] =
    NestedPayloadCodec.encode(serialization, aie.data) match {
      case Success(encoded) =>
        val (actorClassTypeProto, actorClassTypeOverride) = ActorInteractionCodec.encodeActorClassType(aie.actorClassType)
        val (eventTypeProto, eventTypeOverride) = ActorInteractionCodec.encodeEventType(aie.eventType)
        val (entityClassTypeProto, wireEntityId) = ActorInteractionCodec.encodeEntityIdPrefix(entityId)
        val actorRefIdStrippedOpt = ActorInteractionCodec.stripIdPrefix(aie.actorRefId, aie.actorClassType)
        ActorInteraction(
          tick = aie.tick,
          lamportTick = aie.lamportTick,
          actorRefId = actorRefIdStrippedOpt.getOrElse(aie.actorRefId),
          // shardRefId no longer written — receiver derives it from actorClassType, see proto comment.
          actorRef = aie.actorPathRef,
          actorClassType = actorClassTypeProto,
          eventType = eventTypeProto,
          data = ByteString.copyFrom(encoded.bytes),
          payloadSerializerId = encoded.serializerId,
          payloadManifest = encoded.manifest,
          actorType = ActorInteractionCodec.encodeCreationType(aie.actorType),
          resourceId = aie.resourceId,
          entityId = wireEntityId,
          actorClassTypeOverride = actorClassTypeOverride,
          eventTypeOverride = eventTypeOverride,
          entityClassType = entityClassTypeProto,
          actorRefIdPrefixStripped = actorRefIdStrippedOpt.isDefined
        ).toByteArray
      case Failure(exception) =>
        throw new IllegalArgumentException(
          s"Cannot serialize nested payload of type [${aie.data.getClass.getName}] " +
            s"carried by ActorInteractionEvent for entity [$entityId].",
          exception
        )
    }

  private def toBinaryGeneric(entityId: String, payload: AnyRef): Array[Byte] =
    NestedPayloadCodec.encode(serialization, payload) match {
      case Success(encoded) =>
        EntityEnvelope(
          entityId = entityId,
          payload = ByteString.copyFrom(encoded.bytes),
          payloadSerializerId = encoded.serializerId,
          payloadManifest = if (encoded.manifest.nonEmpty) encoded.manifest else payload.getClass.getName
        ).toByteArray
      case Failure(exception) =>
        throw new IllegalArgumentException(
          s"Cannot serialize nested payload of type [${payload.getClass.getName}] " +
            s"for entity [$entityId].",
          exception
        )
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case m if m == ActorInteractionManifest => fromBinaryActorInteraction(bytes)
      case _                                   => fromBinaryGeneric(bytes)
    }

  private def fromBinaryActorInteraction(bytes: Array[Byte]): AnyRef =
    Try {
      val proto = ActorInteraction.parseFrom(bytes)
      NestedPayloadCodec.decode(serialization, proto.data.toByteArray, proto.payloadSerializerId, proto.payloadManifest) match {
        case Success(deserializedPayload) =>
          val decodedActorClassType = ActorInteractionCodec.decodeActorClassType(proto.actorClassType, proto.actorClassTypeOverride)
          val decodedActorRefId =
            if (proto.actorRefIdPrefixStripped) ActorInteractionCodec.rebuildIdPrefix(proto.actorRefId, decodedActorClassType)
            else proto.actorRefId
          EntityEnvelopeEvent(
            ActorInteractionCodec.decodeEntityIdPrefix(proto.entityClassType, proto.entityId),
            ActorInteractionEvent(
              tick = proto.tick,
              lamportTick = proto.lamportTick,
              actorRefId = decodedActorRefId,
              shardRefId = StringUtil.getModelClassName(decodedActorClassType),
              actorPathRef = proto.actorRef,
              actorClassType = decodedActorClassType,
              eventType = ActorInteractionCodec.decodeEventType(proto.eventType, proto.eventTypeOverride),
              data = deserializedPayload,
              actorType = ActorInteractionCodec.decodeCreationType(proto.actorType),
              resourceId = proto.resourceId
            )
          )
        case Failure(exception) =>
          throw new IllegalArgumentException(
            s"Failed to deserialize nested ActorInteraction payload using serializerId [${proto.payloadSerializerId}] " +
              s"and manifest [${proto.payloadManifest}]. Check Pekko serialization configuration for this payload type.",
            exception
          )
      }
    } match {
      case Success(event) => event
      case Failure(ex) =>
        system.log.error(
          ex,
          s"[DIAG-SER] ACTOR-INTERACTION ENVELOPE DESERIALIZATION FAILED — ${ex.getClass.getName}: ${ex.getMessage}"
        )
        throw new IllegalArgumentException(
          s"Failed to deserialize EntityEnvelopeEvent(ActorInteractionEvent) from binary. Error: ${ex.getMessage}",
          ex
        )
    }

  private def fromBinaryGeneric(bytes: Array[Byte]): AnyRef =
    Try {
      val proto: EntityEnvelope = EntityEnvelope.parseFrom(bytes)
      NestedPayloadCodec.decode(serialization, proto.payload.toByteArray, proto.payloadSerializerId, proto.payloadManifest) match {
        case Success(deserializedPayload) =>
          EntityEnvelopeEvent(proto.entityId, deserializedPayload)
        case Failure(exception) =>
          system.log.error(
            exception,
            s"[DIAG-SER] DESERIALIZATION FAILED for entity=${proto.entityId} manifest=${proto.payloadManifest} serializerId=${proto.payloadSerializerId} — ${exception.getClass.getName}: ${exception.getMessage}"
          )
          throw new IllegalArgumentException(
            s"Failed to deserialize nested payload using serializerId [${proto.payloadSerializerId}] " +
              s"and manifest [${proto.payloadManifest}]. Check Pekko serialization configuration for this payload type.",
            exception
          )
      }
    } match {
      case Success(event) => event
      case Failure(ex) =>
        system.log.error(
          ex,
          s"[DIAG-SER] ENVELOPE DESERIALIZATION FAILED — ${ex.getClass.getName}: ${ex.getMessage}"
        )
        throw new IllegalArgumentException(
          s"Failed to deserialize EntityEnvelopeEvent from binary. Error: ${ex.getMessage}",
          ex
        )
    }
}
