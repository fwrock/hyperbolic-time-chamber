package org.interscity.htc
package core.enumeration

import core.actor.manager.report.{ CsvReportData, ReportData }

enum ReportTypeEnum(val clazz: Class[? <: ReportData]) {
  case csv extends ReportTypeEnum(classOf[CsvReportData])
  case json extends ReportTypeEnum(classOf[ReportData])
  case cassandra extends ReportTypeEnum(classOf[ReportData])
}
