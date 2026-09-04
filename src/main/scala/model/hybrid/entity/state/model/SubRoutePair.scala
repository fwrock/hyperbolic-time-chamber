package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

case class SubRoutePair(
  @JsonProperty("origin") origin: Long,
  @JsonProperty("destination") destination: Long
) {
  override def toString: String = s"$origin:$destination"
}

object SubRoutePair {
  @JsonCreator
  def fromString(key: String): SubRoutePair = {
    val parts = key.split(":")
    if (parts.length == 2) {
      SubRoutePair(parts(0).toLong, parts(1).toLong)
    } else {
      throw new IllegalArgumentException(s"Invalid SubRoutePair key format: $key")
    }
  }
}
