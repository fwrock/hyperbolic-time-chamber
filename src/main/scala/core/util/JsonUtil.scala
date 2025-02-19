package org.interscity.htc
package core.util

import com.fasterxml.jackson.core.`type`.TypeReference

import scala.io.Source
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object JsonUtil {

  private val mapper = new ObjectMapper()

  def readJsonFile(filePath: String): String = {
    val source = Source.fromFile(filePath)
    try source.getLines().mkString("\n")
    finally source.close()
  }

  def toJson[T](data: T): String = {
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new JavaTimeModule())
    mapper.writeValueAsString(data)
  }

  def fromJson[T](data: String)(implicit m: Manifest[T]): T = {
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new JavaTimeModule())
    mapper.readValue[T](
      data,
      new TypeReference[T] {
        override def getType: Class[T] = m.runtimeClass.asInstanceOf[Class[T]]
      }
    )
  }

  def fromJsonClassName[T](json: String, className: String): T = {
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new JavaTimeModule())
    val clazz = Class.forName(className).asInstanceOf[Class[T]]
    mapper.readValue(json, clazz)
  }

  def jsonToObject[T](json: String, clazz: Class[T]): T = {
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new JavaTimeModule())
    mapper.readValue(json, clazz)
  }
}
