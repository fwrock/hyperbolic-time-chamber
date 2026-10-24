package org.interscity.htc
package model.hybrid.entity.event.data.routing

import core.entity.event.data.BaseEventData
import model.hybrid.entity.state.model.PrecomputedRouteItem
import model.hybrid.entity.state.plan.{ AtomicLeg, ModeDecisionRequest }
import model.hybrid.decision.{ DecisionContext, NoViableJourney }

final case class LinkCostChangedData(linkId: Long, weight: Double, observedTick: Long)
    extends BaseEventData

final case class RouteQueryData(originNodeId: Long, destinationNodeId: Long, maxExpansions: Int)
    extends BaseEventData

final case class RouteResult(cost: Double, path: List[PrecomputedRouteItem])

final case class RouteReplyData(requestSeq: Long, result: Option[RouteResult])
    extends BaseEventData

final case class ModeDecisionQueryData(
  originNodeId: Long,
  destinationNodeId: Long,
  request: ModeDecisionRequest,
  context: DecisionContext
) extends BaseEventData

final case class ModeDecisionReplyData(
  requestSeq: Long,
  result: Either[NoViableJourney, List[AtomicLeg]]
) extends BaseEventData
