package org.interscity.htc
package core.actor.manager.loadbalance.prediction

import core.entity.control.loadbalance.{ FlowVector, ShardMetrics }

import scala.collection.mutable

/** Predictive load balancer that anticipates shard saturation before it occurs.
  *
  * Uses flow vector analysis and linear projection to predict which shards will become overloaded.
  * When a shard is projected to exceed the load threshold within the prediction window, migration
  * is triggered proactively — hiding network transfer latency within the time before actual
  * saturation.
  *
  * @param loadThreshold
  *   Load fraction threshold (e.g., 0.85 = 85%)
  * @param predictionWindowSeconds
  *   How far ahead to predict (e.g., 10 seconds)
  * @param maxSamples
  *   Maximum flow vector samples to retain per shard
  */
class PredictiveBalancer(
  val loadThreshold: Double = 0.85,
  val predictionWindowSeconds: Double = 10.0,
  val maxSamples: Int = 100
) {

  /** Historical metrics per shard for trend analysis */
  private val metricsHistory: mutable.Map[String, mutable.ArrayDeque[ShardMetrics]] =
    mutable.Map.empty

  /** Flow vector history per shard */
  private val flowHistory: mutable.Map[String, mutable.ArrayDeque[FlowVector]] =
    mutable.Map.empty

  /** Records a new metrics snapshot for a shard.
    *
    * @param metrics
    *   The current metrics
    */
  def recordMetrics(metrics: ShardMetrics): Unit = {
    val history = metricsHistory.getOrElseUpdate(metrics.shardId, mutable.ArrayDeque.empty)
    history.addOne(metrics)
    if (history.size > maxSamples) history.removeHead()

    if (metrics.flowVector != FlowVector.Zero) {
      val flows = flowHistory.getOrElseUpdate(metrics.shardId, mutable.ArrayDeque.empty)
      flows.addOne(metrics.flowVector)
      if (flows.size > maxSamples) flows.removeHead()
    }
  }

  /** Predicts which shards will exceed the load threshold within the prediction window.
    *
    * Uses linear extrapolation of the load trend:
    *
    * $L_{predicted}(t + \Delta t) = L_{current} + \frac{dL}{dt} \cdot \Delta t$
    *
    * @param maxShardWeight
    *   The maximum weight capacity of a shard (for normalization)
    * @return
    *   List of (shardId, currentLoad, predictedLoad, timeToSaturation) for at-risk shards
    */
  def predictOverloadedShards(
    maxShardWeight: Double
  ): List[PredictionResult] = {
    val results = mutable.ListBuffer[PredictionResult]()

    metricsHistory.foreach {
      case (shardId, history) if history.size >= 2 =>
        val current = history.last
        val currentLoad = current.loadScore(maxShardWeight)

        if (currentLoad >= loadThreshold) {
          results += PredictionResult(
            shardId = shardId,
            currentLoad = currentLoad,
            predictedLoad = currentLoad,
            timeToSaturationSeconds = 0.0,
            isAlreadyOverloaded = true
          )
        } else {
          val loadTrend = calculateLoadTrend(history, maxShardWeight)

          if (loadTrend > 0) {
            val timeToThreshold = (loadThreshold - currentLoad) / loadTrend
            val predictedLoad = currentLoad + loadTrend * predictionWindowSeconds

            if (predictedLoad >= loadThreshold) {
              results += PredictionResult(
                shardId = shardId,
                currentLoad = currentLoad,
                predictedLoad = predictedLoad,
                timeToSaturationSeconds = timeToThreshold,
                isAlreadyOverloaded = false
              )
            }
          }
        }

      case _ => ()
    }

    results.toList.sortBy(_.timeToSaturationSeconds)
  }

  /** Gets the average flow vector for a shard.
    *
    * @param shardId
    *   The shard to query
    * @return
    *   The average flow vector, or Zero if no data
    */
  def getAverageFlowVector(shardId: String): FlowVector =
    flowHistory.get(shardId) match {
      case Some(flows) if flows.nonEmpty =>
        flows.foldLeft(FlowVector.Zero)(_.merge(_))
      case _ => FlowVector.Zero
    }

  /** Clears all history for a shard (e.g., after migration). */
  def clearHistory(shardId: String): Unit = {
    metricsHistory.remove(shardId)
    flowHistory.remove(shardId) // Not enough history
  }

  /** Clears all history. */
  def clearAll(): Unit = {
    metricsHistory.clear()
    flowHistory.clear()
  }

  /** Calculate load trend (dL/dt) from historical metrics using simple linear regression.
    *
    * @return
    *   Rate of load change per second (positive = increasing)
    */
  private def calculateLoadTrend(
    history: mutable.ArrayDeque[ShardMetrics],
    maxWeight: Double
  ): Double = {
    if (history.size < 2) return 0.0

    val points = history.toList.map {
      m =>
        val t = m.timestamp.toDouble / 1e9 // Convert nanos to seconds
        val l = m.loadScore(maxWeight)
        (t, l)
    }

    val n = points.size.toDouble
    val sumT = points.map(_._1).sum
    val sumL = points.map(_._2).sum
    val sumTL = points.map {
      case (t, l) => t * l
    }.sum
    val sumT2 = points.map {
      case (t, _) => t * t
    }.sum

    val denominator = n * sumT2 - sumT * sumT
    if (math.abs(denominator) < 1e-15) 0.0
    else (n * sumTL - sumT * sumL) / denominator
  }
}

/** Result of a load prediction for a single shard.
  *
  * @param shardId
  *   The shard being analyzed
  * @param currentLoad
  *   Current normalized load [0.0, 1.0]
  * @param predictedLoad
  *   Predicted load at end of prediction window
  * @param timeToSaturationSeconds
  *   Estimated seconds until threshold is reached
  * @param isAlreadyOverloaded
  *   Whether the shard is already above threshold
  */
case class PredictionResult(
  shardId: String,
  currentLoad: Double,
  predictedLoad: Double,
  timeToSaturationSeconds: Double,
  isAlreadyOverloaded: Boolean
)
