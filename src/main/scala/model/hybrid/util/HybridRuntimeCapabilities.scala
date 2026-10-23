package org.interscity.htc.model.hybrid.util

object HybridRuntimeCapabilities {
  def validateOptimisticRouting(strategy: String): Unit =
    validateAccountedRouting(strategy, "optimistic")

  def validateAccountedRouting(strategy: String, policy: String): Unit =
    require(Set("disabled", "none", "off").contains(strategy.toLowerCase(java.util.Locale.ROOT)),
      s"$policy dynamic routing requires a timestamped feedback adapter; set htc.routing.cache-strategy=disabled for static routing")

  def requireCancellableTransitBirth(): Unit =
    throw new UnsupportedOperationException(
      "optimistic transit stations require a cancellable birth adapter; precreate inert vehicle LPs before execution")

  def requireAccountedBirth(policy: String): Unit =
    if (policy == org.interscity.htc.core.enumeration.TimeManagerTypeEnum.TIME_WARP) requireCancellableTransitBirth()
    else throw new UnsupportedOperationException(
      s"$policy transit stations require an accounted birth/initialization adapter; precreate inert vehicle LPs before execution")
}
