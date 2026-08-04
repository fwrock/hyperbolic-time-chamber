package org.interscity.htc
package core.actor.manager.digitaltwin

import org.interscity.htc.avro.bus.{ EventKind, HtcReadyEvent }

/** Resolved routing instruction for a single HtcReadyEvent. */
case class DigitalTwinRouting(
  targetEntityId: String,
  targetShardId: String,
  eventType: String,
  payload: Map[String, Any]
)

/** Stateless helper that maps an HtcReadyEvent to a DigitalTwinRouting.
  *
  * Receives lambdas for all actor state access so it holds no Pekko references and can be tested
  * without an ActorSystem.
  *
  * @param entityIdFn    returns the entity ID of the owning actor (for log context)
  * @param currentTickFn returns the current simulation tick
  * @param logFn         (level, message) → Unit; level is "INFO", "WARN", or "ERROR"
  */
class DigitalTwinHandler(
  entityIdFn: () => String,
  currentTickFn: () => Long,
  logFn: (String, String) => Unit
) {

  /** Resolve a routing target for `event`, or None if the entity type is not yet handled. */
  def resolveTarget(event: HtcReadyEvent): Option[DigitalTwinRouting] = {
    val eventType = deriveEventType(event)
    val shardId   = inferShardId(event)
    Some(
      DigitalTwinRouting(
        targetEntityId = event.entityId,
        targetShardId  = shardId,
        eventType      = eventType,
        payload        = buildUpdatePayload(event)
      )
    )
  }

  /** Build the Map that will be sent as `data` to the target actor. */
  def buildUpdatePayload(event: HtcReadyEvent): Map[String, Any] = {
    val base: Map[String, Any] = Map(
      "eventId"         -> event.eventId,
      "entityId"        -> event.entityId,
      "entityType"      -> event.entityType.toString,
      "eventKind"       -> event.eventKind.toString,
      "payload"         -> event.payload,
      "sourceTimestamp" -> event.sourceTimestamp,
      "receivedAtTick"  -> currentTickFn()
    )
    val withTick = event.simulationTick.fold(base)(t => base + ("simulationTick" -> t))
    val withModel = event.htcdlModelId.fold(withTick)(m => withTick + ("htcdlModelId" -> m))
    event.anomalyType.fold(withModel)(a => withModel + ("anomalyType" -> a.toString))
  }

  private def deriveEventType(event: HtcReadyEvent): String =
    event.eventKind match {
      case EventKind.ANOMALY      => DigitalTwinHandler.EventType.DtAnomaly
      case EventKind.COMMAND      => DigitalTwinHandler.EventType.DtCommand
      case EventKind.STATE_UPDATE => DigitalTwinHandler.EventType.DtStateUpdate
      case EventKind.TELEMETRY    => DigitalTwinHandler.EventType.DtUpdate
    }

  /** Derive shard ID from the entity ID using the same hash strategy as ActorCreatorUtil so that
    * the routing layer can find the actor without a registry lookup.
    *
    * Falls back to the SpatialShardIdRegistry when the entity has a spatial assignment.
    */
  private def inferShardId(event: HtcReadyEvent): String = {
    import org.interscity.htc.core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
    import org.interscity.htc.core.util.IdUtil

    val formattedId = IdUtil.format(event.entityId)
    SpatialShardIdRegistry
      .getShardId(formattedId)
      .getOrElse((formattedId.hashCode % 1000).toString)
  }
}

object DigitalTwinHandler {

  /** Event-type constants sent to target actors so they can pattern-match without magic strings. */
  object EventType {
    val DtUpdate      = "DT_UPDATE"
    val DtCommand     = "DT_COMMAND"
    val DtAnomaly     = "DT_ANOMALY"
    val DtStateUpdate = "DT_STATE_UPDATE"
  }
}
