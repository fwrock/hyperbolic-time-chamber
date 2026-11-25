package org.interscity.htc
package core.runtime

/** A wake-up an actor lets another actor schedule for it.
  *
  * `revision` is reserved by the owner ahead of its own `FinishEvent`, so it is strictly greater
  * than any schedule revision the owner emits for this request. The time manager applies schedule
  * revisions per actor monotonically, hence the delegate's schedule can never be overwritten by the
  * owner's earlier finish, whichever of the two reaches the time manager first.
  */
final case class DelegatedWake(model: String, id: Long, revision: Long)
