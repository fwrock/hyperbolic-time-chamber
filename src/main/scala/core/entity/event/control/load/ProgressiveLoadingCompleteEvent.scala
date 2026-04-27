package org.interscity.htc
package core.entity.event.control.load

/** Sent by ProgressiveLoadDataManager back to the SimulationManager (or LoadDataManager) to signal
  * that all progressive sources have been fully exhausted (all actors from all progressive files
  * have been created).
  */
case class ProgressiveLoadingCompleteEvent(
  totalActorsCreated: Long
)
