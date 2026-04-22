package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

/**
 * Internal event: the tick index for a source file has been built.
 *
 * @param sourceId    identifier of the data source
 * @param tickCounts  lightweight map from startTick to number of actors at that tick
 * @param totalActors total number of actors found in the file
 * @param maxTick     the maximum startTick found
 */
case class TickIndexBuiltEvent(
  sourceId: String,
  tickCounts: Map[Tick, Int],
  totalActors: Int,
  maxTick: Tick
)
