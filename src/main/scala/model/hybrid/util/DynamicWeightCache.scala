package org.interscity.htc.model.hybrid.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.cache.{ DisabledCacheStrategy, InMemoryCacheStrategy, KafkaCacheStrategy, WeightCacheStrategy }

import java.util.concurrent.atomic.AtomicLong
import scala.util.Try
import scala.concurrent.ExecutionContext

/** Cache for dynamic link costs with configurable strategy.
  *
  * Supports multiple backends:
  *
  *   1. **Kafka** (recommended):
  *      - LinkActors publish → Kafka topic → all nodes consume
  *      - ~10ns local reads (ultra-fast A* calculation)
  *      - Eventually consistent (~100ms realistic lag)
  *      - Best for multi-node clusters with high-frequency routing
  *
  * 2. **Redis** (centralized):
  *   - Single Redis instance shared by cluster
  *   - ~1ms network latency
  *   - Immediate consistency
  *   - Legacy option for simpler deployments
  *
  * 3. **InMemory** (distributed):
  *   - Local ConcurrentHashMap + Pekko Distributed Data
  *   - ~10ns local reads (ultra-fast!)
  *   - Eventually consistent (~10-50ms sync)
  *   - Best for single-node or when eventual consistency OK
  *
  * Configuration:
  * ``
  * htc.routing.cache-strategy = "kafka"  # or "redis" or "inmemory"
  * ``
  */
object DynamicWeightCache {

  private val config = ConfigFactory.load()

  private lazy val strategy: WeightCacheStrategy = {
    val strategyType =
      try
        config.getString("htc.routing.cache-strategy")
      catch {
        case _: Exception => "inmemory"
      }

    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    strategyType.toLowerCase match {
      case "kafka" =>
        println(
          "[DynamicWeightCache] Using Kafka cache strategy (distributed pub/sub + local memory)"
        )
        new KafkaCacheStrategy()

      case "inmemory" =>
        println(
          "[DynamicWeightCache] Using InMemory cache strategy (local ConcurrentHashMap, no cluster sync)"
        )
        new InMemoryCacheStrategy()

      case "disabled" | "none" | "off" =>
        println("[DynamicWeightCache] Dynamic weights DISABLED — using static weights only.")
        new DisabledCacheStrategy()

      case other =>
        println(
          s"[DynamicWeightCache] Unknown cache strategy '$other' — falling back to InMemory."
        )
        new InMemoryCacheStrategy()
    }
  }

  /** Factory method to create strategy with ActorSystem. Dynamic weights only work with Kafka;
    * otherwise returns disabled (no-op).
    */
  def createStrategy(strategyType: String)(implicit system: ActorSystem): WeightCacheStrategy = {
    implicit val ec: ExecutionContext = system.dispatcher

    strategyType.toLowerCase match {
      case "kafka"                  => new KafkaCacheStrategy()
      case "inmemory"               => new InMemoryCacheStrategy()
      case "disabled" | "none" | "off" => new DisabledCacheStrategy()
      case _                        => new InMemoryCacheStrategy()
    }
  }

  def publishCost(cost: DynamicLinkCost, ttlSeconds: Int = 60): Try[Unit] =
    strategy.publishCost(cost, ttlSeconds)

  def getCost(linkId: String): Option[DynamicLinkCost] =
    strategy.getCost(linkId)

  // ---------------------------------------------------------------------------
  // Lightweight observability: cheap atomic counters only. No per-link tracking
  // and no allocation on the hot path. Logged every LOG_INTERVAL queries.
  // ---------------------------------------------------------------------------
  private val dynHits      = new AtomicLong(0L)
  private val staticMisses = new AtomicLong(0L)
  private val totalQueries = new AtomicLong(0L)

  private val LOG_INTERVAL: Long =
    try
      config.getLong("htc.routing.dynamic-weight-log-interval")
    catch {
      case _: Exception => 100000L
    }

