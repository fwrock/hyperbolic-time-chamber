package org.interscity.htc
package model.hybrid.entity.event.data.link

import core.entity.event.data.BaseEventData

/** Sent once by a Link to its entry Node (`LinkState.from`) at Link initialization, so the Node
  * can seed `NodeState.availableCapacity` for that link before any vehicle ever requests access
  * to it. See docs/CONGESTION_PROPAGATION_DESIGN.md.
  */
case class RegisterLinkCapacityData(
  linkId: String,
  capacity: Int
) extends BaseEventData
