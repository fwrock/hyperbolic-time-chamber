package org.interscity.htc
package model.mobility.entity.event.data.vehicle

import org.interscity.htc.core.entity.event.data.BaseEventData

/** MICRO Link authorizes crossing within its physics interval; Node still owns downstream capacity.
  * The clearance survives reporting that crossing at the interval's rounded-up global tick.
  */
case class RequestLinkAccessData(
  targetLinkId: Long,
  signalAlreadyCleared: Boolean = false,
  approachLinkId: Long = 0L,
  approachLane: Option[Int] = None,
  followingLinkId: Long = 0L,
  /** Set when a Link asks on behalf of a vehicle: the Node orders, cancels and reports by this id and the reply
    * (which goes to the sender) names it again. 0 means the sender is the requester.
    */
  requesterId: Long = 0L,
  requesterShard: String = "",
  /** The requester's own send sequence, which the answer echoes so the requester can correlate it. */
  requesterSeq: Long = 0L
) extends BaseEventData
