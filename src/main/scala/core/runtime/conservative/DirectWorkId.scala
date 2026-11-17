package org.interscity.htc
package core.runtime.conservative

import core.runtime.accounting.LogicalProcessId

/** A source-local identity; negative sequences are reserved for owner-enrolled roots. */
final case class DirectWorkId(source: LogicalProcessId, incarnation: String, sequence: Long)
