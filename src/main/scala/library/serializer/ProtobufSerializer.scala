package org.interscity.htc
package library.serializer

import org.apache.pekko.serialization.SerializerWithStringManifest

class ProtobufSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 27011998

  override def manifest(o: AnyRef): String = ""

  override def toBinary(o: AnyRef): Array[Byte] = ???

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = ???
}
