package org.interscity.htc.core.kafka

import com.typesafe.config.ConfigFactory

/** Kafka topic naming conventions and auto-creation for HTC simulation.
  *
  * Topic naming pattern:
  * ```
  * htc.{domain}.{component}.{version}
  * ```
  *
  * Examples:
  *   - `htc.routing.dynamic-costs.v1` - Dynamic link costs for routing
  *   - `htc.mobility.vehicle-updates.v1` - Vehicle state updates
  *   - `htc.system.actor-lifecycle.v1` - Actor creation/destruction events
  *   - `htc.performance.metrics.v1` - Performance monitoring data
  *
  * Benefits:
  *   - Clear domain separation
  *   - Version management for schema evolution
  *   - Environment isolation (via prefix)
  *   - Searchable/filterable by component
  */
object KafkaTopicNaming {

  private val config = ConfigFactory.load()

  // Environment prefix (dev, staging, prod)
  private val envPrefix: String =
    try
      config.getString("htc.kafka.topic.environment-prefix")
    catch {
      case _: Exception => "dev" // Default to dev
    }

  // Base HTC topic prefix
  private val basePrefix = s"$envPrefix.htc"

  /** Routing domain topics */
  object Routing {

    /** Dynamic link costs for real-time routing */
    val DynamicCosts = s"$basePrefix.routing.dynamic-costs.v1"

    /** Route calculation requests/responses */
    val RouteRequests = s"$basePrefix.routing.requests.v1"
    val RouteResponses = s"$basePrefix.routing.responses.v1"

    /** Traffic incidents and road closures */
    val TrafficIncidents = s"$basePrefix.routing.incidents.v1"
  }

  /** Mobility domain topics */
  object Mobility {

    /** Vehicle state updates (position, speed, etc.) */
    val VehicleUpdates = s"$basePrefix.mobility.vehicle-updates.v1"

    /** Bus/transit specific updates */
    val TransitUpdates = s"$basePrefix.mobility.transit-updates.v1"

    /** Person/pedestrian movement */
    val PersonMovement = s"$basePrefix.mobility.person-movement.v1"
  }

  /** System monitoring and control topics */
  object System {

    /** Actor lifecycle events */
    val ActorLifecycle = s"$basePrefix.system.actor-lifecycle.v1"

    /** Performance metrics */
    val PerformanceMetrics = s"$basePrefix.system.performance-metrics.v1"

    /** Simulation control commands */
    val SimulationControl = s"$basePrefix.system.simulation-control.v1"
  }

  /** Get all defined topics for auto-creation */
  def getAllTopics: Set[String] = Set(
    Routing.DynamicCosts,
    Routing.RouteRequests,
    Routing.RouteResponses,
    Routing.TrafficIncidents,
    Mobility.VehicleUpdates,
    Mobility.TransitUpdates,
    Mobility.PersonMovement,
    System.ActorLifecycle,
    System.PerformanceMetrics,
    System.SimulationControl
  )

  /** Get topic configuration for a specific topic
    */
  case class TopicConfig(
    name: String,
    partitions: Int,
    replicationFactor: Int,
    retentionMs: Long,
    cleanupPolicy: String = "delete"
  )

  def getTopicConfig(topicName: String): TopicConfig =
    topicName match {
      // High-frequency, short retention for routing
      case Routing.DynamicCosts =>
        TopicConfig(
          name = topicName,
          partitions = 12,
          replicationFactor = 1,
          retentionMs = 300000L, // 5 minutes
          cleanupPolicy = "delete"
        )

      // Medium frequency for mobility updates
      case Mobility.VehicleUpdates =>
        TopicConfig(
          name = topicName,
          partitions = 8,
          replicationFactor = 1,
          retentionMs = 3600000L, // 1 hour
          cleanupPolicy = "delete"
        )

      // Low frequency, longer retention for system events
      case System.ActorLifecycle | System.PerformanceMetrics =>
        TopicConfig(
          name = topicName,
          partitions = 4,
          replicationFactor = 1,
          retentionMs = 86400000L, // 24 hours
          cleanupPolicy = "delete"
        )

      // Default configuration
      case _ =>
        TopicConfig(
          name = topicName,
          partitions = 6,
          replicationFactor = 1,
          retentionMs = 3600000L, // 1 hour
          cleanupPolicy = "delete"
        )
    }

  /** Print all topics with their configurations (for debugging)
    */
  def printTopicConfiguration(): Unit = {
    println(s"🏷️  Kafka Topic Configuration (Environment: $envPrefix)")
    println("=" * 60)

    val topics = getAllTopics.toSeq.sorted
    topics.foreach {
      topic =>
        val config = getTopicConfig(topic)
        println(f"📍 ${config.name}")
        println(
          f"   Partitions: ${config.partitions}%2d | Replication: ${config.replicationFactor} | Retention: ${config.retentionMs / 60000}%3d min"
        )
    }

    println(s"\nTotal topics: ${topics.size}")
  }
}
