package org.interscity.htc.model.hybrid.util

object HybridRuntimeCapabilities {
  def validateOptimisticRouting(strategy: String): Unit =
    validateAccountedRouting(strategy, "optimistic")

  def validateAccountedRouting(strategy: String, policy: String): Unit =
    require(Set("disabled", "none", "off", "accounted").contains(strategy.toLowerCase(java.util.Locale.ROOT)),
      s"$policy dynamic routing requires htc.routing.cache-strategy=accounted (or disabled for static routing)")

}
