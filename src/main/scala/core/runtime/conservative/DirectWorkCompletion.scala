package org.interscity.htc
package core.runtime.conservative

/** Immutable replacement of one processed callback by all work that it emitted. */
final case class DirectWorkCompletion(work: DirectWork, children: Vector[DirectWork])
