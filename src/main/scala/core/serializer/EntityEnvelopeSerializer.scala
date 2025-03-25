package org.interscity.htc
package core.serializer

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.{Serialization, SerializationExtension, SerializerWithStringManifest}
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent

import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}

class EntityEnvelopeSerializer(
  system: ExtendedActorSystem
                              ) extends SerializerWithStringManifest {

  private val EnvelopeManifest = classOf[EntityEnvelopeEvent].getName
  private val serialization = SerializationExtension(system)

  override def identifier: Int = 10042004

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case EntityEnvelopeEvent(entityId, payload) =>
        val entityIdBytes = entityId.getBytes(StandardCharsets.UTF_8)
        val triedSerializedPayload: Try[Array[Byte]] = serialization.serialize(payload)

        triedSerializedPayload match {
          case Success(serializedPayload) =>
            val entityIdLength = entityIdBytes.length
            val payloadLength = serializedPayload.length
            val buffer = java.nio.ByteBuffer.allocate(4 + entityIdLength + 4 + payloadLength)
            buffer.putInt(entityIdLength)
            buffer.put(entityIdBytes)
            buffer.putInt(payloadLength)
            buffer.put(serializedPayload)
            buffer.array()
          case Failure(exception) =>
            throw new IllegalArgumentException(
              s"Cannot serialize payload of type: ${payload.getClass.getName}", exception
            )
        }
      case _ =>
        throw new IllegalArgumentException(s"Cannot serialize object of type: ${o.getClass.getName}")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    val entityIdLength = buffer.getInt
    val entityIdBytes = new Array[Byte](entityIdLength)
    buffer.get(entityIdBytes)
    val payloadLength = buffer.getInt
    val payloadBytes = new Array[Byte](payloadLength)
    buffer.get(payloadBytes)

    val entityId = new String(entityIdBytes, StandardCharsets.UTF_8)

    // Correctly use serialization.deserialize with the appropriate class
    serialization.deserialize(payloadBytes, classOf[AnyRef]) match { // or a more specific supertype if you have one
      case Success(deserializedPayload) =>
        EntityEnvelopeEvent(entityId, deserializedPayload)
      case Failure(exception) =>
        throw new IllegalArgumentException(s"Error deserializing payload",exception)
    }
  }
}
