package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.ActorSimulation
import core.entity.configuration.{ActorDataSource, LoadFilterSimulation}
import com.typesafe.config.Config
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A model-owned transformation of a loaded record. Identity and class must remain unchanged. */
trait ActorLoadTransform {
  def transform(actor: ActorSimulation): ActorSimulation
}

/** Generic, run-local class selection. Original scenario files are never written. */
object ActorLoadFilter {
  final case class Settings(includedClasses: Set[String] = Set.empty,
                            excludedClasses: Set[String] = Set.empty,
                            transforms: List[ActorLoadTransform] = Nil) {
    def enabled: Boolean = includedClasses.nonEmpty || excludedClasses.nonEmpty || transforms.nonEmpty
  }
  @volatile private var current = Settings()

  def settings(config: Config, block: Option[LoadFilterSimulation] = None): Settings = {
    def list(path: String): List[String] = Try(config.getStringList(path).asScala.toList)
      .getOrElse(Try(config.getString(path).split(',').toList).getOrElse(Nil))
      .map(_.trim).filter(_.nonEmpty)
    def clean(values: List[String]): List[String] = values.map(_.trim).filter(_.nonEmpty)
    val transforms = block.map(b => clean(b.transforms)).getOrElse(list("htc.load-filter.transforms")).map { name =>
      try Class.forName(name).asSubclass(classOf[ActorLoadTransform])
        .getConstructor(classOf[Config]).newInstance(config)
      catch { case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"cannot initialize load transform $name", error)
      }
    }
    Settings(block.map(b => clean(b.includeClasses)).getOrElse(list("htc.load-filter.include-classes")).toSet,
      block.map(b => clean(b.excludeClasses)).getOrElse(list("htc.load-filter.exclude-classes")).toSet, transforms)
  }

  def configure(config: Config, block: Option[LoadFilterSimulation] = None): Settings = {
    val next = settings(config, block)
    current = next
    next
  }
  def clear(): Unit = current = Settings()

  private def matches(className: String, configured: Set[String]): Boolean = configured.exists { selected =>
    className == selected || className.endsWith("." + selected) || selected.endsWith("." + className)
  }
  def isExcluded(className: String, s: Settings = current): Boolean =
    matches(className, s.excludedClasses) ||
      (s.includedClasses.nonEmpty && !matches(className, s.includedClasses))

  def sources(sources: List[ActorDataSource], s: Settings = current): List[ActorDataSource] =
    sources.filterNot(source => isExcluded(source.classType, s))

  def apply(actors: List[ActorSimulation]): List[ActorSimulation] =
    if (!current.enabled) actors else actors.flatMap(transform(_))

  def transform(actor: ActorSimulation, s: Settings = current): Option[ActorSimulation] = {
    if (isExcluded(actor.typeActor, s)) return None
    if (!s.enabled) return Some(actor)
    val updated = s.transforms.foldLeft(actor) { (record, transform) =>
      val result = transform.transform(record)
      require(result.id == actor.id && result.typeActor == actor.typeActor,
        s"load transform ${transform.getClass.getName} changed actor identity/class: ${actor.id}")
      result
    }
    val excludedDependencies = Option(updated.relationships).toList.flatMap(_.values)
      .filter(ref => Option(ref.classType).exists(name => name.nonEmpty && isExcluded(name, s)))
    require(excludedDependencies.isEmpty,
      s"actor ${actor.id} (${actor.typeActor}) references an excluded class: " +
        excludedDependencies.map(_.classType).distinct.sorted.mkString(", "))
    Some(updated)
  }
}
