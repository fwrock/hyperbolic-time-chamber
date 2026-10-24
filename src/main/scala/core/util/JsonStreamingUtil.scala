package org.interscity.htc
package core.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import org.interscity.htc.core.entity.actor.ActorSimulation

import java.io.InputStream

object JsonStreamingUtil {
  private val mapper = new ObjectMapper()
  mapper.registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)

  /** The caller owns the parser and stream for the lifetime of the iterator. */
  def createParser(is: InputStream): (JsonParser, Iterator[ActorSimulation]) = {
    val parser = mapper.getFactory.createParser(is)
    require(parser.nextToken() == JsonToken.START_ARRAY, "Actor source JSON must start with '['")
    // Own the array boundary explicitly: a MappingIterator positioned at END_ARRAY tries
    // decoding it as a record. Per-record binding also works across stream buffer refills.
    val iter = new Iterator[ActorSimulation] {
      private var token = parser.nextToken()
      private var endChecked = false
      override def hasNext: Boolean = {
        require(token != null, "Truncated actor source JSON array")
        if (token == JsonToken.END_ARRAY) {
          if (!endChecked) {
            require(parser.nextToken() == null, "Trailing content after actor source JSON array")
            endChecked = true
          }
          false
        } else {
          require(token == JsonToken.START_OBJECT, "Actor source JSON array must contain objects")
          true
        }
      }
      override def next(): ActorSimulation = {
        if (!hasNext) throw new NoSuchElementException("End of actor source JSON array")
        val actor = mapper.readValue(parser, classOf[ActorSimulation])
        token = parser.nextToken()
        actor
      }
    }
    (parser, iter)
  }
}