  // ---------------------------------------------------------------------------
  // Time Warp replay-safety (docs/TIME_WARP_DESIGN.md's model-level audit, third finding):
  // dynamic link costs arrive via Kafka/Distributed-Data pub-sub on a real-time TTL, entirely
  // outside HTC's deterministic event log. A route computed live (`getWeight` reading whatever the
  // strategy holds *right now*) and later replayed by `RollbackHistoryHandler.rollbackTo` would read
  // a DIFFERENT, time-shifted set of costs on replay -- silently reconstructing a different route
  // than the one that was actually causally communicated to other actors (LinkInfoData already sent
  // along the ORIGINAL route). Fixed with a ThreadLocal recording/override hook rather than
  // threading parameters through every call site (CompactGraph's A*, CityMapUtil's congestion
  // check, and any future caller) -- `SimulationBaseActor` wraps a live dispatch in `withRecording`
  // and a replay in `withOverride`, transparently covering whatever `getWeight` calls happen inside,
  // wherever they are, without CompactGraph/GPSUtil/the model actors needing to know Time Warp
  // exists. Safe across concurrently-running actors on different threads by construction (each
  // thread's Pekko dispatcher runs one actor's message to completion before picking up the next, so
  // a ThreadLocal set for the duration of one such call never leaks across actors).
  // ---------------------------------------------------------------------------
  private val recordingSink: ThreadLocal[scala.collection.mutable.Map[String, Double]] =
    new ThreadLocal[scala.collection.mutable.Map[String, Double]]()
  private val overrideMap: ThreadLocal[Map[String, Double]] =
    new ThreadLocal[Map[String, Double]]()

  /** Runs `body` with every `getWeight` read observed during it recorded into `sink` (the caller's
    * own mutable map — typically an actor's per-event read buffer, flushed into its
    * `RollbackHistoryHandler` log once the event finishes). Used only for a LIVE dispatch.
    */
  def withRecording[A](sink: scala.collection.mutable.Map[String, Double])(body: => A): A = {
    val previous = recordingSink.get()
    recordingSink.set(sink)
    try body
    finally recordingSink.set(previous)
  }

  /** Runs `body` with every `getWeight` read forced to come from `overrides` instead of the live
    * strategy (falling back to the edge's static weight for any link not in the map, same as a
    * live cache miss would). Used only while replaying an already-processed event, so it reproduces
    * exactly the reads that event's original live dispatch made, not whatever the cache holds now.
    */
  def withOverride[A](overrides: Map[String, Double])(body: => A): A = {
    val previous = overrideMap.get()
    overrideMap.set(overrides)
    try body
    finally overrideMap.set(previous)
  }

  def getWeight(linkId: String, staticWeight: Double): Double = {
    val activeOverride = overrideMap.get()
    val (w, wasDynamic) =
      if (activeOverride != null) {
        val ov = activeOverride.get(linkId)
        (ov.getOrElse(staticWeight), ov.isDefined)
      } else {
        val costOpt = strategy.getCost(linkId)
        (costOpt.map(_.totalCost).getOrElse(staticWeight), costOpt.isDefined)
      }

    val activeSink = recordingSink.get()
    if (activeSink != null) activeSink.update(linkId, w)

    if (wasDynamic) dynHits.incrementAndGet() else staticMisses.incrementAndGet()

    val total = totalQueries.incrementAndGet()
    if (LOG_INTERVAL > 0 && total % LOG_INTERVAL == 0L) {
      val d   = dynHits.get()
      val s   = staticMisses.get()
      val pct = if (total > 0) 100.0 * d / total else 0.0
      println(
        f"[DynamicWeightCache] queries=$total dyn=$d ($pct%.2f%%) static=$s"
      )
    }
    w
  }

  def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    strategy.getBatchWeights(linkWeights)

  def clearCost(linkId: String): Try[Unit] =
    strategy.clearCost(linkId)

  def clearAllCosts(): Try[Unit] =
    strategy.clearAllCosts()

  def getStatistics(): (Int, Double, Int) =
    strategy.getStatistics()
}
