package org.interscity.htc
package core.enumeration

import org.interscity.htc.core.actor.manager.report.{ ClickHouseReportData, CsvReportData, JsonReportData, ParquetReportData, ReportData }

enum ReportTypeEnum(val clazz: Class[? <: ReportData]) {
  case csv extends ReportTypeEnum(classOf[CsvReportData])
  case json extends ReportTypeEnum(classOf[JsonReportData])
  case clickhouse extends ReportTypeEnum(classOf[ClickHouseReportData])
  case parquet extends ReportTypeEnum(classOf[ParquetReportData])
}
