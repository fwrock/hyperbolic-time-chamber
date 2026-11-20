package org.interscity.htc
package core.runtime.conservative.partition

import scala.collection.mutable

/** Actor-confined admission directory. Only distinct wake-ups advance a tick's counter. Claimed
  * keys remain until a strictly later globally authorized tick.
  */
final class TickScheduleAssignments(workerCount: Int) {
  require(workerCount > 0, "schedule worker count must be positive")
  private case class Entry(
    wake: TickWake,
    owner: Int,
    var accepted: Boolean = false,
    var claimed: Boolean = false
  )
  private val entries = mutable.Map.empty[(Long, Long), Entry]
  private val retainedTicks = mutable.TreeMap.empty[Long, mutable.Set[Long]]
  private val ticks = mutable.TreeMap.empty[Long, mutable.LinkedHashSet[Long]]
  private val counts = mutable.LongMap.empty[mutable.Map[Int, Int]]
  private val sequences = mutable.LongMap.empty[Long]
  private var pendingAcceptance = 0
  private var frontier = Long.MinValue

  /** Return an existing assignment for a repeated wake-up, or allocate a new one. */
  def admit(wake: TickWake): (Int, Boolean) = {
    require(wake.tick >= frontier, "wake-up below reclaimed frontier")
    val key = wake.identity.id -> wake.tick
    entries.get(key) match {
      case Some(entry) =>
        require(entry.wake == wake, "conflicting wake-up identity")
        entry.owner -> false
      case None =>
        val sequence = sequences.getOrElse(wake.tick, 0L)
        val owner = (sequence % workerCount).toInt
        sequences(wake.tick) = Math.addExact(sequence, 1L)
        entries(key) = Entry(wake, owner)
        retainedTicks.getOrElseUpdate(wake.tick, mutable.Set.empty).add(wake.identity.id)
        ticks.getOrElseUpdate(wake.tick, mutable.LinkedHashSet.empty).add(wake.identity.id)
        val byOwner = counts.getOrElseUpdate(wake.tick, mutable.Map.empty)
        byOwner(owner) = byOwner.getOrElse(owner, 0) + 1
        pendingAcceptance += 1
        owner -> true
    }
  }

  /** A worker's receipt establishes storage, without requiring execution. */
  def accept(wake: TickWake, owner: Int): Unit = {
    val entry = entries(wake.identity.id -> wake.tick)
    require(entry.owner == owner && entry.wake == wake, "wrong wake-up acceptance")
    if (!entry.accepted) {
      pendingAcceptance -= 1
      entry.accepted = true
    }
  }

  /** Only the recorded worker can claim an accepted event, once. */
  def claim(wake: TickWake, owner: Int): Unit = {
    val entry = entries(wake.identity.id -> wake.tick)
    require(
      entry.owner == owner && entry.wake == wake && entry.accepted && !entry.claimed,
      "invalid wake-up claim"
    )
    entry.claimed = true
    ticks(wake.tick).remove(wake.identity.id)
    if (ticks(wake.tick).isEmpty) ticks.remove(wake.tick)
    val byOwner = counts(wake.tick)
    val remaining = byOwner(owner) - 1
    if (remaining == 0) byOwner.remove(owner) else byOwner(owner) = remaining
    if (byOwner.isEmpty) counts.remove(wake.tick)
  }

  /** The owner is stable through queued, running and completed states. */
  def owner(wake: TickWake): Int = entries(wake.identity.id -> wake.tick).owner

  /** Pending acceptance fences registration and closure. */
  def hasPendingAcceptance: Boolean = pendingAcceptance != 0

  /** Admitted wake-ups whose storage receipt has not arrived (diagnostics). */
  def pendingAcceptanceCount: Int = pendingAcceptance

  /** Ordered summaries use the authoritative directory, including unacknowledged events. */
  def earliest: Vector[TickWake] = ticks.headOption.toVector.flatMap {
    case (tick, ids) =>
      ids.headOption.map(
        id => entries(id -> tick).wake
      )
  }

  /** Workers with queued events for the authorized tick. */
  def dueOwners(tick: Long): Set[Int] = counts.get(tick).fold(Set.empty[Int])(_.keySet.toSet)

  /** Old identities can be reclaimed only at a globally authorized advance. */
  def advance(tick: Long): Unit = {
    require(tick >= frontier, "schedule frontier regressed")
    val old = retainedTicks.keysIterator.takeWhile(_ < tick).toVector
    require(
      old.forall(
        t =>
          retainedTicks(t).forall {
            id =>
              val entry = entries(id -> t)
              entry.claimed && entry.accepted
          }
      ),
      "advance with unsettled scheduled events"
    )
    old.foreach {
      t =>
        retainedTicks
          .remove(t)
          .get
          .foreach(
            id => entries.remove(id -> t)
          )
        sequences.remove(t)
    }
    frontier = tick
  }
}
