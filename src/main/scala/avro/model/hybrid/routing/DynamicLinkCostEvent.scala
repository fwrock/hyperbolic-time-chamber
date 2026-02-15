package org.interscity.htc.avro.model.hybrid.routing

/**
 * Manual implementation of DynamicLinkCostEvent to avoid sbt-avro plugin issues.
 * 
 * This replaces the generated Avro class until plugin dependencies are resolved.
 * Provides the same functionality for Kafka serialization.
 */
case class DynamicLinkCostEvent(
  linkId: String,
  staticCost: Double,
  congestionFactor: Double = 1.0,
  incidentPenalty: Double = 0.0,
  currentSpeed: Double,
  freeFlowSpeed: Double,
  vehicleCount: Int,
  capacity: Double,
  totalCost: Double,
  timestamp: Long,
  tick: Long,
  nodeId: Option[String] = None,
  simulationMode: SimulationMode = SimulationMode.MESO
)

/**
 * Simulation mode enum
 */
sealed trait SimulationMode {
  def name: String
}

object SimulationMode {
  case object MESO extends SimulationMode { val name = "MESO" }
  case object MICRO extends SimulationMode { val name = "MICRO" }
  
  def fromString(mode: String): SimulationMode = mode.toUpperCase match {
    case "MESO" => MESO
    case "MICRO" => MICRO
    case _ => MESO // Default
  }
}

object DynamicLinkCostEvent {
  
  /**
   * Builder pattern for compatibility with generated Avro classes
   */
  class Builder {
    private var linkId: String = ""
    private var staticCost: Double = 0.0
    private var congestionFactor: Double = 1.0
    private var incidentPenalty: Double = 0.0
    private var currentSpeed: Double = 0.0
    private var freeFlowSpeed: Double = 0.0
    private var vehicleCount: Int = 0
    private var capacity: Double = 0.0
    private var totalCost: Double = 0.0
    private var timestamp: Long = 0L
    private var tick: Long = 0L
    private var nodeId: Option[String] = None
    private var simulationMode: SimulationMode = SimulationMode.MESO
    
    def setLinkId(value: String): Builder = { linkId = value; this }
    def setStaticCost(value: Double): Builder = { staticCost = value; this }
    def setCongestionFactor(value: Double): Builder = { congestionFactor = value; this }
    def setIncidentPenalty(value: Double): Builder = { incidentPenalty = value; this }
    def setCurrentSpeed(value: Double): Builder = { currentSpeed = value; this }
    def setFreeFlowSpeed(value: Double): Builder = { freeFlowSpeed = value; this }
    def setVehicleCount(value: Int): Builder = { vehicleCount = value; this }
    def setCapacity(value: Double): Builder = { capacity = value; this }
    def setTotalCost(value: Double): Builder = { totalCost = value; this }
    def setTimestamp(value: Long): Builder = { timestamp = value; this }
    def setTick(value: Long): Builder = { tick = value; this }
    def setNodeId(value: String): Builder = { nodeId = Option(value); this }
    def setSimulationMode(value: SimulationMode): Builder = { simulationMode = value; this }
    
    def build(): DynamicLinkCostEvent = DynamicLinkCostEvent(
      linkId = linkId,
      staticCost = staticCost,
      congestionFactor = congestionFactor,
      incidentPenalty = incidentPenalty,
      currentSpeed = currentSpeed,
      freeFlowSpeed = freeFlowSpeed,
      vehicleCount = vehicleCount,
      capacity = capacity,
      totalCost = totalCost,
      timestamp = timestamp,
      tick = tick,
      nodeId = nodeId,
      simulationMode = simulationMode
    )
  }
  
  def newBuilder(): Builder = new Builder()
  
  /**
   * Mock schema for compatibility
   */
  def getClassSchema: String = {
    """
    {
      "type": "record",
      "name": "DynamicLinkCostEvent", 
      "namespace": "org.interscity.htc.avro.model.hybrid.routing",
      "fields": [
        {"name": "linkId", "type": "string"},
        {"name": "staticCost", "type": "double"},
        {"name": "congestionFactor", "type": "double", "default": 1.0},
        {"name": "incidentPenalty", "type": "double", "default": 0.0},
        {"name": "currentSpeed", "type": "double"},
        {"name": "freeFlowSpeed", "type": "double"},
        {"name": "vehicleCount", "type": "int"},
        {"name": "capacity", "type": "double"},
        {"name": "totalCost", "type": "double"},
        {"name": "timestamp", "type": "long"},
        {"name": "tick", "type": "long"},
        {"name": "nodeId", "type": ["null", "string"], "default": null},
        {"name": "simulationMode", "type": {"type": "enum", "name": "SimulationMode", "symbols": ["MESO", "MICRO"]}, "default": "MESO"}
      ]
    }
    """
  }
}