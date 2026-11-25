package org.interscity.htc
package model.mobility.entity.state.model

import core.util.StringPool
import model.mobility.entity.event.data.{LinkAccessRequest, MicroLeaveLinkData}

final case class MicroDelegatedTraversal(requestSeq: Long, accessRequest: Option[LinkAccessRequest], useExitLane: Boolean = false) {
  def internStrings: MicroDelegatedTraversal =
    copy(accessRequest = accessRequest.map(a => a.copy(nodeClassType = StringPool.intern(a.nodeClassType))))
}

/** The Link must deliver this exit even when the Node answers Full or Red. */
final case class PendingMicroExit(shardId: String, traversal: MicroDelegatedTraversal, reply: MicroLeaveLinkData) {
  def internStrings: PendingMicroExit =
    copy(shardId = StringPool.intern(shardId), traversal = traversal.internStrings)
}
