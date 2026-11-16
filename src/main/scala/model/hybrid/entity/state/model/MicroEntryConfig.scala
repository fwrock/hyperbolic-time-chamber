package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick
import model.hybrid.entity.event.data.EnterLinkData

final case class MicroEntryConfig(enabled: Boolean = false, avoidWeavingConflicts: Boolean = true)

final case class PendingMicroEntry(
  actorId: Long, shardId: String, requestedTick: Tick, requestSeq: Long, data: EnterLinkData
)
