package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.properties.{DefaultBaseProperties, Properties}
import org.interscity.htc.core.entity.event.control.report.ReportEvent

import java.time.LocalDateTime

abstract class ReportData(
  val id: String = "",
  val reportManager: ActorRef = null,
  val startRealTime: LocalDateTime
) extends BaseActor[DefaultState](
      properties = DefaultBaseProperties(
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
