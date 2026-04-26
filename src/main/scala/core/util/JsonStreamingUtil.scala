package org.interscity.htc
package core.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonParser
import org.interscity.htc.core.entity.actor.ActorSimulation

import java.io.InputStream
import scala.jdk.CollectionConverters.*

object JsonStreamingUtil {
  private val mapper = new ObjectMapper()
  mapper.registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)

  /**
   * Creates a streaming parser over ActorSimulation objects from a JSON array.
   *
   * Reads the full InputStream into a byte[] before creating the parser to avoid
   * a Jackson bug where the internal 4000-byte read buffer refill at exact multiples
   * of 4000 bytes (e.g. offset 96000 = 4000×24) causes spurious parse errors on
   * otherwise valid JSON.
   */
  def createParser(is: InputStream): (JsonParser, Iterator[ActorSimulation]) = {
    val bytes = is.readAllBytes()
    val parser = mapper.getFactory.createParser(bytes)
    if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
      throw new IllegalStateException("JSON deve começar com '['")
    }
    parser.nextToken()
    val iter = mapper.readValues[ActorSimulation](parser, classOf[ActorSimulation]).asScala
    (parser, iter)
  }
}
