package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

case class SubRoutePair(
  @JsonProperty("origin") origin: String,
  @JsonProperty("destination") destination: String
) {
  override def toString: String = s"$origin:$destination"
}

object SubRoutePair {
  @JsonCreator
  def fromString(key: String): SubRoutePair = {
    val parts = key.split(":")
    if (parts.length == 2) {
      SubRoutePair(parts(0), parts(1))
    } else {
      throw new IllegalArgumentException(s"Invalid SubRoutePair key format: $key")
    }
  }
}
