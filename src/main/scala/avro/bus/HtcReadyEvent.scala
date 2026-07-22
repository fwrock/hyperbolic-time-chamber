package org.interscity.htc.avro.bus

/** Manual implementation of HtcReadyEvent — mirrors the Avro schema in
  * src/main/avro/bus/htc_ready_event.avsc without requiring the sbt-avro plugin.
  */
case class HtcReadyEvent(
  eventId: String,
  entityId: String,
  entityType: EntityType,
  eventKind: EventKind,
  payload: String,
  sourceTimestamp: Long,
  simulationTick: Option[Long] = None,
  htcdlModelId: Option[String] = None,
  anomalyType: Option[AnomalyType] = None
)

sealed trait EntityType
object EntityType {
  case object CAR            extends EntityType
  case object BIKE           extends EntityType
  case object BUS            extends EntityType
  case object SUBWAY         extends EntityType
  case object PERSON         extends EntityType
  case object TRAFFIC_SIGNAL extends EntityType
  case object LINK           extends EntityType
  case object NODE           extends EntityType

  def fromString(s: String): EntityType = s.toUpperCase match {
    case "CAR"            => CAR
    case "BIKE"           => BIKE
    case "BUS"            => BUS
    case "SUBWAY"         => SUBWAY
    case "PERSON"         => PERSON
    case "TRAFFIC_SIGNAL" => TRAFFIC_SIGNAL
    case "LINK"           => LINK
    case "NODE"           => NODE
    case other            => throw new IllegalArgumentException(s"Unknown EntityType: $other")
  }
}

sealed trait EventKind
object EventKind {
  case object TELEMETRY    extends EventKind
  case object COMMAND      extends EventKind
  case object ANOMALY      extends EventKind
  case object STATE_UPDATE extends EventKind

  def fromString(s: String): EventKind = s.toUpperCase match {
    case "TELEMETRY"    => TELEMETRY
    case "COMMAND"      => COMMAND
    case "ANOMALY"      => ANOMALY
    case "STATE_UPDATE" => STATE_UPDATE
    case other          => throw new IllegalArgumentException(s"Unknown EventKind: $other")
  }
}

sealed trait AnomalyType
object AnomalyType {
  case object ACCIDENT       extends AnomalyType
  case object SIGNAL_CHANGE  extends AnomalyType
  case object ROAD_CLOSURE   extends AnomalyType
  case object SENSOR_FAILURE extends AnomalyType

  def fromString(s: String): AnomalyType = s.toUpperCase match {
    case "ACCIDENT"       => ACCIDENT
    case "SIGNAL_CHANGE"  => SIGNAL_CHANGE
    case "ROAD_CLOSURE"   => ROAD_CLOSURE
    case "SENSOR_FAILURE" => SENSOR_FAILURE
    case other            => throw new IllegalArgumentException(s"Unknown AnomalyType: $other")
  }
}
