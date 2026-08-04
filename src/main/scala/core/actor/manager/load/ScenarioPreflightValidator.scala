package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.ActorSimulation
import core.entity.configuration.ActorDataSource
import core.enumeration.DataSourceTypeEnum
import core.util.{ JsonStreamingUtil, JsonUtil, SqliteActorSimulationUtil, StringUtil }

import org.interscity.htc.model.hybrid.actor.Person
import org.interscity.htc.model.hybrid.decision.{ ScenarioLoadError, ScenarioLoadValidator, ScenarioValidationContext }
import org.interscity.htc.model.hybrid.entity.state.PersonState
import org.interscity.htc.model.hybrid.entity.state.plan.PendingDecision
import org.interscity.htc.model.hybrid.util.{ TransitMapUtil, TransitRouteUtil }

import java.io.{ BufferedInputStream, File, FileInputStream }
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

/** Wires `model.hybrid.decision.ScenarioLoadValidator.validateModeDecisionEngines` into the real
  * load pipeline — called from both `LoadDataManager` (EAGER sources) and
  * `ProgressiveLoadDataManager` (PROGRESSIVE sources) before either creates a single actor.
  *
  * The validator itself only needs the [[PendingDecision]]s already present across the scenario's
  * Person plans, but at the point the load pipeline first sees a Person data source, plans are
  * still raw parsed JSON (`ActorSimulation.data.content`), not yet turned into `PersonState` — that
  * conversion normally happens per-actor, deep inside `BaseActor.onInitialize`, well after the
  * actor already exists. This object performs that same conversion
  * (`JsonUtil.convertValue[PersonState]`) one entity at a time during a dedicated pre-flight scan,
  * immediately discarding each `PersonState` once its `PendingDecision`s have been inspected — nothing
  * beyond the distinct `strategyId`s seen so far is retained, so memory stays bounded regardless of
  * scenario size.
  *
  * Every newly-seen `strategyId` is validated the moment it's found (reusing
  * `ScenarioLoadValidator.validateModeDecisionEngines` unchanged, called with a single-element
  * list), so a scenario-wide configuration error short-circuits the scan — the common case (a bad
  * `strategyId` used broadly, or a systemically missing dataset) fails on the first occurrence
  * instead of paying for a full read of every Person file.
  */
object ScenarioPreflightValidator {

  /** `ActorDataSource.classType` is recorded relative to the `org.interscity.htc.model.` package
    * prefix (see `StringUtil.getModelClassName`, the same resolution `CreatorLoadData` uses for
    * `typeActor` when instantiating actors) — resolve it the same way before comparing.
    */
  private val PERSON_CLASS_NAME: String = classOf[Person].getName

  def isPersonSource(source: ActorDataSource): Boolean =
    StringUtil.getModelClassName(source.classType) == PERSON_CLASS_NAME

  /** Scenario-wide data-availability flags. Both `TransitRouteUtil.isAvailable` and
    * `TransitMapUtil.isAvailable` are process-wide lazy vals sourced from `application.conf` /
    * env-var-configured files — independent of scenario actor load order, so this can be computed
    * at any point once `SimulatorSettingsRegistry` has the transit file paths registered (done in
    * `SimulationManager.onSimulationConfigLoaded`, which always runs before any load-pipeline
    * manager is started).
    */
  def currentValidationContext(): ScenarioValidationContext =
    ScenarioValidationContext(
      transitRouteDataAvailable = TransitRouteUtil.isAvailable,
      transitMapDataAvailable = TransitMapUtil.isAvailable
    )

  /** Streams every Person source in `personSources` exactly once, validating each newly-seen
    * `strategyId` as soon as it's found and stopping at the first `ScenarioLoadError`.
    *
    * Must be run off the calling actor's thread (see the "no blocking calls in actors" rule) —
    * callers dispatch this onto an I/O-safe `ExecutionContext` (e.g.
    * `"pekko.actor.io-dispatcher"`) and resume via a self-message once the `Future` completes,
    * never by blocking on it inline.
    */
  def validate(
    personSources: List[ActorDataSource],
    ctx: ScenarioValidationContext
  )(implicit ec: ExecutionContext): Future[Either[ScenarioLoadError, Unit]] =
    Future {
      val seenStrategyIds = mutable.Set.empty[String]
      var result: Either[ScenarioLoadError, Unit] = Right(())

      val sourceIterator = personSources.iterator
      while (sourceIterator.hasNext && result.isRight) {
        val source = sourceIterator.next()
        val path = source.dataSource.info("path").asInstanceOf[String]
        val (closeable, actorIterator): (AutoCloseable, Iterator[ActorSimulation]) =
          source.dataSource.sourceType match {
            case DataSourceTypeEnum.json =>
              val is = new BufferedInputStream(new FileInputStream(new File(path)))
              val (_, iter) = JsonStreamingUtil.createParser(is)
              (is, iter)
            case DataSourceTypeEnum.sqlite =>
              SqliteActorSimulationUtil.openIterator(path)
          }
        try {
          while (actorIterator.hasNext && result.isRight) {
            val actor = actorIterator.next()
            val personState = JsonUtil.convertValue[PersonState](actor.data.content)
            val decisionIterator = personState.originalPlan.iterator.collect {
              case p: PendingDecision => p
            }
            while (decisionIterator.hasNext && result.isRight) {
              val decision = decisionIterator.next()
              val strategyId = decision.decision.strategyId
              if (!seenStrategyIds.contains(strategyId)) {
                seenStrategyIds += strategyId
                result = ScenarioLoadValidator.validateModeDecisionEngines(List(decision), ctx)
              }
            }
          }
        } finally closeable.close()
      }

      result
    }
}
