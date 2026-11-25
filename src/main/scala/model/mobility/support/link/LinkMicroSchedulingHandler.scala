package org.interscity.htc
package model.mobility.support.link

import model.mobility.entity.event.data.{EnterLinkData, MicroLeaveLinkData}
import model.mobility.entity.event.node.LinkAccessData
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.{ActorTypeEnum, EventTypeEnum, LinkCapacityStateEnum}
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import model.mobility.entity.state.model.{MicroDelegatedTraversal, PendingMicroExit}

class LinkMicroSchedulingHandler(
  stateFn: () => LinkState,
  enabledFn: () => Boolean,
  sendFn: (Long, String, AnyRef, String) => Unit,
  leaveFn: Long => Unit
) {
  def admit(actorId: Long, data: EnterLinkData, requestSeq: Long): Unit =
    if (enabledFn() && data.delegateMicroScheduling && LinkMicroSchedulingHandler.supports(data.actorType)) {
      val state = stateFn()
      if (state.microDelegations.get(actorId).forall(_.requestSeq <= requestSeq))
        state.microDelegations = state.microDelegations.updated(actorId,
          MicroDelegatedTraversal(requestSeq, data.microAccessRequest,
            data.actorType == model.mobility.entity.state.enumeration.ActorTypeEnum.Car).internStrings)
    }

  def exit(actorId: Long, shardId: String, reply: MicroLeaveLinkData): Unit = {
    val state = stateFn()
    state.microDelegations.get(actorId) match {
      case None => sendFn(actorId, shardId, reply, EventTypeEnum.MicroLeaveLink.toString)
      case Some(traversal) =>
        state.microDelegations = state.microDelegations - actorId
        traversal.accessRequest match {
          case None =>
            val leaves = !reply.retainedInLane
            if (leaves) leaveFn(actorId)
            sendFn(actorId, shardId, reply.copy(leaveDone = leaves), EventTypeEnum.MicroLeaveLink.toString)
          case Some(access) =>
            state.microAccessAwaiting = state.microAccessAwaiting.updated(actorId,
              PendingMicroExit(shardId, traversal, reply).internStrings)
            // Lane changes and the physics clearance are known only at the actual boundary, not at admission.
            sendFn(access.nodeId, access.nodeClassType,
              access.request.copy(signalAlreadyCleared = true,
                approachLane = if (traversal.useExitLane) reply.finalLane else access.request.approachLane,
                requesterId = actorId, requesterShard = shardId, requesterSeq = traversal.requestSeq),
              EventTypeEnum.RequestLinkAccess.toString)
        }
    }
  }

  /** A Full answer releases the exit report now; the Node's later grant uses the existing forwarding path. */
  def forwardNodeAccess(data: LinkAccessData): Boolean = {
    val state = stateFn()
    state.microAccessAwaiting.get(data.requesterId) match {
      case None => false
      case Some(pending) =>
        if (data.requestSeq == pending.traversal.requestSeq) {
          state.microAccessAwaiting = state.microAccessAwaiting - data.requesterId
          if (data.permit > 0) pending.traversal.accessRequest.foreach { access =>
            val target = access.request.targetLinkId
            state.permits = state.permits.updated(target, state.permits.getOrElse(target, 0) + data.permit)
          }
          val leaves = !pending.reply.retainedInLane && data.phase == Green &&
            data.capacityState == LinkCapacityStateEnum.Available
          if (leaves) leaveFn(data.requesterId)
          sendFn(data.requesterId, pending.shardId,
            pending.reply.copy(access = Some(data.copy(permit = 0)), leaveDone = leaves), EventTypeEnum.MicroLeaveLink.toString)
        }
        true
    }
  }
}

object LinkMicroSchedulingHandler {
  def supports(actorType: ActorTypeEnum): Boolean = actorType match {
    case ActorTypeEnum.Car | ActorTypeEnum.Bus | ActorTypeEnum.Bicycle | ActorTypeEnum.Motorcycle => true
    case _ => false
  }
}
