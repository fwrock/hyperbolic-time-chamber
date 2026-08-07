package org.interscity.htc
package core.serializer

import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }
import core.util.StringUtil

import com.google.protobuf.ByteString
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, SerializerWithStringManifest }
import org.htc.protobuf.core.entity.event.communication.ActorInteraction

import scala.util.{ Failure, Success, Try }

class ActorInteractionSerializer(
  val system: ExtendedActorSystem
) extends SerializerWithStringManifest {

  private val EnvelopeManifest = classOf[ActorInteractionEvent].getName
  private lazy val serialization = SerializationExtension(system)

  override def identifier: Int = 16071977

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case ActorInteractionEvent(
            tick,
            lamportTick,
            actorRefId,
            shardRefId,
            actorRef,
            actorClassType,
            eventType,
            data,
            actorType,
            resourceId
          ) =>
        NestedPayloadCodec.encode(serialization, data) match {
          case Success(encoded) =>
            val (actorClassTypeProto, actorClassTypeOverride) = ActorInteractionCodec.encodeActorClassType(actorClassType)
            val (eventTypeProto, eventTypeOverride) = ActorInteractionCodec.encodeEventType(eventType)
            val actorRefIdStrippedOpt = ActorInteractionCodec.stripIdPrefix(actorRefId, actorClassType)
            val proto = ActorInteraction(
              tick = tick,
              lamportTick = lamportTick,
              actorRefId = actorRefIdStrippedOpt.getOrElse(actorRefId),
              // shardRefId no longer written — receiver derives it from actorClassType, see proto comment.
              actorRef = actorRef,
              actorClassType = actorClassTypeProto,
              eventType = eventTypeProto,
              data = ByteString.copyFrom(encoded.bytes),
              payloadSerializerId = encoded.serializerId,
              payloadManifest = encoded.manifest,
              actorType = ActorInteractionCodec.encodeCreationType(actorType),
              resourceId = resourceId,
              actorClassTypeOverride = actorClassTypeOverride,
              eventTypeOverride = eventTypeOverride,
              actorRefIdPrefixStripped = actorRefIdStrippedOpt.isDefined
            )
            proto.toByteArray
          case Failure(exception) =>
            throw new IllegalArgumentException(
              s"Cannot serialize nested payload of type [${data.getClass.getName}] carried by a standalone ActorInteractionEvent.",
              exception
            )
        }

      case other =>
        throw new IllegalArgumentException(
          s"Cannot serialize object of type [${other.getClass.getName}]. " +
            s"This serializer only handles [${classOf[EntityEnvelopeEvent].getName}]."
        )
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    Try {
      val proto = ActorInteraction.parseFrom(bytes)

      NestedPayloadCodec.decode(serialization, proto.data.toByteArray, proto.payloadSerializerId, proto.payloadManifest) match {
        case Success(deserializedPayload) =>
          val decodedActorClassType = ActorInteractionCodec.decodeActorClassType(proto.actorClassType, proto.actorClassTypeOverride)
          val decodedActorRefId =
            if (proto.actorRefIdPrefixStripped) ActorInteractionCodec.rebuildIdPrefix(proto.actorRefId, decodedActorClassType)
            else proto.actorRefId
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
        case Failure(exception) =>
          throw new IllegalArgumentException(
            s"Failed to deserialize nested payload using serializerId [${proto.payloadSerializerId}] " +
              s"and manifest [${proto.payloadManifest}]. Check Pekko serialization configuration for this payload type.",
            exception
          )
      }
    } match {
      case Success(event) => event
      case Failure(ex) =>
        throw new IllegalArgumentException(
          s"Failed to deserialize EntityEnvelopeEvent from binary. " +
            s"Manifest provided was [$manifest]. Error: ${ex.getMessage}",
          ex
        )
    }
}
