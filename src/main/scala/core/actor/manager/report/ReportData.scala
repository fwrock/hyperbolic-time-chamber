package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef

abstract class ReportData(
  val id: String = "",
  val reportManager: ActorRef = null,
                         ) extends BaseActor[DefaultState](
  actorId = id
) {
}