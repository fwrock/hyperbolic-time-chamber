package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import com.fasterxml.jackson.core.{ JsonParser, JsonToken }
import com.fasterxml.jackson.databind.ObjectMapper

/** Decodes an actor array incrementally without owning its parser or input stream.
  *
  * The parser must be positioned at the opening array token. Per-record binding preserves streaming
  * across buffer refills; array boundaries are handled here because a MappingIterator positioned at
  * END_ARRAY would attempt to decode that boundary as another record.
  */
private[util] final class ActorSourceIterator(parser: JsonParser, mapper: ObjectMapper)
    extends Iterator[ActorSimulation] {
  private var token = parser.nextToken()
  private var endChecked = false

  /** Validates the next record boundary, checking trailing content only after reaching array end.
    */
  override def hasNext: Boolean = {
    require(token != null, "Truncated actor source JSON array")
    if (token == JsonToken.END_ARRAY) {
      checkEndOfInput()
      false
    } else {
      require(token == JsonToken.START_OBJECT, "Actor source JSON array must contain objects")
      true
    }
  }

  /** Decodes one actor and advances the boundary; throws when the array is exhausted or invalid. */
  override def next(): ActorSimulation = {
    if (!hasNext) throw new NoSuchElementException("End of actor source JSON array")
    val actor = mapper.readValue(parser, classOf[ActorSimulation])
    token = parser.nextToken()
    actor
  }

  /** Checks EOF once so repeated exhaustion checks never advance the parser again. */
  private def checkEndOfInput(): Unit =
    if (!endChecked) {
      require(parser.nextToken() == null, "Trailing content after actor source JSON array")
      endChecked = true
    }
}
