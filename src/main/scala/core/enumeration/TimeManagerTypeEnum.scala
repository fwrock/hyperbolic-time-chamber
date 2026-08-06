package org.interscity.htc
package core.enumeration

/** Enumeration of available time manager types in the simulation. These types define different
  * strategies for managing simulation time.
  */
object TimeManagerTypeEnum {

  /** Discrete Event Simulation - processes events in chronological order, advancing time only when
    * all events at current time are completed. This is the default and original time management
    * strategy.
    */
  val DISCRETE_EVENT = "discrete-event"

  /** Time-Stepped Simulation - advances time in fixed intervals, processing all actors at each time
    * step regardless of scheduled events. Useful for continuous simulations requiring regular
    * updates.
    */
  val TIME_STEPPED = "time-stepped"

  /** Time Warp (optimistic synchronization with rollback) - actors run ahead without waiting for
    * a global barrier; causality is restored via checkpoint/replay rollback instead of being
    * guaranteed up front. See `docs/TIME_WARP_DESIGN.md`. Not yet wired into the config-driven
    * factories that would let a scenario actually select it (`GlobalTimeManagerBase.
    * localTimeManagerProps`, `SimulationManager.createSingletonTimeManager`,
    * `SimulationBaseActor.onInitialize`'s `currentTimeManagerType` assignment all still hardcode
    * `DISCRETE_EVENT`/`ConservativeGlobalTimeManager`) — added here so `OptimisticLocalTimeManager`/
    * `OptimisticGlobalTimeManager` have a type string to report themselves under.
    */
  val TIME_WARP = "time-warp"

  /** All available time manager types */
  val ALL_TYPES: Set[String] = Set(DISCRETE_EVENT, TIME_STEPPED, TIME_WARP)

  /** Validates if a time manager type is supported.
    * @param timeManagerType
    *   The type to validate
    * @return
    *   true if supported, false otherwise
    */
  def isValid(timeManagerType: String): Boolean =
    ALL_TYPES.contains(timeManagerType)

  /** Gets the default time manager type.
    * @return
    *   The default type (discrete-event)
    */
  def getDefault: String = DISCRETE_EVENT
}
