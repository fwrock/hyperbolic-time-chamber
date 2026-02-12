package org.interscity.htc
package system.broker.kafka.abstraction

import system.broker.kafka.abstraction.avro._

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import scala.reflect.ClassTag
import scala.util.{Try, Success, Failure}

/**
 * Serialization provider for Kafka messages
 * Uses Jackson for JSON serialization with Scala support
 */
trait KafkaSerializer[T] {
  def serialize(data: T): Array[Byte]
  def deserialize(data: Array[Byte]): Try[T]
}

/**
 * Jackson-based JSON serializer for Kafka messages
 */
class JacksonKafkaSerializer[T: ClassTag] extends KafkaSerializer[T] {
  
  private val mapper: ObjectMapper = new ObjectMapper()
    .registerModule(DefaultScalaModule)
    .registerModule(new JavaTimeModule())
  
  private val classTag = implicitly[ClassTag[T]]
  
  override def serialize(data: T): Array[Byte] = {
    try {
      mapper.writeValueAsBytes(data)
    } catch {
      case ex: Exception =>
        throw new KafkaSerializationException(s"Failed to serialize object of type ${classTag.runtimeClass.getName}", ex)
    }
  }
  
  override def deserialize(data: Array[Byte]): Try[T] = {
    Try {
      mapper.readValue(data, classTag.runtimeClass.asInstanceOf[Class[T]])
    }.recoverWith {
      case ex => Failure(new KafkaDeserializationException(s"Failed to deserialize to ${classTag.runtimeClass.getName}", ex))
    }
  }
}

/**
 * String-based serializer for simple messages
 */
class StringKafkaSerializer extends KafkaSerializer[String] {
  override def serialize(data: String): Array[Byte] = data.getBytes("UTF-8")
  override def deserialize(data: Array[Byte]): Try[String] = Success(new String(data, "UTF-8"))
}

/**
 * Binary pass-through serializer
 */
class BinaryKafkaSerializer extends KafkaSerializer[Array[Byte]] {
  override def serialize(data: Array[Byte]): Array[Byte] = data
  override def deserialize(data: Array[Byte]): Try[Array[Byte]] = Success(data)
}

/**
 * Factory for creating serializers
 */
object KafkaSerializerFactory {
  
  def jackson[T: ClassTag]: KafkaSerializer[T] = new JacksonKafkaSerializer[T]
  
  def string: KafkaSerializer[String] = new StringKafkaSerializer
  
  def binary: KafkaSerializer[Array[Byte]] = new BinaryKafkaSerializer
  
  def envelope[T: ClassTag]: KafkaSerializer[KafkaMessageEnvelope[T]] = 
    new JacksonKafkaSerializer[KafkaMessageEnvelope[T]]
  
  // *** AVRO SERIALIZERS - MUCH FASTER THAN JSON! ***
  
  /**
   * Create Avro serializer for VehiclePosition (3-5x faster than JSON)
   */
  def avroVehiclePosition: KafkaSerializer[HTCAvroConverters.VehiclePosition] = {
    val schema = AvroSchemaUtils.createVehiclePositionSchema()
    new GenericAvroSerializer[HTCAvroConverters.VehiclePosition](
      schema,
      HTCAvroConverters.vehiclePositionToRecord(schema),
      HTCAvroConverters.recordToVehiclePosition
    )
  }
  
  /**
   * Create Avro serializer for TrafficReport (3-5x faster than JSON)
   */
  def avroTrafficReport: KafkaSerializer[HTCAvroConverters.TrafficReport] = {
    val schema = AvroSchemaUtils.createTrafficReportSchema()
    new GenericAvroSerializer[HTCAvroConverters.TrafficReport](
      schema,
      HTCAvroConverters.trafficReportToRecord(schema),
      HTCAvroConverters.recordToTrafficReport
    )
  }
  
  /**
   * Create Avro serializer for SimulationEvent (3-5x faster than JSON)
   */
  def avroSimulationEvent: KafkaSerializer[HTCAvroConverters.SimulationEvent] = {
    val schema = AvroSchemaUtils.createSimulationEventSchema()
    new GenericAvroSerializer[HTCAvroConverters.SimulationEvent](
      schema,
      HTCAvroConverters.simulationEventToRecord(schema),
      HTCAvroConverters.recordToSimulationEvent
    )
  }
  
  /**
   * Create Avro serializer for HybridMicroUpdate (3-5x faster than JSON)
   */
  def avroHybridMicroUpdate: KafkaSerializer[HTCAvroConverters.HybridMicroUpdate] = {
    val schema = AvroSchemaUtils.createHybridMicroUpdateSchema()
    new GenericAvroSerializer[HTCAvroConverters.HybridMicroUpdate](
      schema,
      HTCAvroConverters.hybridMicroUpdateToRecord(schema),
      HTCAvroConverters.recordToHybridMicroUpdate
    )
  }
  
  /**
   * Create generic Avro serializer for custom types
   */
  def avroGeneric[T: ClassTag](
    schema: org.apache.avro.Schema,
    toRecord: T => org.apache.avro.generic.GenericRecord,
    fromRecord: org.apache.avro.generic.GenericRecord => T
  ): KafkaSerializer[T] = {
    new GenericAvroSerializer[T](schema, toRecord, fromRecord)
  }
}

/**
 * Custom exceptions for serialization errors
 */
class KafkaSerializationException(message: String, cause: Throwable = null) 
  extends Exception(message, cause)

class KafkaDeserializationException(message: String, cause: Throwable = null) 
  extends Exception(message, cause)