package org.interscity.htc
package core.actor.manager.load

import core.entity.configuration.LoadFilterSimulation

import com.typesafe.config.Config

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Resolves per-scenario filter overrides and constructs ordered, model-owned transformations.
  *
  * A present scenario block replaces every application setting, including empty lists. Reading
  * settings does not publish them; callers can retain their previous configuration on failure.
  */
private[load] object ActorLoadFilterConfig {

  /** Reads class sets and ordered transform names without deduplicating transform instances. */
  def read(config: Config, block: Option[LoadFilterSimulation]): ActorLoadFilter.Settings = {
    val transforms = values(config, block.map(_.transforms), "transforms")
      .map(
        name => instantiate(name, config)
      )
    ActorLoadFilter.Settings(
      includedClasses = values(config, block.map(_.includeClasses), "include-classes").toSet,
      excludedClasses = values(config, block.map(_.excludeClasses), "exclude-classes").toSet,
      transforms = transforms
    )
  }

  /** Trims the chosen list while keeping an explicitly empty scenario list authoritative. */
  private def values(
    config: Config,
    overrideValues: Option[List[String]],
    setting: String
  ): List[String] =
    overrideValues
      .getOrElse(configuredList(config, s"htc.load-filter.$setting"))
      .map(_.trim)
      .filter(_.nonEmpty)

  /** Accepts HOCON lists or CSV strings, preserving the empty fallback for configuration errors. */
  private def configuredList(config: Config, path: String): List[String] =
    Try(config.getStringList(path).asScala.toList)
      .getOrElse(Try(config.getString(path).split(',').toList).getOrElse(Nil))

  /** Keeps reflection failures wrapped with the transform name and the original reflective cause.
    */
  private def instantiate(name: String, config: Config): ActorLoadTransform =
    try
      Class
        .forName(name)
        .asSubclass(classOf[ActorLoadTransform])
        .getConstructor(classOf[Config])
        .newInstance(config)
    catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"cannot initialize load transform $name", error)
    }
}
