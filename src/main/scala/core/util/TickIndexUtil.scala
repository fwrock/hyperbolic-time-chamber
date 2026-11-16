package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation
import core.types.Tick

import java.io.{ BufferedInputStream, FileInputStream }
import scala.collection.mutable
import scala.util.Using

/** Streams actor sources into tick counts or bounded loading chunks without retaining a full
  * source.
  *
  * Missing and nonnumeric start ticks map to zero. An explicit `Long.MinValue` remains in the count
  * map but is excluded from the bounds calculation. Loading a window re-reads the source, trading
  * disk I/O for bounded retained actor data.
  */
object TickIndexUtil {

  /** Counts and bounds without retained actor payloads.
    *
    * This existing nested public type retains its owner to preserve its JVM name and callers using
    * `TickIndexUtil.LightTickIndex`. Bounds exclude `Long.MinValue`; the upper bound starts at
    * zero.
    *
    * @param tickCounts
    *   actor count per extracted start tick
    * @param totalActors
    *   total records scanned
    * @param maxTick
    *   maximum non-sentinel start tick, or zero if every such tick is negative or absent
    * @param minTick
    *   minimum non-sentinel start tick, or zero when the minimum accumulator remains
    *   `Long.MaxValue`
    */
  case class LightTickIndex(
    tickCounts: Map[Tick, Int],
    totalActors: Int,
    maxTick: Tick,
    minTick: Tick
  )

  /** Scans a JSON source with scoped stream and parser lifetimes.
    *
    * The stream always closes; a parser returned by the factory closes when scanning finishes or
    * fails. Only counts and bounds survive the scan. File and JSON failures propagate to the
    * caller.
    */
  def buildLightIndex(filePath: String): LightTickIndex =
    Using.resource(new BufferedInputStream(new FileInputStream(filePath))) {
      stream =>
        val (parser, actors) = JsonStreamingUtil.createParser(stream)
        Using.resource(parser)(
          _ => indexActors(actors)
        )
    }

  /** Reduces one record at a time while preserving the existing sentinel and bound conventions. */
  private def indexActors(actors: Iterator[ActorSimulation]): LightTickIndex = {
    val tickCounts = mutable.Map.empty[Tick, Int]
    var maxTick: Tick = 0L
    var minTick: Tick = Long.MaxValue
    var totalActors = 0

    while (actors.hasNext) {
      val tick = extractStartTick(actors.next())
      tickCounts.updateWith(tick) {
        case Some(count) => Some(count + 1)
        case None        => Some(1)
      }
      if (tick != Long.MinValue) {
        if (tick > maxTick) maxTick = tick
        if (tick < minTick) minTick = tick
      }
      totalActors += 1
    }

    LightTickIndex(
      tickCounts = tickCounts.toMap,
      totalActors = totalActors,
      maxTick = maxTick,
      minTick = if (minTick == Long.MaxValue) 0L else minTick
    )
  }

  /** Collects up to `maxCount` actors in the inclusive tick range from a caller-owned iterator.
    *
    * Nonmatching records are consumed without being retained. The returned flag becomes true only
    * when exhaustion is observed: reaching the chunk limit exactly leaves it false until a later
    * call probes the iterator. Nonpositive limits consume nothing and return false.
    */
  def readMatchingChunk(
    iter: Iterator[ActorSimulation],
    fromTick: Tick,
    toTick: Tick,
    maxCount: Int
  ): (List[ActorSimulation], Boolean) = {
    val chunk = mutable.ListBuffer.empty[ActorSimulation]
    var exhausted = false

    while (!exhausted && chunk.size < maxCount)
      if (iter.hasNext) {
        val actor = iter.next()
        val tick = extractStartTick(actor)
        if (tick >= fromTick && tick <= toTick) chunk += actor
      } else exhausted = true

    (chunk.toList, exhausted)
  }

  /** Reads the supported Jackson/Scala map representations, defaulting absent data to tick zero. */
  private def extractStartTick(actor: ActorSimulation): Tick = {
    if (actor.data == null || actor.data.content == null) return 0L
    actor.data.content match {
      case map: java.util.LinkedHashMap[?, ?] => numericStartTick(map.get("startTick"))
      case map: Map[String @unchecked, Any @unchecked] =>
        numericStartTick(map.getOrElse("startTick", null))
      case _ => 0L
    }
  }

  /** Keeps numeric conversion identical for both supported map representations. */
  private def numericStartTick(value: Any): Tick = value match {
    case number: java.lang.Number => number.longValue()
    case _                        => 0L
  }

  /** Sums counts in an inclusive tick range; an inverted range contains no ticks. */
  def countActorsInRange(
    tickCounts: Map[Tick, Int],
    fromTick: Tick,
    toTick: Tick
  ): Int = tickCounts.iterator.collect {
    case (tick, count) if tick >= fromTick && tick <= toTick => count
  }.sum
}
