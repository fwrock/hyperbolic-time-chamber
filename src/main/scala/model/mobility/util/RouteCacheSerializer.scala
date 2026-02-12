package org.interscity.htc
package model.mobility.util

import com.esotericsoftware.kryo.kryo5.Kryo
import com.esotericsoftware.kryo.kryo5.io.{ Input, Output }

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import scala.collection.mutable
import scala.util.Try

object RouteCacheSerializer {
  private val kryo = new ThreadLocal[Kryo]() {
    override def initialValue(): Kryo = {
      val k = new Kryo()
      k.register(classOf[Option[_]])
      k.register(classOf[Some[_]])
      k.register(None.getClass)
      k.register(classOf[Tuple2[_, _]])
      k.register(classOf[mutable.Queue[_]]) // Embora vamos converter para List
      k.register(classOf[List[_]])
      k.register(Class.forName("scala.collection.immutable.Nil$"))
      k.register(Class.forName("scala.collection.immutable.$colon$colon"))
      // Adicione outras classes se necessário
      k
    }
  }

  // Vamos serializar Option[(Double, List[(String, String)])]
  // Convertemos a Queue para List para serialização.
  def serialize(data: Option[(Double, List[(String, String)])]): Array[Byte] = {
    val output = new Output(new ByteArrayOutputStream())
    kryo.get().writeClassAndObject(output, data)
    output.close()
    output.getBuffer
  }

  def deserialize(bytes: Array[Byte]): Option[Option[(Double, List[(String, String)])]] =
    Try {
      val input = new Input(new ByteArrayInputStream(bytes))
      val data =
        kryo.get().readClassAndObject(input).asInstanceOf[Option[(Double, List[(String, String)])]]
      input.close()
      Some(data)
    }.getOrElse(None) // Retorna None se a desserialização falhar
}
