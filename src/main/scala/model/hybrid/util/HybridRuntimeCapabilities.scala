package org.interscity.htc.model.hybrid.util

object HybridRuntimeCapabilities {
  def validateOptimisticRouting(strategy: String): Unit =
    require(Set("disabled", "none", "off").contains(strategy.toLowerCase(java.util.Locale.ROOT)),
      "optimistic dynamic routing requires a timestamped feedback adapter; set htc.routing.cache-strategy=disabled for static routing")

  def requireCancellableTransitBirth(): Unit =
    throw new UnsupportedOperationException(
      "optimistic transit stations require a cancellable birth adapter; precreate inert vehicle LPs before execution")
}
