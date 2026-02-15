package org.interscity.htc
package core.util

import com.fasterxml.jackson.core.`type`.TypeReference

import scala.io.Source
import com.fasterxml.jackson.databind.{ DeserializationFeature, JavaType, ObjectMapper }
import com.fasterxml.jackson.databind.`type`.TypeFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{KeyDeserializer, DeserializationContext, JsonDeserializer}
import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.google.protobuf.ByteString

import java.io.InputStream

import scala.jdk.CollectionConverters._

object JsonUtil {

  // Custom key deserializer for SubRoutePair
  class SubRoutePairKeyDeserializer extends KeyDeserializer {
    override def deserializeKey(key: String, ctxt: DeserializationContext): Object = {
      val parts = key.split(":")
      if (parts.length == 2) {
        // Try both hybrid and mobility versions
        try {
          org.interscity.htc.model.hybrid.entity.state.model.SubRoutePair(parts(0), parts(1))
        } catch {
          case _: Exception =>
            org.interscity.htc.model.mobility.entity.state.model.SubRoutePair(parts(0), parts(1))
        }
      } else {
        throw new IllegalArgumentException(s"Invalid SubRoutePair key format: $key")
      }
    }
  }

  // Custom deserializer for Tuple2 with SubwayStationNode
  class SubwayStationNodeTupleDeserializer extends JsonDeserializer[(org.interscity.htc.model.hybrid.entity.state.model.SubwayStationNode, String)] {
    override def deserialize(p: JsonParser, ctxt: DeserializationContext): (org.interscity.htc.model.hybrid.entity.state.model.SubwayStationNode, String) = {
      if (p.getCurrentToken == JsonToken.START_OBJECT) {
        val node = p.readValuesAs(classOf[java.util.Map[String, Object]])
        if (node.hasNext) {
          val obj = node.next()
          val stationId = obj.get("stationId").asInstanceOf[String]
          val nodeId = obj.get("nodeId").asInstanceOf[String]
          val railLinkId = obj.getOrDefault("railLinkId", obj.get("linkId")).asInstanceOf[String]
          
          val subwayStationNode = org.interscity.htc.model.hybrid.entity.state.model.SubwayStationNode(stationId, nodeId)
          (subwayStationNode, railLinkId)
        } else {
          throw new RuntimeException("Invalid SubwayStationNode tuple format")
        }
      } else {
        // Try array format [{"stationId": "...", "nodeId": "..."}, "link_id"]
        val codec = p.getCodec
        val array = codec.readValue(p, classOf[Array[Object]])
        if (array.length == 2) {
          val nodeMap = array(0).asInstanceOf[java.util.Map[String, String]]
          val stationId = nodeMap.get("stationId")
          val nodeId = nodeMap.get("nodeId")
          val railLinkId = array(1).asInstanceOf[String]
          
          val subwayStationNode = org.interscity.htc.model.hybrid.entity.state.model.SubwayStationNode(stationId, nodeId)
          (subwayStationNode, railLinkId)
        } else {
          throw new RuntimeException(s"Invalid SubwayStationNode tuple array format: expected 2 elements, got ${array.length}")
        }
      }
    }
  }

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)
  mapper.registerModule(new JavaTimeModule())
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  // Register custom key deserializers for SubRoutePair
  private val subRoutePairModule = new SimpleModule()
  subRoutePairModule.addKeyDeserializer(classOf[org.interscity.htc.model.hybrid.entity.state.model.SubRoutePair], new SubRoutePairKeyDeserializer)
  subRoutePairModule.addKeyDeserializer(classOf[org.interscity.htc.model.mobility.entity.state.model.SubRoutePair], new SubRoutePairKeyDeserializer)
  
  // Register custom deserializer for SubwayStationNode tuples
  subRoutePairModule.addDeserializer(
    classOf[(org.interscity.htc.model.hybrid.entity.state.model.SubwayStationNode, String)], 
    new SubwayStationNodeTupleDeserializer
  )
  
  mapper.registerModule(subRoutePairModule)

  def readJsonFile(filePath: String): String = {
    val source = Source.fromFile(filePath)
    try source.getLines().mkString("\n")
    finally source.close()
  }

  def convertValue[T: Manifest](content: Any): T = {
    val json = mapper.writeValueAsString(content)
    val javaType = TypeFactory.defaultInstance().constructType(implicitly[Manifest[T]].runtimeClass)
    mapper.readValue(json, javaType).asInstanceOf[T]
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

  def writeJsonBytes[T](data: T): Array[Byte] =
    mapper.writeValueAsBytes(data)

  def fromJsonBytes[T](data: Array[Byte])(implicit m: Manifest[T]): T = {
    val javaType = TypeFactory.defaultInstance().constructType(m.runtimeClass)
    mapper.readValue[T](data, javaType)
  }

  def fromJsonListStream[A](
    jsonStream: InputStream
  )(implicit elementManifest: Manifest[A]): List[A] = {
    val typeFactory = TypeFactory.defaultInstance()

    // 1. Construir o JavaType para java.util.List<A>
    //    elementManifest.runtimeClass fornecerá a classe de 'A' (ex: ActorSimulation.class)
    val javaListType: JavaType = typeFactory.constructCollectionType(
      classOf[java.util.List[_]], // Alvo: java.util.List genérico
      elementManifest.runtimeClass // Classe dos elementos
    )

    // 2. Desserializar para uma java.util.List<A>
    val javaList: java.util.List[A] = mapper.readValue(jsonStream, javaListType)

    // 3. Converter a lista Java para uma lista Scala imutável
    javaList.asScala.toList
  }

  // fromJsonStream genérico (com a mesma ressalva para construção de JavaType para genéricos complexos)
  def fromJsonStream[T](jsonStream: InputStream)(implicit m: Manifest[T]): T = {
    val typeFactory = TypeFactory.defaultInstance()
    val javaType: JavaType =
      if (m.runtimeClass == classOf[List[_]] && m.typeArguments.length == 1) {
        // Para List[SpecificType], vamos usar a abordagem de desserializar para java.util.List e converter
        // (se T é List[SpecificType], m.typeArguments.head.runtimeClass é SpecificType.class)
        typeFactory.constructCollectionType(
          classOf[java.util.List[_]],
          m.typeArguments.head.runtimeClass
        )
      } else if (m.runtimeClass == classOf[Seq[_]] && m.typeArguments.length == 1) {
        // Similar para Seq[SpecificType]
        typeFactory.constructCollectionType(
          classOf[java.util.List[_]],
          m.typeArguments.head.runtimeClass
        )
      } else if (m.runtimeClass.isArray && m.typeArguments.nonEmpty) {
        typeFactory.constructArrayType(m.typeArguments.head.runtimeClass)
      } else {
        typeFactory.constructType(m.runtimeClass)
      }

    val result = mapper.readValue(jsonStream, javaType)

    // Se o tipo T original era um Scala List/Seq e desserializamos para java.util.List, convertemos agora.
    if (
      (m.runtimeClass == classOf[List[_]] || m.runtimeClass == classOf[
        Seq[_]
      ]) && m.typeArguments.length == 1 && result.isInstanceOf[java.util.List[_]]
    ) {
      result
        .asInstanceOf[java.util.List[Any]]
        .asScala
        .toList
        .asInstanceOf[T] // Cuidado com 'Any' aqui, idealmente o tipo do elemento seria usado
    } else {
      result.asInstanceOf[T]
    }
  }
}
