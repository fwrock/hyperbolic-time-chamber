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

  def createParser(is: InputStream): (JsonParser, Iterator[ActorSimulation]) = {
    val parser = mapper.getFactory.createParser(is)
    if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
      throw new IllegalStateException("JSON deve começar com '['")
    }
    parser.nextToken() // Pula o '['
    val iter = mapper.readValues[ActorSimulation](parser, classOf[ActorSimulation]).asScala
    (parser, iter)
  }
}