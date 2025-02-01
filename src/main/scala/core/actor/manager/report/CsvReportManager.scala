package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.entity.event.BaseEvent

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Props
import org.interscity.htc.core.entity.state.DefaultState

import java.io.{ BufferedWriter, FileWriter }
import scala.collection.mutable

class CsvReportManager(timeManager: ActorRef)
    extends BaseActor[DefaultState](timeManager = timeManager) {

  override def handleEvent: Receive = {
    case eventReport: BaseEvent[_] =>
      buffer += eventReport
      if (buffer.size >= batchSize) {
        flushBuffer()
      }
  }

  private val config = context.system.settings.config.getConfig("report-manager.csv")
  private val directory = config.getString("directory")
  private val batchSize = 100

  private val buffer = mutable.ListBuffer[BaseEvent[_]]()
  private var fileWriter: Option[BufferedWriter] = None

  private def flushBuffer(): Unit = {
    val filePath = s"$directory/report_${System.currentTimeMillis()}.csv"
    val writer = new BufferedWriter(new FileWriter(filePath, true))
    buffer.foreach {
      report =>
        // writer.write(s"${report.getTick},${report.getData.values.mkString(",")}\n")
    }
    writer.close()
    buffer.clear()
  }

  override def postStop(): Unit =
    if (buffer.nonEmpty) {
      flushBuffer()
    }
}
