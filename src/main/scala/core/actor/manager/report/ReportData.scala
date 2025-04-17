package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.entity.state.DefaultState

abstract class ReportData(
  val id: String = ""
                         ) extends BaseActor[DefaultState](
  actorId = id
) {
}