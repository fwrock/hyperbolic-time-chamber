package org.interscity.htc
package core.serializer

import com.google.protobuf.ByteString
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializer => PekkoSerializer, SerializerWithStringManifest }
import org.htc.protobuf.core.entity.event.communication.EntityEnvelope
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent

import scala.util.{ Failure, Success, Try }

class EntityEnvelopeSerializer(
  val system: ExtendedActorSystem
) extends SerializerWithStringManifest {

  private val EnvelopeManifest = classOf[EntityEnvelopeEvent].getName
  private lazy val serialization = SerializationExtension(system)

  override def identifier: Int = 10042004

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case EntityEnvelopeEvent(entityId, payload) =>
        val payloadSerializer: PekkoSerializer = serialization.serializerFor(payload.getClass)

        val payloadManifest: String = payloadSerializer match {
          case s: SerializerWithStringManifest => s.manifest(payload)
          case _                               => ""
        }
        val triedSerializedPayload: Try[Array[Byte]] = Try(payloadSerializer.toBinary(payload))

        triedSerializedPayload match {
          case Success(serializedPayloadBytes) =>
            val proto = EntityEnvelope(
              entityId = entityId,
              payload = ByteString.copyFrom(serializedPayloadBytes),
              payloadSerializerId = payloadSerializer.identifier,
              payloadManifest = payloadManifest
            )
            proto.toByteArray
          case Failure(exception) =>
            throw new IllegalArgumentException(
              s"Cannot serialize nested payload of type [${payload.getClass.getName}] " +
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
      val proto: EntityEnvelope = EntityEnvelope.parseFrom(bytes)

      val payloadBytes: Array[Byte] = proto.payload.toByteArray
      val payloadSerializerId: Int = proto.payloadSerializerId
      val payloadManifest: String = proto.payloadManifest

      val triedDeserializedPayload: Try[AnyRef] = serialization.deserialize(
        payloadBytes,
        payloadSerializerId,
        payloadManifest
      )
      triedDeserializedPayload match {
        case Success(deserializedPayload) =>
          EntityEnvelopeEvent(proto.entityId, deserializedPayload)
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
