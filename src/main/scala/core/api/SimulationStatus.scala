package org.interscity.htc
package core.api

enum SimulationStatus:
  /** API mode enabled and waiting for POST /api/v1/simulation/start */
  case Idle
  /** PrepareSimulationEvent sent, actors being loaded */
  case Loading
  /** Simulation ticks advancing */
  case Running
  /** PauseSimulationEvent sent, ticks halted */
  case Paused
  /** StopSimulationEvent sent or simulation ended naturally */
  case Stopped
