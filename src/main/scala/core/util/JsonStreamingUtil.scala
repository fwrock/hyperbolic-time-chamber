package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import com.fasterxml.jackson.core.{ JsonParser, JsonToken }
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.InputStream

/** Opens streaming actor sources using the same Jackson record mapping for every caller. */
object JsonStreamingUtil {
  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  /** Opens a top-level JSON array whose records are decoded on demand.
    *
    * The caller owns the parser and stream for the iterator's lifetime; closing the parser also
    * closes its stream. If construction fails, the caller remains responsible for the input stream.
    * Invalid record boundaries, truncation and trailing content fail as iteration reaches them.
    */
  def createParser(is: InputStream): (JsonParser, Iterator[ActorSimulation]) = {
    val parser = mapper.getFactory.createParser(is)
    require(parser.nextToken() == JsonToken.START_ARRAY, "Actor source JSON must start with '['")
    (parser, new ActorSourceIterator(parser, mapper))
  }
}
