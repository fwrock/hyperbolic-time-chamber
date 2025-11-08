package org.interscity.htc
package model.hybrid.entity.state.enumeration

/** Enumeration defining the simulation mode for hybrid simulation.
  * 
  * The simulation mode determines whether a link (or region) uses:
  * - MESO: Mesoscopic simulation with aggregate speed calculations
  * - MICRO: Microscopic simulation with individual vehicle dynamics
  * 
  * The mode is set per link, and ALL vehicles entering that link
  * will adopt the link's simulation mode.
  */
enum SimulationModeEnum:
  /** Mesoscopic simulation mode - aggregate speed calculations based on link density.
    * Used for large-scale, city-wide simulations where individual vehicle dynamics
    * are not critical. More computationally efficient.
    */
  case MESO
  
  /** Microscopic simulation mode - individual vehicle dynamics with car-following models.
    * Used for detailed analysis of specific corridors, intersections, or regions where
    * vehicle-to-vehicle interactions are important. More computationally intensive.
    * 
    * In MICRO mode:
    * - Each vehicle has position, velocity, acceleration
    * - Car-following models (Krauss, IDM, etc.) determine behavior
    * - Lane-change models handle multi-lane scenarios
    * - Sub-ticks are managed by local time managers within each link
    */
  case MICRO
