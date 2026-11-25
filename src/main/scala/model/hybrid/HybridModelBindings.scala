package org.interscity.htc
package model.hybrid

import com.fasterxml.jackson.databind.module.SimpleModule
import core.actor.manager.load.ScenarioPreflightValidator
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.ScenarioLoadError
import core.modelbinding.{ LinkFlowRecord, ModelBindings }
import core.types.Tick
import model.hybrid.entity.event.data.{ GlobalTickEvent, VehicleLinkFlowData }
import model.hybrid.entity.state.model.SubRoutePair
import model.hybrid.util.AccountedRoutingConfig
import model.hybrid.util.SubRoutePairKeys

import scala.concurrent.{ ExecutionContext, Future }

object HybridModelBindings extends ModelBindings {
  val namespace = "hybrid"

  val fractionalClockActorClassTypes: Set[String] =
    Set("hybrid.actor.Car", "hybrid.actor.Link", "hybrid.actor.Node", "hybrid.actor.TrafficSignal")

  def globalTick(tick: Tick): AnyRef = GlobalTickEvent(tick = tick)

  def accountedRoutingActorClassType: String = AccountedRoutingConfig.ActorClassType
  def accountedRoutingEnabled: Boolean = AccountedRoutingConfig.enabled
  def accountedRoutingWorkerIds: Vector[Long] = AccountedRoutingConfig.workerIds

  def registerJsonKeys(module: SimpleModule): Unit =
    module.addKeyDeserializer(classOf[SubRoutePair], new SubRoutePairKeys.Deserializer)

  def isPersonSource(source: ActorDataSource): Boolean = ScenarioPreflightValidator.isPersonSource(source)

  def validatePreflight(personSources: List[ActorDataSource])(implicit ec: ExecutionContext): Future[Either[ScenarioLoadError, Unit]] =
    ScenarioPreflightValidator
      .validate(personSources, ScenarioPreflightValidator.currentValidationContext())
      .map(_.left.map(e => ScenarioLoadError(e.message)))

  def linkFlow(data: Any): Option[LinkFlowRecord] = data match {
    case f: VehicleLinkFlowData =>
      Some(LinkFlowRecord(f.linkId, f.eventType, f.vehicleId, f.actorType, f.actorCreationType, f.vehicleCountOnLink))
    case _ => None
  }
}
