package org.interscity.htc
package core.actor.trace

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.slf4j.LoggerFactory

/** Lightweight actor lifecycle tracer.
  *
  * Controlled by `htc.actor-trace.enabled` (env: `HTC_ACTOR_TRACE_ENABLED`, default: `false`).
  * When enabled, emits INFO lines with the `[ACTOR-TRACE]` prefix via the `org.interscity.htc.actor.trace`
  * logger, making them easy to filter in container logs:
  *
  * {{{
  *   docker logs <container> 2>&1 | grep '\[ACTOR-TRACE\]'
  * }}}
  *
  * Toggle at runtime (no restart needed) via the REST API:
  * {{{
  *   PUT /api/v1/settings  {"htc.actor-trace.enabled": "true"}
  * }}}
  *
  * ==Removing all trace instrumentation from source==
  * Every call site is marked with the comment `// #actor-trace`.
  * To remove all of them at once:
  * {{{
  *   grep -rn '#actor-trace' src/
  * }}}
  * Then delete each flagged line.
  */
object ActorTrace {

  private val logger = LoggerFactory.getLogger("org.interscity.htc.actor.trace")

  /** Returns `true` when actor tracing is active.
    *
    * Re-evaluated on every call so that runtime API overrides (and env var changes at startup)
    * take effect immediately without restarting the JVM.
    */
  def enabled: Boolean =
    SimulatorSettingsRegistry.effectiveValue("htc.actor-trace.enabled") == "true"

  /** Emit a structured trace line if tracing is enabled.
    *
    * @param actorId
    *   Entity ID of the actor emitting the trace.
    * @param tick
    *   Current simulation tick.
    * @param event
    *   Short event label (e.g. `"journey_started"`, `"route_ok"`, `"passengers_loaded"`).
    * @param detail
    *   Optional extra context in `key=value` format or free text.
    */
  def trace(actorId: String, tick: Long, event: String, detail: String = ""): Unit =
    if (enabled) {
      val msg =
        if (detail.isEmpty) s"[ACTOR-TRACE] $event | actor=$actorId tick=$tick"
        else s"[ACTOR-TRACE] $event | actor=$actorId tick=$tick | $detail"
      logger.info(msg)
    }
}
