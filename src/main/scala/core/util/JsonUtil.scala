package org.interscity.htc
package core.util

import com.fasterxml.jackson.core.`type`.TypeReference

import scala.io.Source
import com.fasterxml.jackson.databind.{ DeserializationFeature, ObjectMapper }
import com.fasterxml.jackson.databind.`type`.TypeFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.google.protobuf.ByteString

object JsonUtil {

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)
  mapper.registerModule(new JavaTimeModule())
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def readJsonFile(filePath: String): String = {
    val source = Source.fromFile(filePath)
    try source.getLines().mkString("\n")
    finally source.close()
  }

  def convertValue[T: Manifest](content: Any): T = {
    val json = mapper.writeValueAsString(content) // Transforma o Map em JSON
    val javaType = TypeFactory.defaultInstance().constructType(implicitly[Manifest[T]].runtimeClass)
    mapper.readValue(json, javaType).asInstanceOf[T] // Converte corretamente para a case class
  }

  def convertValueByString[T](content: ByteString)(implicit m: Manifest[T]): T =
    mapper.convertValue(content.toByteArray, m.runtimeClass).asInstanceOf[T]

  def convertValue(content: Any, className: String): Any = {
    val clazz = Class.forName(className)
    mapper.convertValue(content, clazz)
  }

  def toJson[T](data: T): String =
    mapper.writeValueAsString(data)

  def fromJson[T](data: String)(implicit m: Manifest[T]): T = {
    val javaType = TypeFactory.defaultInstance().constructType(m.runtimeClass)
    mapper.readValue[T](data, javaType)
  }

  def fromJsonManifest[T: Manifest](data: String): T = {
    val typeReference = new TypeReference[T] {}
    mapper.readValue[T](data, typeReference)
  }

  def fromJsonList[T](data: String)(implicit m: Manifest[T]): Seq[T] = {
    import scala.jdk.CollectionConverters._
    val javaType = TypeFactory
      .defaultInstance()
      .constructCollectionType(classOf[java.util.List[_]], m.runtimeClass)
    mapper.readValue(data, javaType).asInstanceOf[java.util.List[T]].asScala.toSeq
  }

  def fromJsonClassName[T](json: String, className: String): T = {
    val clazz = Class.forName(className).asInstanceOf[Class[T]]
    mapper.readValue(json, clazz)
  }

  def jsonToObject[T](json: String, clazz: Class[T]): T =
    mapper.readValue(json, clazz)
}
