package org.interscity.htc.core.system.dispatcher

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.ExecutionContext

/** Opt-in accessor for the dedicated `pekko.routing-dispatcher`.
  *
  * The dispatcher is configured in `vm.conf` (and may be added to other deployment
  * profiles). It exists to isolate CPU-heavy route computation (A-star / ALT / CH)
  * from the `default-dispatcher` so a long routing call does not delay the rest of
  * the simulation actors.
  *
  * Usage (opt-in, no existing caller is changed):
  * 
  * {{{
  *   import org.apache.pekko.pattern.pipe
  *   import org.interscity.htc.core.system.dispatcher.RoutingDispatcher
  *   import scala.concurrent.{ ExecutionContext, Future }
  *
  *   implicit val routingEc: ExecutionContext = RoutingDispatcher.executionContext(context.system)
  *
  *   Future {
  *     GPSUtil.calcRouteCompact(originId, destinationId)
  *   }.pipeTo(self)
  * }}}
  *
  * Notes:
  *   - If the configured id is not present (e.g. running with a profile that does
  *     not override it), Pekko falls back to the default dispatcher. We catch that
  *     and fall back to the system dispatcher explicitly so callers never break.
  *   - Do NOT use this dispatcher to host actors via `Props.withDispatcher` unless
  *     you have validated it does not cause sharding/cluster interactions issues.
  */
object RoutingDispatcher {

  /** Configured id of the routing dispatcher. */
  val Id: String = "pekko.routing-dispatcher"

  /** Returns an [[ExecutionContext]] backed by `pekko.routing-dispatcher` when
    * configured, otherwise the default dispatcher of the given system.
    */
  def executionContext(system: ActorSystem): ExecutionContext =
    if (system.settings.config.hasPath(Id))
      system.dispatchers.lookup(Id)
    else
      system.dispatcher
}
