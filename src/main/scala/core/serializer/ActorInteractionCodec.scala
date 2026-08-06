package org.interscity.htc
package core.serializer

import core.enumeration.CreationTypeEnum

import org.htc.protobuf.core.entity.event.communication.{ ActorClassType, ActorCreationType, ActorEventType }

/** Maps the free-form `eventType`/`actorClassType`/`actorType` strings carried by
  * [[org.interscity.htc.core.entity.event.ActorInteractionEvent]] to/from the closed-set protobuf
  * enums added to `ActorInteraction` (see `communication.proto`). Scala-side call sites
  * (`sendMessageTo`, actor constructors, etc.) are untouched — they keep passing plain strings;
  * only the two wire-boundary serializers ([[ActorInteractionSerializer]],
  * [[EntityEnvelopeSerializer]]) go through this codec, so the enum sets can be extended here
  * without touching call sites elsewhere.
  *
  * Every known value below was found by grepping every `sendMessageTo`/`ActorInteractionEvent`
  * construction site in the codebase (docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 2) — not
  * guessed. A string outside these maps still round-trips correctly via the `*_OTHER` enum case
  * plus the corresponding `*Override` string field; it just doesn't get the wire-size win until
  * the map below is extended for it.
  */
private[serializer] object ActorInteractionCodec {

  // ---- eventType ----------------------------------------------------------------------------

  private val eventTypeToProto: Map[String, ActorEventType] = Map(
    "default" -> ActorEventType.EVENT_TYPE_DEFAULT,
    "enter" -> ActorEventType.EVENT_TYPE_ENTER,
    "leave" -> ActorEventType.EVENT_TYPE_LEAVE,
    "LineNotOperational" -> ActorEventType.EVENT_TYPE_LINE_NOT_OPERATIONAL,
    "PTLineNotOperational" -> ActorEventType.EVENT_TYPE_PT_LINE_NOT_OPERATIONAL,
    "TripCompleted" -> ActorEventType.EVENT_TYPE_TRIP_COMPLETED,
    "ReceiveEnterLinkInfo" -> ActorEventType.EVENT_TYPE_RECEIVE_ENTER_LINK_INFO,
    "ReceiveLeaveLinkInfo" -> ActorEventType.EVENT_TYPE_RECEIVE_LEAVE_LINK_INFO,
    "RegisterLinkCapacity" -> ActorEventType.EVENT_TYPE_REGISTER_LINK_CAPACITY,
    "DT_UPDATE" -> ActorEventType.EVENT_TYPE_DT_UPDATE,
    "DT_COMMAND" -> ActorEventType.EVENT_TYPE_DT_COMMAND,
    "DT_ANOMALY" -> ActorEventType.EVENT_TYPE_DT_ANOMALY,
    "DT_STATE_UPDATE" -> ActorEventType.EVENT_TYPE_DT_STATE_UPDATE
  )
  private val eventTypeFromProto: Map[ActorEventType, String] = eventTypeToProto.map(_.swap)

  /** Returns the enum tag to write, plus the override string (empty unless the enum is OTHER). */
  def encodeEventType(value: String): (ActorEventType, String) =
    eventTypeToProto.get(value) match {
      case Some(proto) => (proto, "")
      case None         => (ActorEventType.EVENT_TYPE_OTHER, value)
    }

  def decodeEventType(proto: ActorEventType, overrideValue: String): String =
    if (proto == ActorEventType.EVENT_TYPE_OTHER) overrideValue
    else eventTypeFromProto.getOrElse(proto, overrideValue)

  // ---- actorClassType ------------------------------------------------------------------------

  private val actorClassTypeToProto: Map[String, ActorClassType] = Map(
    "Bicycle" -> ActorClassType.ACTOR_CLASS_BICYCLE,
    "Bus" -> ActorClassType.ACTOR_CLASS_BUS,
    "BusStation" -> ActorClassType.ACTOR_CLASS_BUS_STATION,
    "BusStop" -> ActorClassType.ACTOR_CLASS_BUS_STOP,
    "Car" -> ActorClassType.ACTOR_CLASS_CAR,
    "Link" -> ActorClassType.ACTOR_CLASS_LINK,
    "RailLink" -> ActorClassType.ACTOR_CLASS_RAIL_LINK,
    "Motorcycle" -> ActorClassType.ACTOR_CLASS_MOTORCYCLE,
    "Node" -> ActorClassType.ACTOR_CLASS_NODE,
    "Person" -> ActorClassType.ACTOR_CLASS_PERSON,
    "Subway" -> ActorClassType.ACTOR_CLASS_SUBWAY,
    "SubwayStation" -> ActorClassType.ACTOR_CLASS_SUBWAY_STATION,
    "TrafficSignal" -> ActorClassType.ACTOR_CLASS_TRAFFIC_SIGNAL,
    "DigitalTwinManager" -> ActorClassType.ACTOR_CLASS_DIGITAL_TWIN_MANAGER
  )
  private val actorClassTypeFromProto: Map[ActorClassType, String] = actorClassTypeToProto.map(_.swap)

  def encodeActorClassType(value: String): (ActorClassType, String) =
    actorClassTypeToProto.get(value) match {
      case Some(proto) => (proto, "")
      case None         => (ActorClassType.ACTOR_CLASS_OTHER, value)
    }

  def decodeActorClassType(proto: ActorClassType, overrideValue: String): String =
    if (proto == ActorClassType.ACTOR_CLASS_OTHER) overrideValue
    else actorClassTypeFromProto.getOrElse(proto, overrideValue)

  // ---- actorType (CreationTypeEnum) -----------------------------------------------------------

  private val creationTypeToProto: Map[String, ActorCreationType] = Map(
    CreationTypeEnum.Simple.toString -> ActorCreationType.CREATION_TYPE_SIMPLE,
    CreationTypeEnum.SingletonDistributed.toString -> ActorCreationType.CREATION_TYPE_SINGLETON_DISTRIBUTED,
    CreationTypeEnum.PoolDistributed.toString -> ActorCreationType.CREATION_TYPE_POOL_DISTRIBUTED,
    CreationTypeEnum.LoadBalancedDistributed.toString -> ActorCreationType.CREATION_TYPE_LOAD_BALANCED_DISTRIBUTED
  )
  private val creationTypeFromProto: Map[ActorCreationType, String] = creationTypeToProto.map(_.swap)

  // CreationTypeEnum is a genuinely closed Scala enum (every ActorInteractionEvent.actorType is
  // built from `CreationTypeEnum.X.toString`, never a free-form string) — no OTHER/fallback case
  // needed; an unmapped value here would indicate a bug (a new CreationTypeEnum case added
  // without updating ActorCreationType), so it defaults to the same value CreationTypeEnum itself
  // defaults to rather than silently drop information via a fallback string.
  def encodeCreationType(value: String): ActorCreationType =
    creationTypeToProto.getOrElse(value, ActorCreationType.CREATION_TYPE_LOAD_BALANCED_DISTRIBUTED)

  def decodeCreationType(proto: ActorCreationType): String =
    creationTypeFromProto.getOrElse(proto, CreationTypeEnum.LoadBalancedDistributed.toString)
}
