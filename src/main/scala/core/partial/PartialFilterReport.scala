package org.interscity.htc
package core.partial

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.File
import scala.util.Try

/** Derives partial-selection counts and writes the existing versioned load report.
  *
  * These reports describe startup filtering rather than modeled simulation output. Failure to write
  * a report does not change or deactivate the selected filter.
  */
private[partial] object PartialFilterReport {

  /** Counts unique retained ids by reason/class, keeping the existing 100-entry diagnostic caps. */
  def summarize(
    index: PartialActorIndex,
    selection: PartialManifest.Selection,
    result: PartialClosure.Result
  ): PartialFilter.Summary = {
    val byClass = result.kept
      .groupBy(index.classesById)
      .view
      .mapValues {
        ids =>
          countReasons(ids.toList.map(result.reasons))
      }
      .toMap
    PartialFilter.Summary(
      total = index.total,
      filterable = index.classesById.size,
      selected = selection.units.size,
      kept = result.kept.size,
      removed = index.classesById.size - result.kept.size,
      byReason = countReasons(result.reasons.values),
      byClass = byClass,
      selectedNotFound = result.selectedNotFound.toList.sorted.take(100),
      nonNumericUnits = selection.nonNumeric.take(100)
    )
  }

  /** Groups the public reason labels without changing their serialized names. */
  private def countReasons(reasons: Iterable[PartialClosure.Reason]): Map[String, Int] =
    reasons.groupBy(_.toString).view.mapValues(_.size).toMap

  /** Writes the configured path or the selection-adjacent default, returning None on write failure.
    */
  def write(settings: PartialFilter.Settings, summary: PartialFilter.Summary): Option[String] = {
    val target =
      if (settings.reportFile.nonEmpty) settings.reportFile
      else settings.selectionFile.stripSuffix(".json") + ".partial-report.json"
    Try {
      new ObjectMapper()
        .registerModule(DefaultScalaModule)
        .writerWithDefaultPrettyPrinter()
        .writeValue(new File(target), fields(settings, summary))
      target
    }.toOption
  }

  /** Keeps the v1 report keys and sorted class selection independent of internal helper types. */
  private def fields(
    settings: PartialFilter.Settings,
    summary: PartialFilter.Summary
  ): Map[String, Any] = Map(
    "schema" -> "htc.partial.report.v1",
    "selection_file" -> settings.selectionFile,
    "filterable_classes" -> settings.filterableClasses.toList.sorted,
    "actors_scanned" -> summary.total,
    "filterable_actors" -> summary.filterable,
    "selected_units" -> summary.selected,
    "kept" -> summary.kept,
    "removed" -> summary.removed,
    "kept_by_reason" -> summary.byReason,
    "kept_by_class" -> summary.byClass,
    "selected_not_found" -> summary.selectedNotFound,
    "non_numeric_units" -> summary.nonNumericUnits
  )
}
