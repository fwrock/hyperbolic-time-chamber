package org.interscity.htc
package model.hybrid.util

import com.fasterxml.jackson.databind.{ DeserializationContext, KeyDeserializer }
import model.hybrid.entity.state.model.SubRoutePair

object SubRoutePairKeys {
  class Deserializer extends KeyDeserializer {
    override def deserializeKey(key: String, ctxt: DeserializationContext): Object = {
      val parts = key.split(":")
      if (parts.length == 2) SubRoutePair(parts(0).toLong, parts(1).toLong)
      else throw new IllegalArgumentException(s"Invalid SubRoutePair key format: $key")
    }
  }
}
