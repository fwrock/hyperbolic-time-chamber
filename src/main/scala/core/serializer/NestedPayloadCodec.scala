package org.interscity.htc
package core.serializer

import org.apache.pekko.serialization.{ Serialization, Serializer as PekkoSerializer, SerializerWithStringManifest }

import scala.util.Try

/** Shared glue for serializing/deserializing an arbitrary nested payload (the `data` carried by
  * [[org.interscity.htc.core.entity.event.ActorInteractionEvent]]) using whatever serializer is
  * registered for its runtime class. Used by both [[EntityEnvelopeSerializer]] (fast path:
  * `EntityEnvelopeEvent` wrapping an `ActorInteractionEvent`) and [[ActorInteractionSerializer]]
  * (standalone `ActorInteractionEvent`, e.g. sent directly to a pool router) so the two frames
  * stay consistent without duplicating the resolve/serialize/deserialize logic.
  */
private[serializer] object NestedPayloadCodec {

  final case class Encoded(bytes: Array[Byte], serializerId: Int, manifest: String)

  def encode(serialization: Serialization, data: AnyRef): Try[Encoded] = Try {
    val payloadSerializer: PekkoSerializer = serialization.serializerFor(data.getClass)
    val payloadManifest: String = payloadSerializer match {
      case s: SerializerWithStringManifest => s.manifest(data)
      case _                               => ""
    }
    Encoded(payloadSerializer.toBinary(data), payloadSerializer.identifier, payloadManifest)
  }

  def decode(serialization: Serialization, bytes: Array[Byte], serializerId: Int, manifest: String): Try[AnyRef] =
    serialization.deserialize(bytes, serializerId, manifest)
}
