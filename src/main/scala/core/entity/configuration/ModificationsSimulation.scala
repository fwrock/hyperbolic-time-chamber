package org.interscity.htc
package core.entity.configuration

/** `modifications` block of `simulation.json`: what-if changes applied while loading, before the partial filter.
  * Files are `htc.modifications.v1` (see `simedape/docs/MODIFICATIONS.md`) and are applied in the order listed.
  *
  * @param files absolute paths (as the engine sees them) of the modification files
  */
case class ModificationsSimulation(files: List[String] = List.empty)
