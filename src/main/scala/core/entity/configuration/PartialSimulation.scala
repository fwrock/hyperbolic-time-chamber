package org.interscity.htc
package core.entity.configuration

/** Partial-simulation block of `simulation.json`: keep only the units of a SimEDaPE selection manifest
  * (`htc.simedape.selection.v1`) and what their composition relations require. Per-run, so a running
  * engine can start a partial and a full simulation one after the other.
  *
  * @param selectionFile absolute path of the selection manifest
  * @param filterableClasses actor classes the filter may remove (`Person` or `hybrid.actor.Person`);
  *   every other class is always created
  * @param reportFile where the filter report is written; none = next to the selection file
  */
case class PartialSimulation(
  selectionFile: String,
  filterableClasses: List[String] = List.empty,
  reportFile: Option[String] = None
)
