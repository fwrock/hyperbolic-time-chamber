package org.interscity.htc.model.hybrid.util.cache

import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost

import scala.util.{Success, Try}

/** No-op cache strategy that disables dynamic weights entirely.
  *
  * Used when Kafka is not available. All publish operations are ignored,
  * and getWeight always returns the static weight directly.
  * Dynamic weights are a Kafka-only feature.
  */
class DisabledCacheStrategy extends WeightCacheStrategy {

  override def publishCost(cost: DynamicLinkCost, ttlSeconds: Int): Try[Unit] =
    Success(())

  override def getCost(linkId: String): Option[DynamicLinkCost] =
    None

  override def getWeight(linkId: String, staticWeight: Double): Double =
    staticWeight

  override def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    linkWeights

  override def clearCost(linkId: String): Try[Unit] =
    Success(())

  override def clearAllCosts(): Try[Unit] =
    Success(())

  override def getStatistics(): (Int, Double, Int) =
    (0, 0.0, 0)
}
