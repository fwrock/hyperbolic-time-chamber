package org.interscity.htc
package model.mobility.decision

import core.actor.manager.load.ActorLoadFilter
import core.entity.actor.ActorSimulation
import core.entity.configuration.ActorDataSource
import core.enumeration.DataSourceTypeEnum
import core.util.{ JsonStreamingUtil, JsonUtil, SqliteActorSimulationUtil, StringUtil }

import org.interscity.htc.model.mobility.actor.Person
import org.interscity.htc.model.mobility.entity.state.PersonState
import org.interscity.htc.model.mobility.entity.state.plan.PendingDecision
import org.interscity.htc.model.mobility.util.{ TransitMapUtil, TransitRouteUtil }

import java.io.{ BufferedInputStream, File, FileInputStream }
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

/** Scenario-wide mode-decision pre-flight for the mobility model: streams every Person source once,
  * validating each newly seen `strategyId` and stopping at the first error. Mirrors the hybrid model's
  * validator over mobility's own `PersonState`/`PendingDecision` types.
  */
object MobilityScenarioPreflight {

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
            val filtered = core.modifications.ScenarioModifications.transform(actor)
              .flatMap(ActorLoadFilter.transform(_))
            val personState = filtered.map(a => JsonUtil.convertValue[PersonState](a.data.content))
            val decisionIterator = personState.iterator.flatMap(_.originalPlan.iterator).collect {
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
