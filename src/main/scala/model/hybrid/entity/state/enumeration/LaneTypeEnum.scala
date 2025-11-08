package org.interscity.htc
package model.hybrid.entity.state.enumeration

/** Enumeration defining the type of lane in microscopic simulation.
  * 
  * Lane types determine which vehicles can use a specific lane and
  * affect lane-change decisions.
  */
enum LaneTypeEnum:
  /** Normal lane - all vehicles can use */
  case NORMAL
  
  /** Bus lane - restricted to buses and sometimes taxis */
  case BUS_LANE
  
  /** Bike lane - dedicated for bicycles */
  case BIKE_LANE
  
  /** HOV (High Occupancy Vehicle) lane - cars with multiple passengers */
  case HOV_LANE
  
  /** Emergency lane / shoulder */
  case EMERGENCY
