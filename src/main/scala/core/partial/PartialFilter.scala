package org.interscity.htc
package core.partial

import core.entity.actor.ActorSimulation
import core.entity.configuration.ActorDataSource
import core.modifications.ScenarioModifications

import com.typesafe.config.Config

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Partial-simulation filter applied while the scenario is loaded.
  *
  * Off unless `htc.partial.selection-file` is set. When on, `LoadDataManager` first scans every
  * actor source (ids and relations only), computes the kept set with [[PartialClosure]], and stores
  * it here; the loaders then drop, before creating anything, the actors of a *filterable* class
  * that are not kept. Actors of any other class (nodes, links, signals...) are never touched.
  *
  * The state is per JVM and set once before any actor is created. The loaders run in the same JVM
  * as the load manager (they are created by it); a loader that finds the filter enabled but not
  * prepared fails instead of silently loading everything.
  */
object PartialFilter {

  /** Per-run selection configuration, kept nested to preserve its public JVM identity.
    *
    * @param selectionFile
    *   selection manifest; empty = filter disabled
    * @param filterableClasses
    *   actor classes the filter applies to (`Person` or `hybrid.actor.Person`)
    * @param reportFile
    *   where the report is written; empty = next to the selection file
    */
  final case class Settings(
    selectionFile: String,
    filterableClasses: Set[String],
    reportFile: String
  ) {

    /** Indicates whether this configuration requests partial selection. */
    def enabled: Boolean = selectionFile.nonEmpty
  }

  /** Reads list/CSV class selection, treating absent or invalid configuration values as empty. */
  def settings(config: Config): Settings = {
    val classes = Try(config.getStringList("htc.partial.filterable-classes").asScala.toList)
      .getOrElse(
        Try(config.getString("htc.partial.filterable-classes").split(",").toList).getOrElse(Nil)
      )
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
    Settings(
      configuredText(config, "htc.partial.selection-file"),
      classes,
      configuredText(config, "htc.partial.report-file")
    )
  }

  /** Startup counts and bounded diagnostics, kept nested to preserve the existing public type.
    *
    * Total counts transformed records; filterable/kept/removed count unique filterable ids.
    * Selected counts numeric manifest ids, including missing ids, and excludes implicit selection
    * of added actors. Missing numeric ids are sorted and capped at 100; nonnumeric ids retain
    * manifest order and are capped at 100.
    */
  final case class Summary(
    total: Int,
    filterable: Int,
    selected: Int,
    kept: Int,
    removed: Int,
    byReason: Map[String, Int],
    byClass: Map[String, Map[String, Int]],
    selectedNotFound: List[Long],
    nonNumericUnits: List[String]
  )

  /** Publishes a completed selection without retaining source payloads or graph indexes. */
  private final class Active(val kept: Set[Long], val isFilterable: String => Boolean)
  @volatile private var active: Option[Active] = None
  @volatile private var expected = false

  /** Indicates whether loaders must apply the current run's partial filter. */
  def enabled: Boolean = expected

  /** Indicates whether a selection has been published for subsequent loader lookups. */
  def prepared: Boolean = active.isDefined

  /** Drops the not-kept actors of filterable classes. Disabled: returns the list untouched. */
  def filter(actors: List[ActorSimulation]): List[ActorSimulation] =
    if (!expected) actors
    else {
      val a = active.getOrElse(
        throw new IllegalStateException(
          "partial filter is enabled but was not prepared before loading actors"
        )
      )
      actors.filter(
        actor => !a.isFilterable(actor.typeActor) || a.kept.contains(actor.id)
      )
    }

  /** Matches exact class names or dotted suffixes without requiring a common package prefix. */
  def matches(filterable: Set[String]): String => Boolean =
    typeActor =>
      filterable.exists(
        f => typeActor == f || typeActor.endsWith("." + f)
      )

  /** Scans transformed sources and publishes a valid selection before loaders begin.
    *
    * Configuration, manifest and composition errors return Left. Source I/O or transformation
    * failures propagate. An unsuccessful attempt leaves any previously published filter intact;
    * callers must clear per-run state before preparing a different run.
    */
  def prepare(sources: List[ActorDataSource], s: Settings): Either[String, Summary] = {
    if (!s.enabled) return Left("partial filter is not enabled")
    if (s.filterableClasses.isEmpty)
      return Left(
        "htc.partial.selection-file is set but htc.partial.filterable-classes is empty: the filter would keep everything"
      )
    val manifest = Try(PartialManifest.read(s.selectionFile)).toEither.left.map(
      e => s"cannot read selection ${s.selectionFile}: ${e.getMessage}"
    )
    manifest.flatMap {
      selection =>
        val isFilterable = matches(s.filterableClasses)
        val index = PartialSourceScanner.scan(sources, isFilterable)
        val added = ScenarioModifications.addedIds.filter(index.classesById.contains)
        val result =
          PartialClosure.compute(index.classesById.keySet, index.links, selection.units ++ added)
        if (result.problems.nonEmpty) Left(compositionError(result.problems))
        else {
          active = Some(new Active(result.kept, isFilterable))
          expected = true
          Right(PartialFilterReport.summarize(index, selection, result))
        }
    }
  }

  /** Formats at most ten composition diagnostics while retaining the count of omitted problems. */
  private def compositionError(problems: List[String]): String =
    "invalid composition relations: " + problems.take(10).mkString("; ") +
      (if (problems.size > 10) s" (+${problems.size - 10} more)" else "")

  /** Writes the startup report without changing the published selection; None indicates failure. */
  def writeReport(s: Settings, summary: Summary): Option[String] =
    PartialFilterReport.write(s, summary)

  /** Trims scalar settings while preserving the legacy empty fallback for configuration errors. */
  private def configuredText(config: Config, path: String): String =
    Try(config.getString(path)).getOrElse("").trim

  /** Switches the filter off. Called at the start of every load that has no partial block: the
    * state is per JVM and an engine can run a partial simulation and then a full one.
    */
  def clear(): Unit = { active = None; expected = false }

  /** Settings from a simulation.json `partial` block. */
  def settings(partial: core.entity.configuration.PartialSimulation): Settings =
    Settings(
      partial.selectionFile.trim,
      partial.filterableClasses.map(_.trim).filter(_.nonEmpty).toSet,
      partial.reportFile.getOrElse("")
    )

  /** Resets run-local state for package-level callers using the legacy reset entry point. */
  private[partial] def reset(): Unit = clear()
}
