package org.interscity.htc
package system.broker.kafka.abstraction.avro

import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.generic.{GenericData, GenericRecord, GenericDatumReader, GenericDatumWriter}
import org.apache.avro.io.{DecoderFactory, EncoderFactory, BinaryEncoder, BinaryDecoder}
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter, SpecificRecord}

import system.broker.kafka.abstraction._

import java.io.{ByteArrayOutputStream, ByteArrayInputStream}
import scala.reflect.ClassTag
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._

/**
 * Apache Avro serializer implementation for Kafka messages
 * Provides faster serialization compared to JSON, especially for structured data
 * 
 * Performance benefits:
 * - ~3-5x faster serialization/deserialization than JSON
 * - ~30-50% smaller payload size
 * - Schema evolution support
 * - Type safety at compile time
 */
trait AvroKafkaSerializer[T] extends KafkaSerializer[T] {
  def getSchema: Schema
}

/**
 * Generic Avro serializer for any case class that can be converted to GenericRecord
 * Automatically generates schema from case class structure
 */
class GenericAvroSerializer[T: ClassTag](
  schema: Schema,
  toRecord: T => GenericRecord,
  fromRecord: GenericRecord => T
) extends AvroKafkaSerializer[T] {
  
  private val datumWriter = new GenericDatumWriter[GenericRecord](schema)
  private val datumReader = new GenericDatumReader[GenericRecord](schema)
  
  override def getSchema: Schema = schema
  
  override def serialize(data: T): Array[Byte] = {
    try {
      val record = toRecord(data)
      val out = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().binaryEncoder(out, null)
      datumWriter.write(record, encoder)
      encoder.flush()
      out.close()
      out.toByteArray
    } catch {
      case ex: Exception =>
        throw new KafkaSerializationException(s"Failed to serialize with Avro: ${ex.getMessage}", ex)
    }
  }
  
  override def deserialize(data: Array[Byte]): Try[T] = {
    Try {
      val in = new ByteArrayInputStream(data)
      val decoder = DecoderFactory.get().binaryDecoder(in, null)
      val record = datumReader.read(null, decoder)
      fromRecord(record)
    }.recoverWith {
      case ex => Failure(new KafkaDeserializationException(s"Failed to deserialize with Avro: ${ex.getMessage}", ex))
    }
  }
}

/**
 * Specific Avro serializer for classes that implement SpecificRecord
 * Best performance option when using generated Avro classes
 */
class SpecificAvroSerializer[T <: SpecificRecord: ClassTag] extends AvroKafkaSerializer[T] {
  
  private val classTag = implicitly[ClassTag[T]]
  private val clazz = classTag.runtimeClass.asInstanceOf[Class[T]]
  
  // Get schema from the SpecificRecord class
  override def getSchema: Schema = {
    val schemaField = clazz.getField("SCHEMA$")
    schemaField.get(null).asInstanceOf[Schema]
  }
  
  private val datumWriter = new SpecificDatumWriter[T](clazz)
  private val datumReader = new SpecificDatumReader[T](clazz)
  
  override def serialize(data: T): Array[Byte] = {
    try {
      val out = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().binaryEncoder(out, null)
      datumWriter.write(data, encoder)
      encoder.flush()
      out.close()
      out.toByteArray
    } catch {
      case ex: Exception =>
        throw new KafkaSerializationException(s"Failed to serialize SpecificRecord: ${ex.getMessage}", ex)
    }
  }
  
  override def deserialize(data: Array[Byte]): Try[T] = {
    Try {
      val in = new ByteArrayInputStream(data)
      val decoder = DecoderFactory.get().binaryDecoder(in, null)
      datumReader.read(null.asInstanceOf[T], decoder)
    }.recoverWith {
      case ex => Failure(new KafkaDeserializationException(s"Failed to deserialize SpecificRecord: ${ex.getMessage}", ex))
    }
  }
}

