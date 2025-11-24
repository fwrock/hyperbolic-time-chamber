package org.interscity.htc
package core.enumeration

import org.interscity.htc.core.actor.manager.report.{ CsvReportData, JsonReportData, ReportData }

enum ReportTypeEnum(val clazz: Class[? <: ReportData]) {
  case csv extends ReportTypeEnum(classOf[CsvReportData])
  case json extends ReportTypeEnum(classOf[JsonReportData])
  // DEPRECATED: Cassandra support removed - use JSON or CSV instead
  // CassandraReportData code moved to .deprecated file
}
