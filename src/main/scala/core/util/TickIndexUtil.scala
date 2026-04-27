package org.interscity.htc
package core.util

import core.types.Tick
import core.entity.actor.ActorSimulation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonParser

import java.io.{ BufferedInputStream, File, FileInputStream }
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Utility for building lightweight tick-based indexes of actors in JSON files.
  *
  * Two-phase approach to avoid holding all ActorSimulation objects in memory:
  *
  * Phase 1 — Light index (buildLightIndex): Streams through JSON, extracts only startTick from each
  * actor, builds a counts-only map (tick → actor count). No ActorSimulation objects are retained —
  * they are parsed and immediately discarded. Memory footprint is just a Map[Tick, Int] per source.
  *
  * Phase 2 — On-demand loading (loadActorsForTickRange): Re-reads the JSON file, streaming actors
  * and only retaining those whose startTick falls in the requested range. This trades disk I/O for
  * memory.
  *
  * For actors without a `startTick` (or with startTick = Long.MinValue), they are indexed under
  * tick 0 (should be created at startup).
  */
object TickIndexUtil {

  private val mapper = new ObjectMapper()
  mapper.registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)

  /** Lightweight index metadata — no ActorSimulation objects retained.
    *
    * @param tickCounts
    *   immutable map of tick -> actor count (for density analysis and window sizing)
    * @param totalActors
    *   total number of actors in the file
    * @param maxTick
    *   the maximum startTick found
    * @param minTick
    *   the minimum startTick found (excluding Long.MinValue)
    */
  case class LightTickIndex(
    tickCounts: Map[Tick, Int],
    totalActors: Int,
    maxTick: Tick,
    minTick: Tick
  )

  /** Scan a JSON file and build a lightweight tick index (counts only).
    *
    * Streams through ALL actors in the file but only extracts startTick, increments a counter, and
    * immediately discards the parsed ActorSimulation. This keeps memory usage minimal — just a
    * Map[Tick, Int].
    *
    * @param filePath
    *   path to the JSON file
    * @return
    *   LightTickIndex with tick counts and bounds (no actor objects)
    */
  def buildLightIndex(filePath: String): LightTickIndex = {
    val is = new BufferedInputStream(new FileInputStream(new File(filePath)))
    try {
      val (_, iter) = JsonStreamingUtil.createParser(is)
      val tickCounts = mutable.Map[Tick, Int]()
      var maxTick: Tick = 0
      var minTick: Tick = Long.MaxValue
      var totalActors = 0

      while (iter.hasNext) {
        val actor = iter.next()
        val tick = extractStartTick(actor)

        tickCounts.updateWith(tick) {
          case Some(count) => Some(count + 1)
          case None        => Some(1)
        }
        // actor is NOT stored — eligible for GC immediately

        if (tick != Long.MinValue) {
          if (tick > maxTick) maxTick = tick
          if (tick < minTick) minTick = tick
        }

        totalActors += 1
      }

      if (minTick == Long.MaxValue) minTick = 0

      LightTickIndex(
        tickCounts = tickCounts.toMap,
        totalActors = totalActors,
        maxTick = maxTick,
        minTick = minTick
      )
    } finally is.close()
  }

  /** Read up to maxCount matching actors from an open iterator.
    *
    * Reads actors one-by-one from the iterator, filtering by tick range. Stops after collecting
    * maxCount matching actors OR when the iterator is exhausted. Non-matching actors are parsed but
    * immediately discarded (eligible for GC).
    *
    * This is designed for chunked streaming: the caller holds the file stream open and calls this
    * repeatedly with the same iterator, processing each chunk before requesting the next. This
    * keeps at most maxCount actors in memory per loader.
    *
    * @param iter
    *   open Jackson iterator over ActorSimulation objects
    * @param fromTick
    *   start tick (inclusive)
    * @param toTick
    *   end tick (inclusive)
    * @param maxCount
    *   maximum number of matching actors to collect per call
    * @return
    *   tuple of (matching actors, true if iterator exhausted)
    */
  def readMatchingChunk(
    iter: Iterator[ActorSimulation],
    fromTick: Tick,
    toTick: Tick,
    maxCount: Int
  ): (List[ActorSimulation], Boolean) = {
    val chunk = mutable.ListBuffer[ActorSimulation]()
    var exhausted = false

    while (!exhausted && chunk.size < maxCount)
      if (iter.hasNext) {
        val actor = iter.next()
        val tick = extractStartTick(actor)
        if (tick >= fromTick && tick <= toTick) {
          chunk += actor
        }
        // non-matching actors are immediately eligible for GC
      } else {
        exhausted = true
      }

    (chunk.toList, exhausted)
  }

  /** Extract startTick from an ActorSimulation's data.content. The content is typically a
    * LinkedHashMap from Jackson deserialization before being converted to the specific state type.
    *
    * Returns 0 for actors without a startTick (infrastructure actors).
    */
  private def extractStartTick(actor: ActorSimulation): Tick = {
    if (actor.data == null || actor.data.content == null) return 0L

    actor.data.content match {
      case map: java.util.LinkedHashMap[?, ?] =>
        val value = map.get("startTick")
        if (value == null) 0L
        else
          value match {
            case n: java.lang.Number => n.longValue()
            case _                   => 0L
          }
      case map: Map[String @unchecked, Any @unchecked] =>
        map.get("startTick") match {
          case Some(n: java.lang.Number) => n.longValue()
          case _                         => 0L
        }
      case _ => 0L
    }
  }

  /** Count actors in a tick range using the lightweight tick counts map.
    */
  def countActorsInRange(
    tickCounts: Map[Tick, Int],
    fromTick: Tick,
    toTick: Tick
  ): Int =
    tickCounts.filter {
      case (tick, _) => tick >= fromTick && tick <= toTick
    }.values.sum
}
