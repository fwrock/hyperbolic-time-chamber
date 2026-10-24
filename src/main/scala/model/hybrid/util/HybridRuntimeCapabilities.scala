package org.interscity.htc.model.hybrid.util

object HybridRuntimeCapabilities {
  def validateOptimisticRouting(strategy: String): Unit =
    validateAccountedRouting(strategy, "optimistic")

  def validateAccountedRouting(strategy: String, policy: String): Unit =
    require(Set("disabled", "none", "off").contains(strategy.toLowerCase(java.util.Locale.ROOT)),
      s"$policy dynamic routing requires a timestamped feedback adapter; set htc.routing.cache-strategy=disabled for static routing")

}
