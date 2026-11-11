package org.interscity.htc
package core.entity.configuration

/** A present block replaces the application-wide load filter for this scenario, including an empty block. */
case class LoadFilterSimulation(
  includeClasses: List[String] = List.empty,
  excludeClasses: List[String] = List.empty,
  transforms: List[String] = List.empty
)
