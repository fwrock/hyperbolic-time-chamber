package org.interscity.htc
package model.hybrid.support

import core.actor.manager.load.ActorLoadTransform
import core.entity.actor.ActorSimulation
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.Config
import scala.jdk.CollectionConverters.*
import scala.util.Try

object MobilityLoadTransform {
  enum TransitTripPolicy { case Reject, Walk }
  final case class Settings(transitTripPolicy: TransitTripPolicy)
}

/** Optional mobility policy; the generic loader has no knowledge of plans or transport modes. */
class MobilityLoadTransform(config: Config) extends ActorLoadTransform {
  import MobilityLoadTransform.*
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val settings = Settings(Try(config.getString("htc.mobility.load-policy.transit-trip-policy"))
    .getOrElse("reject") match {
      case "reject" => TransitTripPolicy.Reject
      case "walk" => TransitTripPolicy.Walk
      case other => throw new IllegalArgumentException(s"unsupported mobility transit-trip-policy: $other")
    })

  override def transform(actor: ActorSimulation): ActorSimulation =
    if (actor.typeActor.split('.').last == "Person") restrictPerson(actor, settings) else actor

  private def restrictPerson(actor: ActorSimulation, settings: Settings): ActorSimulation = {
    val content = mapper.valueToTree[JsonNode](actor.data.content)
    val plan = Option(content.get("originalPlan")).filter(_.isArray).map(_.elements().asScala.toVector)
    if (plan.isEmpty) return actor
    val elements = plan.get
    def kind(node: JsonNode): String = node.path("kind").asText()
    def transitMode(mode: JsonNode): Boolean = Set("bus", "subway").contains(mode.asText().toLowerCase)
    def needsTransit(node: JsonNode): Boolean = kind(node) match {
      case "TransitLeg" => transitMode(node.path("mode"))
      case "PendingDecision" => node.path("decision").path("allowedModes").elements().asScala.exists(transitMode)
      case _ => false
    }
    if (!elements.exists(needsTransit)) return actor
    require(settings.transitTripPolicy == TransitTripPolicy.Walk,
      s"Person ${actor.id} has a transit-dependent trip while public transport is disabled; " +
        "set htc.mobility.load-policy.transit-trip-policy=walk to replace that trip explicitly")

    val rewritten = mapper.createArrayNode()
    var previousActivity: Option[JsonNode] = None
    var index = 0
    while (index < elements.size) {
      val element = elements(index)
      if (kind(element) == "Activity") {
        previousActivity = Some(element)
        rewritten.add(element)
        index += 1
      } else {
        val end = elements.indexWhere(node => kind(node) == "Activity", index) match {
          case -1 => elements.size
          case next => next
        }
        val trip = elements.slice(index, end)
        if (trip.exists(needsTransit)) {
          require(previousActivity.nonEmpty && end < elements.size,
            s"Person ${actor.id}: transit fallback needs activities at both ends of the trip")
          require(!trip.exists(node => kind(node) == "PrivateVehicleLeg"),
            s"Person ${actor.id}: cannot replace a mixed private-vehicle/transit trip with walking")
          val origin = previousActivity.get.path("nodeId")
          val destination = elements(end).path("nodeId")
          require(origin.isIntegralNumber && destination.isIntegralNumber,
            s"Person ${actor.id}: transit fallback needs physical activity node IDs")
          rewritten.add(mapper.createObjectNode().put("kind", "WalkLeg")
            .put("originNodeId", origin.asLong()).put("destinationNodeId", destination.asLong()))
        } else trip.foreach(node => rewritten.add(node))
        index = end
      }
    }
    val updated = content.asInstanceOf[ObjectNode]
    updated.set[JsonNode]("originalPlan", rewritten)
    actor.copy(data = actor.data.copy(content = mapper.treeToValue(updated, classOf[Object])))
  }
}
