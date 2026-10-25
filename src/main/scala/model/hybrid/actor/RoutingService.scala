package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.hybrid.entity.event.data.routing.*
import model.hybrid.entity.state.{ RoutingCostVersion, RoutingServiceState }
import model.hybrid.entity.state.model.PrecomputedRouteItem
import model.hybrid.entity.state.enumeration.EventTypeEnum
import model.hybrid.util.{ AccountedRoutingConfig, DynamicWeightCache, GPSUtil }
import model.hybrid.decision.{ ModeDecisionEngineRegistry, NoViableJourney }

/** Logical process that makes dynamic routing input part of the rollback history. */
class RoutingService(private val properties: Properties)
    extends SimulationBaseActor[RoutingServiceState](properties) {

  override protected def internStateStrings(s: RoutingServiceState): RoutingServiceState = s

  override protected def validateRuntimeCapabilities(): Unit = {
    require(AccountedRoutingConfig.enabled,
      "RoutingService requires htc.routing.cache-strategy=accounted")
    require(AccountedRoutingConfig.workerIds.contains(getEntityId.toLong),
      s"routing worker $getEntityId is not listed in htc.routing.accounted.worker-ids")
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    onFinishSpontaneous(None)

  override def actInteractWith(event: ActorInteractionEvent): Unit = event.data match {
    case data: LinkCostChangedData =>
      require(data.linkId == event.actorRefId,
        s"link-cost source ${event.actorRefId} cannot update link ${data.linkId}")
      require(data.observedTick == event.tick,
        s"link-cost tick ${data.observedTick} does not match event tick ${event.tick}")
      require(data.weight.isFinite && data.weight > 0.0,
        s"invalid link cost ${data.weight} for ${data.linkId}")
      val order = event.order.getOrElse(
        throw new IllegalArgumentException("accounted link-cost event has no EventOrder"))
      state = state.copy(costs = state.costs.updated(data.linkId, RoutingCostVersion(data.weight, order)))

    case query: RouteQueryData =>
      val result = DynamicWeightCache.withAccountedWeights(id => state.costs.get(id).map(_.weight)) {
        GPSUtil.calcRouteCompact(
          query.originNodeId,
          query.destinationNodeId,
          useDynamicWeights = true,
          maxExpansions = query.maxExpansions
        ).map { case (cost, path) =>
          RouteResult(cost, path.iterator.map { case (linkId, nodeId) =>
            PrecomputedRouteItem(linkId, nodeId)
          }.toList)
        }
      }
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.actorClassType,
        data = RouteReplyData(event.seq, result),
        eventType = EventTypeEnum.RouteReply.toString,
        actorType = LoadBalancedDistributed
      )

    case query: ModeDecisionQueryData =>
      require(query.context.currentTick == event.tick,
        s"mode-decision context tick ${query.context.currentTick} does not match ${event.tick}")
      val result = DynamicWeightCache.withAccountedWeights(id => state.costs.get(id).map(_.weight)) {
        ModeDecisionEngineRegistry.get(query.request.strategyId) match {
          case Some(engine) => engine.decide(
            query.originNodeId, query.destinationNodeId, query.request, query.context)
          case None => Left(NoViableJourney(s"unknown strategyId ${query.request.strategyId}"))
        }
      }
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.actorClassType,
        data = ModeDecisionReplyData(event.seq, result),
        eventType = EventTypeEnum.ModeDecisionReply.toString,
        actorType = LoadBalancedDistributed
      )

    case other =>
      logWarn(s"RoutingService ignored ${other.getClass.getSimpleName}")
  }
}
