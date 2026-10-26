package org.interscity.htc
package core.actor.manager.loadbalance.migration

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.runtime.conservative.ConservativeQueuedInput
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializers }
import java.util.Base64

object ConservativeMigrationCodec {
  def encode(system: ActorSystem, event: AnyRef): SerializedMigrationInput = {
    require(event.isInstanceOf[ActorInteractionEvent] || event.isInstanceOf[SpontaneousEvent], "invalid migration input")
    val serialization = SerializationExtension(system)
    // Network event codecs may normalize aliases; a checkpoint must retain the accepted input verbatim.
    val saved = ConservativeQueuedInput(event)
    val codec = serialization.findSerializerFor(saved)
    SerializedMigrationInput(codec.identifier, Serializers.manifestFor(codec, saved),
      Base64.getEncoder.encodeToString(serialization.serialize(saved).get))
  }
  def decode(system: ActorSystem, input: SerializedMigrationInput): AnyRef = {
    val saved = SerializationExtension(system).deserialize(Base64.getDecoder.decode(input.payload),
      input.serializerId, input.manifest).get
    val value = saved match {
      case ConservativeQueuedInput(event) => event
      case _ => throw new IllegalArgumentException("invalid conservative migration queue frame")
    }
    require(value.isInstanceOf[ActorInteractionEvent] || value.isInstanceOf[SpontaneousEvent], "invalid decoded migration input")
    value
  }
}