/**
 * Utility object for creating Avro schemas from case classes
 */
object AvroSchemaUtils {
  
  /**
   * Create a simple schema for common HTC data types
   */
  def createVehiclePositionSchema(): Schema = {
    SchemaBuilder.record("VehiclePosition")
      .namespace("org.interscity.htc.avro")
      .fields()
      .requiredString("vehicleId")
      .requiredDouble("latitude")
      .requiredDouble("longitude")
      .requiredDouble("speed")
      .requiredLong("timestamp")
      .optionalString("linkId")
      .optionalString("simulationId")
      .endRecord()
  }
  
  def createTrafficReportSchema(): Schema = {
    SchemaBuilder.record("TrafficReport")
      .namespace("org.interscity.htc.avro")
      .fields()
      .requiredString("linkId")
      .requiredDouble("congestionLevel")
      .requiredDouble("avgSpeed")
      .requiredInt("vehicleCount")
      .requiredLong("timestamp")
      .requiredString("simulationId")
      .optionalDouble("density")
      .optionalDouble("flow")
      .endRecord()
  }
  
  def createSimulationEventSchema(): Schema = {
    SchemaBuilder.record("SimulationEvent")
      .namespace("org.interscity.htc.avro")
      .fields()
      .requiredString("eventType")
      .requiredString("actorId")
      .requiredString("actorType")
      .requiredLong("tick")
      .requiredString("simulationId")
      .requiredString("nodeId")
      .optionalString("data")
      .endRecord()
  }
  
  def createHybridMicroUpdateSchema(): Schema = {
    SchemaBuilder.record("HybridMicroUpdate")
      .namespace("org.interscity.htc.avro")
      .fields()
      .requiredString("vehicleId")
      .requiredString("linkId")
      .requiredDouble("position")
      .requiredDouble("velocity")
      .requiredDouble("acceleration")
      .requiredInt("currentLane")
      .requiredLong("subTick")
      .requiredLong("globalTick")
      .optionalString("leaderId")
      .optionalDouble("gapToLeader")
      .endRecord()
  }
}

/**
 * Converter functions for common HTC types to/from GenericRecord
 */
object HTCAvroConverters {
  
  import java.time.Instant
  
  case class VehiclePosition(vehicleId: String, latitude: Double, longitude: Double, speed: Double, timestamp: Long, linkId: Option[String] = None, simulationId: Option[String] = None)
  case class TrafficReport(linkId: String, congestionLevel: Double, avgSpeed: Double, vehicleCount: Int, timestamp: Long, simulationId: String, density: Option[Double] = None, flow: Option[Double] = None)
  case class SimulationEvent(eventType: String, actorId: String, actorType: String, tick: Long, simulationId: String, nodeId: String, data: Option[String] = None)
  case class HybridMicroUpdate(vehicleId: String, linkId: String, position: Double, velocity: Double, acceleration: Double, currentLane: Int, subTick: Long, globalTick: Long, leaderId: Option[String] = None, gapToLeader: Option[Double] = None)
  
  // VehiclePosition converters
  def vehiclePositionToRecord(schema: Schema)(pos: VehiclePosition): GenericRecord = {
    val record = new GenericData.Record(schema)
    record.put("vehicleId", pos.vehicleId)
    record.put("latitude", pos.latitude)
    record.put("longitude", pos.longitude)
    record.put("speed", pos.speed)
    record.put("timestamp", pos.timestamp)
    record.put("linkId", pos.linkId.orNull)
    record.put("simulationId", pos.simulationId.orNull)
    record
  }
  
  def recordToVehiclePosition(record: GenericRecord): VehiclePosition = {
    VehiclePosition(
      vehicleId = record.get("vehicleId").toString,
      latitude = record.get("latitude").asInstanceOf[Double],
      longitude = record.get("longitude").asInstanceOf[Double],
      speed = record.get("speed").asInstanceOf[Double],
      timestamp = record.get("timestamp").asInstanceOf[Long],
      linkId = Option(record.get("linkId")).map(_.toString),
      simulationId = Option(record.get("simulationId")).map(_.toString)
    )
  }
  
