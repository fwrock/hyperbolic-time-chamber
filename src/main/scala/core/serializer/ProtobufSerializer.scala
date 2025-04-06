package org.interscity.htc
package core.serializer

import scalapb.{ GeneratedMessage, Message }
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest

import java.io.NotSerializableException
import scala.util.{ Failure, Success, Try }

class ProtobufSerializer(
  val system: ExtendedActorSystem
) extends SerializerWithStringManifest {

  override def identifier: Int = 27011998

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case ser: GeneratedMessage => ser.toByteArray
      case _ =>
        throw new IllegalArgumentException(
          s"It's not possible to serializer [${o.getClass.getName}] using ProtobufSerializer. " +
            s"It's not extends ${classOf[GeneratedMessage].getName}"
        )
    }
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = null
}
