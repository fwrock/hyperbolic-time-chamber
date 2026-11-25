package org.interscity.htc
package core.modelbinding

import com.fasterxml.jackson.databind.module.SimpleModule
import com.typesafe.config.ConfigFactory
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.ScenarioLoadError
import core.types.Tick

import scala.concurrent.{ ExecutionContext, Future }

/** One link-flow report row, independent of which model produced it. */
final case class LinkFlowRecord(
  linkId: String,
  eventType: String,
  vehicleId: String,
  actorType: String,
  actorCreationType: String,
  vehicleCountOnLink: Int
)

/** The only place where the engine learns about a simulation model's own types.
  *
  * Two models can coexist in one build (`model.hybrid`, `model.mobility`) with distinct classes of
  * the same simple names, so the engine must never name a model type directly. Each model provides
  * one implementation, `org.interscity.htc.model.<namespace>.<Namespace>ModelBindings`, and the
  * engine picks it once per JVM from `htc.model.namespace` (env `HTC_MODEL_NAMESPACE`).
  */
trait ModelBindings {
  def namespace: String

  def fractionalClockActorClassTypes: Set[String]

  /** Message the micro-aware time manager sends to every link on a global tick. */
  def globalTick(tick: Tick): AnyRef

  def accountedRoutingActorClassType: String
  def accountedRoutingEnabled: Boolean
  def accountedRoutingWorkerIds: Vector[Long]

  /** Jackson key deserializers the model's JSON state needs (map keys that are not plain strings). */
  def registerJsonKeys(module: SimpleModule): Unit

  def isPersonSource(source: ActorDataSource): Boolean

  /** Scenario-wide mode-decision pre-flight over the Person sources. Never throws; off-thread safe. */
  def validatePreflight(personSources: List[ActorDataSource])(implicit ec: ExecutionContext): Future[Either[ScenarioLoadError, Unit]]

  /** `Some` when `data` is this model's link-flow report payload. */
  def linkFlow(data: Any): Option[LinkFlowRecord]
}

object ModelBindings {
  val DefaultNamespace = "hybrid"
  val Known: Set[String] = Set("hybrid", "mobility")

  def namespaceSetting: String = {
    val config = ConfigFactory.load()
    val value = if (config.hasPath("htc.model.namespace")) config.getString("htc.model.namespace").trim else ""
    if (value.isEmpty) DefaultNamespace else value
  }

  def forNamespace(namespace: String): ModelBindings = {
    require(Known.contains(namespace), s"unknown htc.model.namespace '$namespace' (known: ${Known.toSeq.sorted.mkString(", ")})")
    val className = s"org.interscity.htc.model.$namespace.${namespace.capitalize}ModelBindings$$"
    val module = Class.forName(className).getField("MODULE$").get(null)
    module.asInstanceOf[ModelBindings]
  }

  /** Pinned for the JVM's lifetime: actor classes and message types cannot change under a run. */
  lazy val active: ModelBindings = forNamespace(namespaceSetting)

  /** Every model-scoped `classType` of a scenario must belong to the active namespace; a mixed or
    * mismatched scenario would otherwise half-run (messages of one model's types to the other's actors).
    */
  def requireScenarioMatches(namespace: String, classTypes: Iterable[String]): Unit = {
    val others = Known - namespace
    val foreign = classTypes.filter { c =>
      val bare = c.stripPrefix("org.interscity.htc.model.")
      others.exists(o => bare.startsWith(o + "."))
    }.toSeq.distinct
    require(foreign.isEmpty,
      s"scenario actor classes ${foreign.take(5).mkString(", ")} do not belong to the active model namespace '$namespace' (set HTC_MODEL_NAMESPACE)")
  }
}
