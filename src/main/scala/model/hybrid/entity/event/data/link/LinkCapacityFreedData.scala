package org.interscity.htc
package model.hybrid.entity.event.data.link

import core.entity.event.data.BaseEventData

/** Sent by a Link to its entry Node (`LinkState.from`) every time a vehicle leaves it, reporting
  * exactly how many slots freed (not an estimate). Node uses this to increment its own
  * `NodeState.availableCapacity` counter for that link and, if any vehicles are buffered waiting
  * for capacity, to drain up to that many of them. See docs/CONGESTION_PROPAGATION_DESIGN.md.
  */
case class LinkCapacityFreedData(
  linkId: String,
  freedCount: Int
) extends BaseEventData
