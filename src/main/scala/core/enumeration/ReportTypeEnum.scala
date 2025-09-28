package org.interscity.htc
package core.enumeration

import org.interscity.htc.core.actor.manager.report.{ CassandraReportData, CsvReportData, JsonReportData, ReportData }

enum ReportTypeEnum(val clazz: Class[? <: ReportData]) {
  case csv extends ReportTypeEnum(classOf[CsvReportData])
  case json extends ReportTypeEnum(classOf[JsonReportData])
  case cassandra extends ReportTypeEnum(classOf[CassandraReportData])
}
