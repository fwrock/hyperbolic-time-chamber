package org.interscity.htc
package core.entity.configuration

/** Replaces application-wide load filtering for one scenario, including explicitly empty lists.
  *
  * Entries are trimmed when resolved by ActorLoadFilter; empty names are ignored. Exclusions take
  * precedence over inclusions, while transforms retain their listed order and duplicate entries.
  *
  * @param includeClasses
  *   optional allow-list; empty leaves class selection unrestricted apart from exclusions
  * @param excludeClasses
  *   classes removed from source declarations and loaded records
  * @param transforms
  *   implementation class names with public Config constructors
  */
case class LoadFilterSimulation(
  includeClasses: List[String] = List.empty,
  excludeClasses: List[String] = List.empty,
  transforms: List[String] = List.empty
)
