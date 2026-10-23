package org.interscity.htc
package core.entity.event.control.execution

import core.runtime.EventOrder
import org.htc.protobuf.core.entity.actor.Identify

/** An atomic replacement, including cancellation. Revision belongs to transport, not checkpoints.
  * Only dispatches already seen by the LP can be released; an unseen one must acknowledge discard.
  */
final case class ReplaceScheduleEvent(
  identify: Identify,
  revision: Long,
  next: Option[EventOrder],
  completedGeneration: Long,
  awaitingReply: Boolean
)
