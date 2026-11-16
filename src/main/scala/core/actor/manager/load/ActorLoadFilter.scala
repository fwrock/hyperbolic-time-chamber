package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.ActorSimulation
import core.entity.configuration.{ ActorDataSource, LoadFilterSimulation }

import com.typesafe.config.Config

/** Publishes run-local class selection and ordered transformations without writing scenario files.
  *
  * Configure before source scans and loader creation. Exclusions take precedence over inclusions;
  * retained records are transformed before checking for explicit dependencies on excluded classes.
  */
object ActorLoadFilter {

  /** Immutable filter selection, kept nested to preserve its existing public JVM identity.
    *
    * Class names match exact or dotted suffix forms. Transform order and duplicates are meaningful;
    * each transform must preserve identity/class at its own boundary.
    */
  final case class Settings(
    includedClasses: Set[String] = Set.empty,
    excludedClasses: Set[String] = Set.empty,
    transforms: List[ActorLoadTransform] = Nil
  ) {

    /** Indicates whether class selection, dependency validation or transformations are requested.
      */
    def enabled: Boolean =
      includedClasses.nonEmpty || excludedClasses.nonEmpty || transforms.nonEmpty
  }

  @volatile private var current = Settings()

  /** Resolves settings without publishing them; constructor or reflection failures propagate. */
  def settings(config: Config, block: Option[LoadFilterSimulation] = None): Settings =
    ActorLoadFilterConfig.read(config, block)

  /** Publishes only fully constructed settings, retaining the previous selection on failure. */
  def configure(config: Config, block: Option[LoadFilterSimulation] = None): Settings = {
    val next = settings(config, block)
    current = next
    next
  }

  /** Restores unrestricted loading for the next run. */
  def clear(): Unit = current = Settings()

  /** Supports suffix qualification in either direction while requiring a dot at the boundary. */
  private def matches(className: String, configured: Set[String]): Boolean =
    configured.exists {
      selected =>
        className == selected || className.endsWith("." + selected) || selected.endsWith(
          "." + className
        )
    }

  /** Applies explicit exclusions first, followed by the optional allow-list. */
  def isExcluded(className: String, s: Settings = current): Boolean =
    matches(className, s.excludedClasses) ||
      (s.includedClasses.nonEmpty && !matches(className, s.includedClasses))

  /** Removes excluded source declarations without opening or modifying their files. */
  def sources(sources: List[ActorDataSource], s: Settings = current): List[ActorDataSource] =
    sources.filterNot(
      source => isExcluded(source.classType, s)
    )

  /** Filters/transforms a chunk in input order; disabled loading returns the original list. */
  def apply(actors: List[ActorSimulation]): List[ActorSimulation] =
    if (!current.enabled) actors else actors.flatMap(transform(_))

  /** Skips excluded records, otherwise transforms and validates their retained dependencies.
    *
    * Disabled settings preserve the original record without dependency validation. A transform or
    * invariant failure propagates rather than silently removing a retained record.
    */
  def transform(actor: ActorSimulation, s: Settings = current): Option[ActorSimulation] = {
    if (isExcluded(actor.typeActor, s)) return None
    if (!s.enabled) return Some(actor)
    val updated = applyTransforms(actor, s.transforms)
    validateDependencies(updated, s)
    Some(updated)
  }

  /** Enforces identity after every step, preventing a later transform from hiding a prior
    * violation.
    */
  private def applyTransforms(
    actor: ActorSimulation,
    transforms: List[ActorLoadTransform]
  ): ActorSimulation =
    transforms.foldLeft(actor) {
      (record, transform) =>
        val result = transform.transform(record)
        require(
          result.id == actor.id && result.typeActor == actor.typeActor,
          s"load transform ${transform.getClass.getName} changed actor identity/class: ${actor.id}"
        )
        result
    }

  /** Ignores unnamed reference classes and reports excluded classes once each, sorted by name. */
  private def validateDependencies(actor: ActorSimulation, settings: Settings): Unit = {
    val excluded = Option(actor.relationships).toList
      .flatMap(_.values)
      .filter(
        ref =>
          Option(ref.classType).exists(
            name => name.nonEmpty && isExcluded(name, settings)
          )
      )
    require(
      excluded.isEmpty,
      s"actor ${actor.id} (${actor.typeActor}) references an excluded class: " +
        excluded.map(_.classType).distinct.sorted.mkString(", ")
    )
  }
}
