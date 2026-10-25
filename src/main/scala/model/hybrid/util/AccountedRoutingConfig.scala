package org.interscity.htc
package model.hybrid.util

import com.typesafe.config.ConfigFactory

import java.util.Locale
import scala.jdk.CollectionConverters.*

/** Configuration for routing feedback that participates in simulation time. */
object AccountedRoutingConfig {
  val ActorClassType = "hybrid.actor.RoutingService"

  private def config = ConfigFactory.load()

  def enabled: Boolean =
    DynamicWeightCache.configuredStrategy.toLowerCase(Locale.ROOT) == "accounted"

  def workerIds: Vector[Long] =
    if (!enabled) Vector.empty
    else {
      require(config.hasPath("htc.routing.accounted.worker-ids"),
        "accounted routing requires htc.routing.accounted.worker-ids")
      val ids = config.getLongList("htc.routing.accounted.worker-ids").asScala.map(_.longValue).toVector
      require(ids.nonEmpty, "htc.routing.accounted.worker-ids must not be empty")
      require(ids.distinct.size == ids.size, "htc.routing.accounted.worker-ids must be unique")
      ids
    }

  def workerFor(requesterId: Long): Long = {
    val ids = workerIds
    ids(Math.floorMod(java.lang.Long.hashCode(requesterId), ids.size))
  }
}