  // TrafficReport converters
  def trafficReportToRecord(schema: Schema)(report: TrafficReport): GenericRecord = {
    val record = new GenericData.Record(schema)
    record.put("linkId", report.linkId)
    record.put("congestionLevel", report.congestionLevel)
    record.put("avgSpeed", report.avgSpeed)
    record.put("vehicleCount", report.vehicleCount)
    record.put("timestamp", report.timestamp)
    record.put("simulationId", report.simulationId)
    record.put("density", report.density.map(Double.box).orNull)
    record.put("flow", report.flow.map(Double.box).orNull)
    record
  }
  
  def recordToTrafficReport(record: GenericRecord): TrafficReport = {
    TrafficReport(
      linkId = record.get("linkId").toString,
      congestionLevel = record.get("congestionLevel").asInstanceOf[Double],
      avgSpeed = record.get("avgSpeed").asInstanceOf[Double],
      vehicleCount = record.get("vehicleCount").asInstanceOf[Int],
      timestamp = record.get("timestamp").asInstanceOf[Long],
      simulationId = record.get("simulationId").toString,
      density = Option(record.get("density")).map(_.asInstanceOf[Double]),
      flow = Option(record.get("flow")).map(_.asInstanceOf[Double])
    )
  }
  
  // SimulationEvent converters
  def simulationEventToRecord(schema: Schema)(event: SimulationEvent): GenericRecord = {
    val record = new GenericData.Record(schema)
    record.put("eventType", event.eventType)
    record.put("actorId", event.actorId)
    record.put("actorType", event.actorType)
    record.put("tick", event.tick)
    record.put("simulationId", event.simulationId)
    record.put("nodeId", event.nodeId)
    record.put("data", event.data.orNull)
    record
  }
  
  def recordToSimulationEvent(record: GenericRecord): SimulationEvent = {
    SimulationEvent(
      eventType = record.get("eventType").toString,
      actorId = record.get("actorId").toString,
      actorType = record.get("actorType").toString,
      tick = record.get("tick").asInstanceOf[Long],
      simulationId = record.get("simulationId").toString,
      nodeId = record.get("nodeId").toString,
      data = Option(record.get("data")).map(_.toString)
    )
  }
  
  // HybridMicroUpdate converters
  def hybridMicroUpdateToRecord(schema: Schema)(update: HybridMicroUpdate): GenericRecord = {
    val record = new GenericData.Record(schema)
    record.put("vehicleId", update.vehicleId)
    record.put("linkId", update.linkId)
    record.put("position", update.position)
    record.put("velocity", update.velocity)
    record.put("acceleration", update.acceleration)
    record.put("currentLane", update.currentLane)
    record.put("subTick", update.subTick)
    record.put("globalTick", update.globalTick)
    record.put("leaderId", update.leaderId.orNull)
    record.put("gapToLeader", update.gapToLeader.map(Double.box).orNull)
    record
  }
  
  def recordToHybridMicroUpdate(record: GenericRecord): HybridMicroUpdate = {
    HybridMicroUpdate(
      vehicleId = record.get("vehicleId").toString,
      linkId = record.get("linkId").toString,
      position = record.get("position").asInstanceOf[Double],
      velocity = record.get("velocity").asInstanceOf[Double],
      acceleration = record.get("acceleration").asInstanceOf[Double],
      currentLane = record.get("currentLane").asInstanceOf[Int],
      subTick = record.get("subTick").asInstanceOf[Long],
      globalTick = record.get("globalTick").asInstanceOf[Long],
      leaderId = Option(record.get("leaderId")).map(_.toString),
      gapToLeader = Option(record.get("gapToLeader")).map(_.asInstanceOf[Double])
    )
  }
}