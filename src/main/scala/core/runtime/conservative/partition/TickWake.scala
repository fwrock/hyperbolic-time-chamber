package org.interscity.htc
package core.runtime.conservative.partition

import org.htc.protobuf.core.entity.actor.Identify

/** Node-local handles distinguish typed LPs; one handle/tick pair denotes one wake-up. */
final case class TickWake(tick: Long, identity: Identify)
