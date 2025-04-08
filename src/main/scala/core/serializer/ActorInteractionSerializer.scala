package org.interscity.htc
package core.serializer

import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }

import com.google.protobuf.ByteString
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializer as PekkoSerializer, SerializerWithStringManifest }
import org.htc.protobuf.core.entity.event.communication.{ ActorInteraction, EntityEnvelope }

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
            data
          ) =>
        val payloadSerializer: PekkoSerializer = serialization.serializerFor(data.getClass)

        val payloadManifest: String = payloadSerializer match {
          case s: SerializerWithStringManifest => s.manifest(data)
          case _                               => ""
        }

        val triedSerializedPayload: Try[Array[Byte]] = Try(payloadSerializer.toBinary(data))

        triedSerializedPayload match {
          case Success(serializedPayload) =>
            val proto = ActorInteraction(
              tick = tick,
              lamportTick = lamportTick,
              actorRefId = actorRefId,
              actorRef = actorRef,
              actorClassType = actorClassType,
              eventType = eventType,
              data = ByteString.copyFrom(serializedPayload),
              payloadSerializerId = payloadSerializer.identifier,
              payloadManifest = payloadManifest
            )
            proto.toByteArray
          case Failure(exception) =>
            throw new IllegalArgumentException(
              s"Cannot serialize nested payload of type [${data.getClass.getName}] " +
                s"using serializerId [${payloadSerializer.identifier}] and manifest [$payloadManifest].",
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

      val payloadBytes: Array[Byte] = proto.data.toByteArray
      val payloadSerializerId: Int = proto.payloadSerializerId
      val payloadManifest: String = proto.payloadManifest

      val triedDeserializedPayload: Try[AnyRef] = serialization.deserialize(
        payloadBytes,
        payloadSerializerId,
        payloadManifest
      )

      triedDeserializedPayload match {
        case Success(deserializedPayload) =>
          ActorInteractionEvent(
            tick = proto.tick,
            lamportTick = proto.lamportTick,
            actorRefId = proto.actorRefId,
            shardRefId = proto.shardRefId,
            actorPathRef = proto.actorRef,
            actorClassType = proto.actorClassType,
            eventType = proto.eventType,
            data = deserializedPayload
          )
        case Failure(exception) =>
          throw new IllegalArgumentException(
            s"Failed to deserialize nested payload using serializerId [$payloadSerializerId] " +
              s"and manifest [$payloadManifest]. Check Pekko serialization configuration for this payload type.",
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
