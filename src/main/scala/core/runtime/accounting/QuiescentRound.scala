package org.interscity.htc
package core.runtime.accounting

/** A frozen cut is usable only after its complete membership supplied fresh reports and
  * every registered delivery was accepted. No elapsed-time or repeated-idle inference exists.
  */
final class QuiescentRound(val id: Long, val members: Set[String]) {
  private var frozen = Set.empty[String]
  private var reports = Map.empty[String, RuntimeSnapshot]

  def acknowledgeFrozen(member: String, round: Long): Unit =
    if (round == id && members.contains(member)) frozen += member

  def allFrozen: Boolean = frozen == members
  def report(member: String, snapshot: RuntimeSnapshot): Unit =
    if (snapshot.round == id && members.contains(member)) reports += member -> snapshot

  def complete: Boolean = reports.keySet == members
  def minimum: Option[Long] = reports.values.flatMap(_.minimum).minOption
  def blocked: Int = reports.values.map(_.blocked).sum
  def callbacks: Int = reports.values.map(_.callbacks).sum
}
