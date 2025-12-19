package org.interscity.htc
package model.mobility.entity.event.data.vehicle

import core.entity.event.data.BaseEventData

/** Event-driven: Vehicle arrived at node (scheduled event)
  *
  * Replaces continuous position polling. Vehicle schedules this event
  * once when entering link, based on calculated travel time.
  *
  * @param nodeId  Node where vehicle arrived
  * @param linkId  Link just traversed
  */
case class ArriveAtNodeData(
  nodeId: String,
  linkId: String
) extends BaseEventData
