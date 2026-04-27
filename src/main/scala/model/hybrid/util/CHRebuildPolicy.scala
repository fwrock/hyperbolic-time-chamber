package org.interscity.htc
package model.hybrid.util

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters.*

/** Defines when the Contraction Hierarchies index should be rebuilt during simulation.
  *
  * Two complementary triggers work independently — either one fires a rebuild:
  *
  * ==Scheduled ticks==
  * Force a rebuild at specific global simulation ticks regardless of traffic state. Intended for
  * known traffic regime changes: simulation start, morning peak, midday, evening peak, and
  * end-of-day. Configured via `htc.routing.ch.rebuild-scheduled-ticks`.
  *
  * ==Block threshold==
  * Trigger an immediate rebuild when ''any'' link's dynamic weight exceeds its static weight by
  * `blockThresholdFactor` times. A factor of 5 means the link costs 5× the free-flow travel time —
  * effectively impassable — so routing around it matters. Configured via
  * `htc.routing.ch.rebuild-block-threshold-factor`.
  *
  * ==What NOT to use it for==
  * Normal congestion fluctuations (e.g. 1.2× slowdown) should NOT trigger a rebuild. For live
  * traffic-aware routing use [[GPSUtil.calcRoute]] with `useDynamicWeights = true` (plain Dijkstra
  * + DynamicWeightCache). CH rebuilds are for structural topology changes: blocked roads,
  * accidents, and scheduled peak periods.
  *
  * @param blockThresholdFactor
  *   Rebuild when dynamicWeight > staticWeight × factor. Defaults to 5.0.
  * @param scheduledTicks
  *   Global simulation ticks at which to force a rebuild. Defaults: 0 (start), 25200 (07:00), 43200
  *   (12:00), 61200 (17:00), 72000 (20:00) — assuming 1 tick = 1 second.
  */
case class CHRebuildPolicy(
  blockThresholdFactor: Double,
  scheduledTicks: Set[Int]
)

object CHRebuildPolicy {

  private val config = ConfigFactory.load()

  /** Loads the policy from `application.conf` (section `htc.routing.ch`). Falls back to sensible
    * defaults when the section is absent.
    *
    * {{{
    * htc.routing.ch {
    *   rebuild-block-threshold-factor = 5.0
    *   # Ticks assuming 1 tick = 1 s, simulation starting at midnight
    *   rebuild-scheduled-ticks = [0, 25200, 43200, 61200, 72000]
    * }
    * }}}
    */
  lazy val fromConfig: CHRebuildPolicy = {
    val factor =
      try config.getDouble("htc.routing.ch.rebuild-block-threshold-factor")
      catch { case _: Exception => 5.0 }

    val ticks: Set[Int] =
      try
        config
          .getIntList("htc.routing.ch.rebuild-scheduled-ticks")
          .asScala
          .map(_.toInt)
          .toSet
      catch { case _: Exception => Set(0, 25200, 43200, 61200, 72000) }

    CHRebuildPolicy(factor, ticks)
  }
}
