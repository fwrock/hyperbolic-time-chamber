package org.interscity.htc
package core.serializer

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import org.interscity.htc.core.entity.Serializable

import java.io.NotSerializableException
import scala.util.{Failure, Success, Try}

class ProtobufSerializer(
  val system: ExtendedActorSystem
                        ) extends SerializerWithStringManifest {

  override def identifier: Int = 27011998

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case ser: Serializable => ser.toByteArray
      case _ =>
        throw new IllegalArgumentException(
          s"Não é possível serializar [${o.getClass.getName}] com ProtobufSerializer. " +
            s"Ele não estende ${classOf[Serializable].getName}"
        )
    }
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val triedClazz: Try[Class[_ <: Serializable]] =
      system.dynamicAccess.getClassFor[Serializable](manifest)

    triedClazz match {
      case Success(clazz) =>
        Try {
          val companionClassName = clazz.getName + "$"
          val companionClass = system.dynamicAccess.getClassFor[AnyRef](companionClassName).get
          val moduleField = companionClass.getField("MODULE$")
          val companionObject = moduleField.get(null)

          val parseFromMethod = companionObject.getClass.getMethod("parseFrom", classOf[Array[Byte]])

          parseFromMethod.invoke(companionObject, bytes)

        } match {
          case Success(instance: AnyRef) => instance
          case Failure(e) =>
            val ex = new NotSerializableException(s"Falha ao invocar parseFrom para o manifesto [$manifest]")
            ex.initCause(e)
            throw ex
          case Success(other) =>
            throw new NotSerializableException(s"parseFrom retornou um tipo inesperado [$other] para o manifesto [$manifest]")

        }
      case Failure(exception) =>
        throw {
          val ex = new NotSerializableException(s"Não foi possível carregar a classe para o manifesto [$manifest]: ${exception.getMessage}")
          ex.initCause(exception)
          ex
        }
    }
  }
}
