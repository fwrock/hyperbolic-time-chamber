package org.interscity.htc
package core.api

import org.apache.pekko.actor.ActorSystem
import org.htc.protobuf.core.entity.event.control.execution.{ PauseSimulationEvent, PrepareSimulationEvent, ResumeSimulationEvent, StopSimulationEvent }
import org.interscity.htc.core.metrics.MetricsServer
import org.interscity.htc.core.util.DistributedUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, SIMULATION_MANAGER_ACTOR_NAME }
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

/** Global controller for simulation lifecycle, accessible from API routes.
  *
  * Lifecycle events are forwarded to the appropriate Pekko singletons via cluster singleton
  * proxies. The controller tracks the high-level simulation status and uses
  * [[MetricsServer.currentTick]] to infer when loading transitions to running.
  *
  * Status transitions: Idle ──(start)──▶ Loading ──(tick > 0)──▶ Running │ ▲ (pause)(resume) ▼
  * Paused Any ──(stop)──▶ Stopped
  */
object SimulationController {

  private val logger = LoggerFactory.getLogger(getClass)

  private val _system = new AtomicReference[Option[ActorSystem]](None)
  private val _status = new AtomicReference[SimulationStatus](SimulationStatus.Idle)
  private var _apiWaitEnabled = false

  /** Called once from [[ConfigApiServer]] right after the ActorSystem is created.
    *
    * @param apiEnabled
    *   When true, [[SimulationManager]] will NOT auto-start; the simulation waits for POST
    *   /api/v1/simulation/start. When false, status is immediately set to Running to reflect that
    *   the auto-start flow is in progress.
    */
  def initialize(system: ActorSystem, apiEnabled: Boolean): Unit = {
    _system.set(Some(system))
    _apiWaitEnabled = apiEnabled
    if (!apiEnabled) _status.set(SimulationStatus.Running)
  }

  def isApiWaitEnabled: Boolean = _apiWaitEnabled

  /** Current simulation status. When in Loading state, transitions to Running automatically once
    * the first tick is recorded in Prometheus (avoids needing to modify SimulationManager).
    */
  def status: SimulationStatus = {
    val s = _status.get()
    if (s == SimulationStatus.Loading && MetricsServer.currentTick.get() > 0)
      SimulationStatus.Running
    else s
  }

  /** Trigger simulation start. Applies any pending settings overrides, then sends
    * [[PrepareSimulationEvent]] to the SimulationManager singleton.
    *
    * @param configFile
    *   Optional path to a simulation JSON file. When absent, the configuration is resolved from
    *   [[ApiConfigRegistry]] or the normal file/env-var chain.
    * @param settings
    *   Optional htc.* settings overrides applied before start.
    */
  def start(
    configFile: Option[String] = None,
    settings: Map[String, String] = Map.empty
  ): Either[String, Unit] = {
    if (_status.get() != SimulationStatus.Idle)
      return Left(s"Cannot start: simulation is already ${_status.get()}")

    if (settings.nonEmpty) SimulatorSettingsRegistry.setAll(settings)

    withSystem {
      sys =>
        val proxy = DistributedUtil.createSingletonProxy(sys, SIMULATION_MANAGER_ACTOR_NAME)
        val configArg = configFile.filter(_.nonEmpty).orNull
        proxy ! PrepareSimulationEvent(configuration = configArg)
        _status.set(SimulationStatus.Loading)
        logger.info(
          s"Simulation start triggered via API" +
            configFile
              .filter(_.nonEmpty)
              .map(
                f => s" (configFile=$f)"
              )
              .getOrElse("") +
            (if (settings.nonEmpty) s", ${settings.size} setting(s) overridden" else "")
        )
    }
  }

  def pause(): Either[String, Unit] = {
    if (status != SimulationStatus.Running)
      return Left(s"Cannot pause: simulation is $status")
    withSystem {
      sys =>
        DistributedUtil.createSingletonProxy(
          sys,
          GLOBAL_TIME_MANAGER_ACTOR_NAME
        ) ! PauseSimulationEvent()
        _status.set(SimulationStatus.Paused)
        logger.info("Simulation paused via API")
    }
  }

  def resume(): Either[String, Unit] = {
    if (_status.get() != SimulationStatus.Paused)
      return Left(s"Cannot resume: simulation is ${_status.get()}")
    withSystem {
      sys =>
        DistributedUtil.createSingletonProxy(
          sys,
          GLOBAL_TIME_MANAGER_ACTOR_NAME
        ) ! ResumeSimulationEvent()
        _status.set(SimulationStatus.Running)
        logger.info("Simulation resumed via API")
    }
  }

  def stop(): Either[String, Unit] = {
    if (_status.get() == SimulationStatus.Idle || _status.get() == SimulationStatus.Stopped)
      return Left(s"Cannot stop: simulation is ${_status.get()}")
    withSystem {
      sys =>
        DistributedUtil.createSingletonProxy(
          sys,
          SIMULATION_MANAGER_ACTOR_NAME
        ) ! StopSimulationEvent()
        _status.set(SimulationStatus.Stopped)
        logger.info("Simulation stopped via API")
    }
  }

  private def withSystem(f: ActorSystem => Unit): Either[String, Unit] =
    _system.get() match {
      case None => Left("Actor system not yet initialized")
      case Some(sys) =>
        try { f(sys); Right(()) }
        catch { case e: Exception => Left(s"Internal error: ${e.getMessage}") }
    }
}
