package org.interscity.htc.model.hybrid.util.avro

import org.interscity.htc.avro.model.hybrid.routing.{ DynamicLinkCostEvent, SimulationMode }
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.core.util.JsonUtil

import scala.util.{ Failure, Success, Try }

/** JSON-based serialization utilities for dynamic link cost events.
  *
  * Temporarily using JSON instead of binary Avro until plugin issues are resolved. Converts between
  * internal DynamicLinkCost model and DynamicLinkCostEvent for efficient Kafka serialization.
  */
object DynamicLinkCostAvro {

  /** Convert internal model to event.
    */
  def toAvro(cost: DynamicLinkCost): DynamicLinkCostEvent =
    DynamicLinkCostEvent
      .newBuilder()
      .setLinkId(cost.linkId)
      .setStaticCost(cost.baseCost)
      .setCongestionFactor(cost.congestionFactor)
      .setIncidentPenalty(cost.incidentFactor)
      .setCurrentSpeed(cost.currentSpeed)
      .setFreeFlowSpeed(cost.freeFlowSpeed)
      .setVehicleCount(cost.vehicleCount)
      .setCapacity(cost.capacity)
      .setTotalCost(cost.totalCost)
      .setTimestamp(cost.timestamp)
      .setTick(cost.lastUpdateTick)
      .setNodeId(
        try java.net.InetAddress.getLocalHost.getHostName
        catch { case _ => "unknown" }
      )
      .setSimulationMode(SimulationMode.MESO) // Default for now
      .build()

  /** Convert event to internal model.
    */
  def fromAvro(event: DynamicLinkCostEvent): DynamicLinkCost =
    DynamicLinkCost(
      linkId = event.linkId,
      baseCost = event.staticCost,
      congestionFactor = event.congestionFactor,
      currentSpeed = event.currentSpeed,
      freeFlowSpeed = event.freeFlowSpeed,
      vehicleCount = event.vehicleCount,
      capacity = event.capacity,
      incidentFactor = event.incidentPenalty,
      lastUpdateTick = event.tick,
      timestamp = event.timestamp
    )

  /** Serialize to JSON format (temporary replacement for binary Avro).
    */
  def serialize(cost: DynamicLinkCost): Try[Array[Byte]] =
    Try {
      val event = toAvro(cost)
      JsonUtil.toJson(event).getBytes("UTF-8")
    }

  /** Deserialize from JSON format.
    */
  def deserialize(data: Array[Byte]): Try[DynamicLinkCost] =
    Try {
      val json = new String(data, "UTF-8")
      val event = JsonUtil.fromJson[DynamicLinkCostEvent](json)
      fromAvro(event)
    }

  /** Get JSON representation of schema for debugging.
    */
  def getSchemaJson: String = DynamicLinkCostEvent.getClassSchema

  /** Validate that a DynamicLinkCost can be serialized/deserialized correctly.
    */
  def validate(cost: DynamicLinkCost): Try[Unit] =
    for {
      serialized <- serialize(cost)
      deserialized <- deserialize(serialized)
    } yield {
      // Basic validation
      require(deserialized.linkId == cost.linkId, "LinkId mismatch")
      require(math.abs(deserialized.totalCost - cost.totalCost) < 0.001, "TotalCost mismatch")
      require(deserialized.lastUpdateTick == cost.lastUpdateTick, "Tick mismatch")
    }
}

/** Example usage and testing.
  */
object DynamicLinkCostAvroExample {
  def main(args: Array[String]): Unit = {
    println("🧪 Testing Avro serialization for DynamicLinkCost")
    println("=" * 50)

    // Create test data
    val testCost = DynamicLinkCost(
      linkId = "htcaid:link;test_link_123",
      baseCost = 100.0,
      congestionFactor = 2.5,
      currentSpeed = 30.0,
      freeFlowSpeed = 50.0,
      vehicleCount = 25,
      capacity = 100.0,
      incidentFactor = 50.0,
      lastUpdateTick = 12345L,
      timestamp = System.currentTimeMillis()
    )

    println(s"📊 Original cost: ${testCost.linkId} -> ${testCost.totalCost}")

    // Test serialization
    DynamicLinkCostAvro.serialize(testCost) match {
      case Success(bytes) =>
        println(s"✅ Serialized to ${bytes.length} bytes")

        // Test deserialization
        DynamicLinkCostAvro.deserialize(bytes) match {
          case Success(deserialized) =>
            println(s"✅ Deserialized: ${deserialized.linkId} -> ${deserialized.totalCost}")

            // Validate round-trip
            DynamicLinkCostAvro.validate(testCost) match {
              case Success(_) =>
                println("✅ Round-trip validation passed")
              case Failure(e) =>
                println(s"❌ Validation failed: ${e.getMessage}")
            }

          case Failure(e) =>
            println(s"❌ Deserialization failed: ${e.getMessage}")
        }

      case Failure(e) =>
        println(s"❌ Serialization failed: ${e.getMessage}")
    }

    // Show schema
    println("\n📋 Avro Schema:")
    println(DynamicLinkCostAvro.getSchemaJson)

    println("\n🎉 Avro testing complete!")
  }
}
