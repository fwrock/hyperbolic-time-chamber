package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.enumeration.LoadBalanceStrategyEnum

/** Factory for creating balancing strategies based on configuration. */
object StrategyFactory {

  /** Creates a BalancingStrategy from the enum value.
    *
    * @param strategyType
    *   The strategy type from configuration
    * @return
    *   A new strategy instance, or None for Disabled
    */
  def create(strategyType: LoadBalanceStrategyEnum): Option[BalancingStrategy] =
    strategyType match {
      case LoadBalanceStrategyEnum.Hybrid  => Some(new HybridStrategy())
      case LoadBalanceStrategyEnum.Default => Some(new DefaultStrategy())
      case LoadBalanceStrategyEnum.Disabled => None
    }

  /** Creates a BalancingStrategy from a string name.
    *
    * @param name
    *   Strategy name: "hybrid", "default", "disabled"
    * @return
    *   A new strategy instance, or None for disabled/unknown
    */
  def create(name: String): Option[BalancingStrategy] = {
    val normalized = name.trim.toLowerCase
    normalized match {
      case "hybrid"   => create(LoadBalanceStrategyEnum.Hybrid)
      case "default"  => create(LoadBalanceStrategyEnum.Default)
      case "disabled" => create(LoadBalanceStrategyEnum.Disabled)
      case _ =>
        // Unknown strategy, fall back to disabled
        None
    }
  }
}
