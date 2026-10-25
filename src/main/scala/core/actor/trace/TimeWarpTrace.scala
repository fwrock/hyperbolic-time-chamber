package org.interscity.htc
package core.actor.trace

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.slf4j.LoggerFactory

/** Diagnostic tracer for the Time Warp rollback / dispatch / reply-matching path, mirroring
  * [[ActorTrace]]'s toggle-without-restart pattern but at DEBUG level instead of INFO -- this is
  * meant to be turned on only for a specific livelock-repro run, never left on in a normal run's
  * log tail (unlike [[ActorTrace]], which is INFO-level lifecycle visibility that's cheap to
  * leave on).
  *
  * Controlled by `htc.timewarp-trace.enabled` (env: `HTC_TIMEWARP_TRACE_ENABLED`, default:
  * `false`), same override precedence as every other [[SimulatorSettingsRegistry]] setting. Also
  * requires the DEBUG log level to be enabled for `org.interscity.htc.timewarp.trace` --
  * `enabled` alone only gates the (comparatively cheap) flag check; the message-formatting cost
  * is skipped unless the logger itself is at DEBUG, via [[trace]]'s `detail` being by-name.
  *
  * {{{
  *   docker logs <container> 2>&1 | grep '\[TW-TRACE\]'
  * }}}
  *
  * ==Removing all trace instrumentation from source==
  * Every call site is marked with the comment `// #timewarp-trace`.
  * {{{
  *   grep -rn '#timewarp-trace' src/
  * }}}
  */
object TimeWarpTrace {

  private val logger = LoggerFactory.getLogger("org.interscity.htc.timewarp.trace")

  /** Re-evaluated on every call so a runtime API toggle takes effect immediately. */
  def enabled: Boolean =
    logger.isDebugEnabled && SimulatorSettingsRegistry.effectiveValue("htc.timewarp-trace.enabled") == "true"

  /** Emit a structured `[TW-TRACE]` DEBUG line if tracing is enabled.
    *
    * @param actorId
    *   Entity ID of the actor (or `"LTM"`/`"GTM"` for manager-level traces) emitting the trace.
    * @param tick
    *   Current simulation tick (or the relevant target/next tick for dispatch traces).
    * @param event
    *   Short event label (e.g. `"rollback_undone_empty"`, `"dispatch_next"`, `"reply_matched"`).
    * @param detail
    *   By-name: only built if [[enabled]], so a caller can pass an interpolated string without
    *   paying its formatting cost when tracing is off.
    */
  def trace(actorId: String, tick: Long, event: String, detail: => String = ""): Unit =
    if (enabled) {
      val d = detail
      val msg =
        if (d.isEmpty) s"[TW-TRACE] $event | actor=$actorId tick=$tick"
        else s"[TW-TRACE] $event | actor=$actorId tick=$tick | $d"
      logger.debug(msg)
    }
}
