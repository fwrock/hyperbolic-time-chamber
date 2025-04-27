package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.report.ReportEvent

abstract class ReportData(
  val id: String = "",
  val reportManager: ActorRef = null
) extends BaseActor[DefaultState](
      properties = Properties(
        entityId = id
      )
    ) {

  override def handleEvent: Receive = {
    case event: ReportEvent =>
      onReport(event)
    case _ =>
      logInfo("Event not handled")
  }

  protected def onReport(event: ReportEvent): Unit = {}
}
