package org.interscity.htc
package core.serializer

import com.google.protobuf.ByteString
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{Serialization, SerializationExtension, SerializerWithStringManifest}
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.protobuf.EntityEnvelope.EntityEnvelope

import java.nio.charset.StandardCharsets
import scala.util.{Failure, Success, Try}

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
        val triedSerializedPayload = serialization.serialize(payload)

        triedSerializedPayload match {
          case Success(serializedPayload) =>
            val proto = EntityEnvelope(entityId, ByteString.copyFrom(serializedPayload))
            proto.toByteArray
          case Failure(exception) =>
            throw new IllegalArgumentException(s"Cannot serialize payload", exception)
        }

      case _ => throw new IllegalArgumentException(s"Cannot serialize object of type: ${o.getClass.getName}")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val proto = EntityEnvelope.parseFrom(bytes)
    val triedDeserializedPayload = serialization.deserialize(proto.payload.toByteArray, classOf[AnyRef])
    
    triedDeserializedPayload match {
      case Success(deserializedPayload) =>
        EntityEnvelopeEvent(proto.entityId, deserializedPayload)
      case Failure(exception) =>
        throw new IllegalArgumentException(s"Error deserializing payload", exception)
    }
  }
}
