package org.interscity.htc
package core.partial

import core.entity.actor.ActorSimulation
import core.entity.configuration.ActorDataSource
import core.enumeration.DataSourceTypeEnum
import core.util.{ JsonStreamingUtil, SqliteActorSimulationUtil }

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.Config

import java.io.{ BufferedInputStream, File, FileInputStream }
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Partial-simulation filter applied while the scenario is loaded.
  *
  * Off unless `htc.partial.selection-file` is set. When on, `LoadDataManager` first scans every
  * actor source (ids and relations only), computes the kept set with [[PartialClosure]], and stores
  * it here; the loaders then drop, before creating anything, the actors of a *filterable* class that
  * are not kept. Actors of any other class (nodes, links, signals...) are never touched.
  *
  * The state is per JVM and set once before any actor is created. The loaders run in the same JVM
  * as the load manager (they are created by it); a loader that finds the filter enabled but not
  * prepared fails instead of silently loading everything.
  */
object PartialFilter {

  /** @param selectionFile selection manifest; empty = filter disabled
    * @param filterableClasses actor classes the filter applies to (`Person` or `hybrid.actor.Person`)
    * @param reportFile where the report is written; empty = next to the selection file
    */
  final case class Settings(selectionFile: String, filterableClasses: Set[String], reportFile: String) {
    def enabled: Boolean = selectionFile.nonEmpty
  }

  def settings(config: Config): Settings = {
    def str(path: String) = Try(config.getString(path)).getOrElse("").trim
    val classes = Try(config.getStringList("htc.partial.filterable-classes").asScala.toList)
      .getOrElse(Try(config.getString("htc.partial.filterable-classes").split(",").toList).getOrElse(Nil))
      .map(_.trim).filter(_.nonEmpty).toSet
    Settings(str("htc.partial.selection-file"), classes, str("htc.partial.report-file"))
  }

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

  private final class Active(val kept: Set[Long], val isFilterable: String => Boolean)
  @volatile private var active: Option[Active] = None
  @volatile private var expected = false

  def enabled: Boolean = expected
  def prepared: Boolean = active.isDefined

  /** Drops the not-kept actors of filterable classes. Disabled: returns the list untouched. */
  def filter(actors: List[ActorSimulation]): List[ActorSimulation] =
    if (!expected) actors
    else {
      val a = active.getOrElse(
        throw new IllegalStateException("partial filter is enabled but was not prepared before loading actors")
      )
      actors.filter(actor => !a.isFilterable(actor.typeActor) || a.kept.contains(actor.id))
    }

  def matches(filterable: Set[String]): String => Boolean =
    typeActor => filterable.exists(f => typeActor == f || typeActor.endsWith("." + f))

  /** Scans every source, computes the kept set and activates the filter. `Left` = the load must fail. */
  def prepare(sources: List[ActorDataSource], s: Settings): Either[String, Summary] = {
    if (!s.enabled) return Left("partial filter is not enabled")
    if (s.filterableClasses.isEmpty)
      return Left("htc.partial.selection-file is set but htc.partial.filterable-classes is empty: the filter would keep everything")
    val manifest = Try(PartialManifest.read(s.selectionFile)).toEither.left.map(e => s"cannot read selection ${s.selectionFile}: ${e.getMessage}")
    manifest.flatMap { selection =>
      val isFilterable = matches(s.filterableClasses)
      val classOf = mutable.HashMap.empty[Long, String]
      val links = mutable.ArrayBuffer.empty[(Long, Long)]
      var total = 0
      sources.foreach { source =>
        val path = source.dataSource.info("path").asInstanceOf[String]
        val (closeable, iterator): (AutoCloseable, Iterator[ActorSimulation]) =
          source.dataSource.sourceType match {
            case DataSourceTypeEnum.json =>
              val is = new BufferedInputStream(new FileInputStream(new File(path)))
              (is, JsonStreamingUtil.createParser(is)._2)
            case DataSourceTypeEnum.sqlite => SqliteActorSimulationUtil.openIterator(path)
          }
        try iterator.foreach { raw =>
          // the universe the run will really have: removed actors are gone, edits applied (dependencies may change)
          core.modifications.ScenarioModifications.transform(raw).flatMap(core.actor.manager.load.ActorLoadFilter.transform(_)).foreach { actor =>
            total += 1
            if (isFilterable(actor.typeActor)) {
              classOf(actor.id) = actor.typeActor
              links ++= PartialClosure.linksOf(actor)
            }
          }
        } finally closeable.close()
      }
      // a relation to an actor that is not filterable is not a composition relation of this filter
      val result = PartialClosure.compute(classOf.keySet, links, selection.units ++ core.modifications.ScenarioModifications.addedIds.filter(classOf.contains)) // added actors are always kept
      if (result.problems.nonEmpty)
        Left("invalid composition relations: " + result.problems.take(10).mkString("; ") +
          (if (result.problems.size > 10) s" (+${result.problems.size - 10} more)" else ""))
      else {
        active = Some(new Active(result.kept, isFilterable))
        expected = true
        val reasonName = (r: PartialClosure.Reason) => r.toString
        val byReason = result.reasons.values.groupBy(reasonName).view.mapValues(_.size).toMap
        val byClass = result.kept.groupBy(classOf).view.mapValues(_.groupBy(id => reasonName(result.reasons(id))).view.mapValues(_.size).toMap).toMap
        Right(Summary(total, classOf.size, selection.units.size, result.kept.size, classOf.size - result.kept.size,
          byReason, byClass, result.selectedNotFound.toList.sorted.take(100), selection.nonNumeric.take(100)))
      }
    }
  }

  def writeReport(s: Settings, summary: Summary): Option[String] = {
    val target = if (s.reportFile.nonEmpty) s.reportFile else s.selectionFile.stripSuffix(".json") + ".partial-report.json"
    Try {
      new ObjectMapper().registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)
        .writerWithDefaultPrettyPrinter().writeValue(new File(target), Map(
          "schema" -> "htc.partial.report.v1", "selection_file" -> s.selectionFile, "filterable_classes" -> s.filterableClasses.toList.sorted,
          "actors_scanned" -> summary.total, "filterable_actors" -> summary.filterable, "selected_units" -> summary.selected,
          "kept" -> summary.kept, "removed" -> summary.removed, "kept_by_reason" -> summary.byReason, "kept_by_class" -> summary.byClass,
          "selected_not_found" -> summary.selectedNotFound, "non_numeric_units" -> summary.nonNumericUnits))
      target
    }.toOption
  }

  /** Switches the filter off. Called at the start of every load that has no partial block: the state is per JVM and
    * an engine can run a partial simulation and then a full one.
    */
  def clear(): Unit = { active = None; expected = false }

  /** Settings from a simulation.json `partial` block. */
  def settings(partial: core.entity.configuration.PartialSimulation): Settings =
    Settings(partial.selectionFile.trim, partial.filterableClasses.map(_.trim).filter(_.nonEmpty).toSet, partial.reportFile.getOrElse(""))

  private[partial] def reset(): Unit = clear()
}
