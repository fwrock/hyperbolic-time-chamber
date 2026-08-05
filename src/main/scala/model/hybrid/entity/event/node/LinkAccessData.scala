package org.interscity.htc
package model.hybrid.entity.event.node

import core.entity.event.data.BaseEventData

import org.interscity.htc.core.types.Tick
import model.hybrid.entity.state.enumeration.{ LinkCapacityStateEnum, TrafficSignalPhaseStateEnum }

/** Node's reply to `RequestLinkAccessData`, and the same shape used unsolicited when the Node
  * later grants a vehicle it had buffered for downstream link capacity (see
  * `NodeEventHandler.tryDrainCapacityQueue`).
  *
  * `capacityState` only matters when `phase == Green` — while Red, the vehicle isn't crossing
  * yet regardless, so capacity isn't checked at request time.
  */
case class LinkAccessData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick,
  queuePosition: Int = 0,
  capacityState: LinkCapacityStateEnum = LinkCapacityStateEnum.Available
) extends BaseEventData
