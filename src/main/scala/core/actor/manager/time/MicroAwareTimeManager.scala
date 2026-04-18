package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.TimeManagerBase
import core.types.Tick
import model.hybrid.entity.event.data.GlobalTickEvent

import org.apache.pekko.actor.ActorRef

/** Mixin trait for time managers that need to coordinate microscopic simulation.
  *
  * Provides functionality to:
  *   - Track micro-enabled links (links in MICRO mode)
  *   - Trigger sub-tick execution on micro links
  *   - Synchronize micro links with global time
  *
  * Architecture:
  *   1. Micro links register themselves with LocalTimeManager 2. At each global tick,
  *      LocalTimeManager triggers all micro links 3. Each link executes its sub-ticks locally via
  *      LinkMicroTimeManager 4. Links report completion back to LocalTimeManager 5.
  *      LocalTimeManager advances to next tick after all links complete
  *
  * This design provides spatial locality (vehicles on same link stay together) and reduces message
  * passing to global time manager.
  */
trait MicroAwareTimeManager extends TimeManagerBase {

  /** Set of micro-enabled link actor references. These links run microscopic simulation with
    * sub-ticks.
    */
  private val microLinks = scala.collection.mutable.Set[ActorRef]()

  /** Register a link as micro-enabled.
    *
    * @param linkRef
    *   The actor reference of the micro link
    */
  def registerMicroLink(linkRef: ActorRef): Unit = {
    microLinks.add(linkRef)
    logInfo(s"Registered micro link: ${linkRef.path.name}")
  }

  /** Unregister a micro link.
    *
    * @param linkRef
    *   The actor reference of the micro link
    */
  def unregisterMicroLink(linkRef: ActorRef): Unit = {
    microLinks.remove(linkRef)
    logInfo(s"Unregistered micro link: ${linkRef.path.name}")
  }

  /** Trigger all micro links to execute sub-ticks for the current global tick.
    *
    * @param tick
    *   The current global tick
    */
  protected def triggerMicroLinks(tick: Tick): Unit =
    if (microLinks.nonEmpty) {
      logInfo(s"[MicroTM] Triggering ${microLinks.size} micro links for global tick $tick")

      microLinks.foreach {
        linkRef =>
          // Send global tick event to link
          linkRef ! GlobalTickEvent(tick = tick)
      }
    }

  /** Get the number of registered micro links.
    *
    * @return
    *   The count of micro-enabled links
    */
  def getMicroLinkCount: Int = microLinks.size

  /** Check if there are any micro links registered.
    *
    * @return
    *   true if at least one micro link is registered
    */
  def hasMicroLinks: Boolean = microLinks.nonEmpty
}
