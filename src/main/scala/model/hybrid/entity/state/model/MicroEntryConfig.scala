package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import model.hybrid.entity.event.data.EnterLinkData

/** Queued admission, optionally restoring exact nonoverlapping positions at one snapshot tick.
  * Snapshot restoration does not permit later interior births or change ordinary entry rules.
  */
final case class MicroEntryConfig(
  enabled: Boolean = false,
  avoidWeavingConflicts: Boolean = true,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  initialSnapshotTick: Option[Tick] = None
) {
  require(initialSnapshotTick.isEmpty || enabled, "initial MICRO snapshot requires queued entry")
}

final case class PendingMicroEntry(
  actorId: Long,
  shardId: String,
  requestedTick: Tick,
  requestSeq: Long,
  data: EnterLinkData
)
