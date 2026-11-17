package org.interscity.htc
package core.runtime.conservative

import core.runtime.accounting.LogicalProcessId

/** Parentage accounts work without ordering independent same-tick interactions. */
final case class DirectWork(
  id: DirectWorkId,
  parent: Option[DirectWorkId],
  receiver: LogicalProcessId,
  tick: Long
)
