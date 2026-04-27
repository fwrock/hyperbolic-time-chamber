package org.interscity.htc
package core.api

import org.interscity.htc.core.entity.configuration.Simulation

import java.util.concurrent.atomic.AtomicReference

/** Thread-safe in-memory registry for simulation configurations provided via the REST API.
  *
  * When a config is stored here it takes priority over `HTC_SIMULATION_CONFIG_FILE` /
  * `application.conf` in the source-resolution chain inside `SimulationUtil`.
  *
  * Config priority (highest → lowest):
  *   1. Direct path argument to `loadSimulationConfig(path)` 2. [[ApiConfigRegistry]] (this object)
  *      — set via `PUT /api/v1/config` 3. `htc.simulation.config-file` in `application.conf` 4.
  *      `HTC_SIMULATION_CONFIG_FILE` environment variable
  */
object ApiConfigRegistry {

  private val ref: AtomicReference[Option[Simulation]] = new AtomicReference(None)

  def set(config: Simulation): Unit = ref.set(Some(config))

  def clear(): Unit = ref.set(None)

  def get: Option[Simulation] = ref.get()

  def hasConfig: Boolean = ref.get().isDefined
}
