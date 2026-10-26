package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import core.util.JsonUtil
import com.fasterxml.jackson.databind.JsonNode
import scala.util.Try
import scala.jdk.CollectionConverters.*

object SpatialPositionUtil {
  def extract(content: Any): Option[(Double, Double)] = Try {
    val json = JsonUtil.convertValue[JsonNode](content)
    def number(name: String): Option[Double] = Option(json.get(name)).flatMap { value =>
      if (value.isNumber) Some(value.asDouble()) else value.asText().toDoubleOption
    }.filter(_.isFinite)
    val direct = for (x <- number("longitude"); y <- number("latitude")) yield x -> y
    direct.orElse(Vector("from", "origin", "currentNode", "nodeId").iterator.flatMap(name =>
      Option(json.get(name)).flatMap(value => SpatialShardIdRegistry.getPosition(value.asText()))).nextOption())
      .orElse(json.path("originalPlan").elements().asScala.flatMap(element =>
        Option(element.get("nodeId")).flatMap(value => SpatialShardIdRegistry.getPosition(value.asText()))).nextOption())
  }.toOption.flatten

  def resolve(content: Any, config: com.typesafe.config.Config): (Double, Double) = extract(content).getOrElse {
    val prefix = "htc.load-balance-manager.world-bounds."
    ((config.getDouble(prefix + "min-x") + config.getDouble(prefix + "max-x")) / 2.0,
      (config.getDouble(prefix + "min-y") + config.getDouble(prefix + "max-y")) / 2.0)
  }
}
