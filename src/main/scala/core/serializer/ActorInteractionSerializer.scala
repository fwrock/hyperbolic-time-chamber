package org.interscity.htc
package core.serializer

import core.entity.event.{ActorInteractionEvent, EntityEnvelopeEvent}

import com.google.protobuf.ByteString
import org.apache.pekko.actor.{ActorRef, ExtendedActorSystem}
import org.apache.pekko.serialization.{SerializationExtension, SerializerWithStringManifest}
import org.htc.protobuf.core.entity.event.communication.{ActorInteraction, EntityEnvelope}
import org.interscity.htc.core.types.CoreTypes.Tick

import scala.util.{Failure, Success}

class ActorInteractionSerializer(
  val system: ExtendedActorSystem
                              ) extends SerializerWithStringManifest {

  private val EnvelopeManifest = classOf[ActorInteractionEvent].getName
  private lazy val serialization = SerializationExtension(system)

  override def identifier: Int = 16071977

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case ActorInteractionEvent(tick, lamportTick, actorRefId, actorRef, actorClassType, eventType, data) =>
        val triedSerializedPayload = serialization.serialize(data)
        
        triedSerializedPayload match {
          case Success(serializedPayload) =>
            val proto = ActorInteraction(
              tick,
              lamportTick,
              actorRefId,
              actorRef,
              actorClassType,
              eventType,
              ByteString.copyFrom(serializedPayload)
            )
            proto.toByteArray
          case Failure(exception) =>
            throw new IllegalArgumentException(s"Cannot serialize payload", exception)
        }

      case _ => throw new IllegalArgumentException(s"Cannot serialize object of type: ${o.getClass.getName}")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val proto = ActorInteraction.parseFrom(bytes)
    val triedDeserializedPayload = serialization.deserialize(proto.data.toByteArray, classOf[AnyRef])
    
    triedDeserializedPayload match {
      case Success(deserializedPayload) =>
        ActorInteractionEvent(
          proto.tick,
          proto.lamportTick,
          proto.actorRefId,
          proto.actorRef,
          proto.actorClassType,
          proto.eventType,
          deserializedPayload
        )
      case Failure(exception) =>
        throw new IllegalArgumentException(s"Error deserializing payload", exception)
    }
  }
}
