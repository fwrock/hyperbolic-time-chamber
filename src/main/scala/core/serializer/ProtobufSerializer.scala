package org.interscity.htc
package core.serializer

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import org.interscity.htc.core.entity.event.SerializableEvent

import java.io.NotSerializableException
import scala.util.{Failure, Success, Try}

class ProtobufSerializer(
  val system: ExtendedActorSystem
                        ) extends SerializerWithStringManifest {

  override def identifier: Int = 27011998

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case event: SerializableEvent => event.toByteArray
      case _ =>
        throw new IllegalArgumentException(
          s"Cannot serialize object of type: ${o.getClass.getName}. It does not extend SerializableEvent."
        )
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    system.dynamicAccess.getClassFor[SerializableEvent](manifest) match {
      case Success(clazz) =>
        if (classOf[SerializableEvent].isAssignableFrom(clazz)) {
          val parseFromMethod = clazz.getMethod("parseFrom", classOf[Array[Byte]])
          parseFromMethod.invoke(null, bytes).asInstanceOf[SerializableEvent]
        } else {
          throw new NotSerializableException(
            s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]"
          )
        }
      case Failure(exception) =>
        throw new NotSerializableException(
          s"Error deserializing message with manifest [$manifest]: ${exception.getMessage}"
        )
    }
}
