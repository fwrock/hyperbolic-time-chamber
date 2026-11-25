package org.interscity.htc
package model.mobility

import com.fasterxml.jackson.databind.module.SimpleModule
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.ScenarioLoadError
import core.modelbinding.{ LinkFlowRecord, ModelBindings }
import core.types.Tick
import model.mobility.entity.event.data.{ GlobalTickEvent, VehicleLinkFlowData }
import model.mobility.entity.state.model.SubRoutePair
import model.mobility.decision.MobilityScenarioPreflight
import model.mobility.util.AccountedRoutingConfig
import model.mobility.util.SubRoutePairKeys

import scala.concurrent.{ ExecutionContext, Future }

object MobilityModelBindings extends ModelBindings {
  val namespace = "mobility"

  val fractionalClockActorClassTypes: Set[String] =
    Set("mobility.actor.Car", "mobility.actor.Link", "mobility.actor.Node", "mobility.actor.TrafficSignal")

  def globalTick(tick: Tick): AnyRef = GlobalTickEvent(tick = tick)

  def accountedRoutingActorClassType: String = AccountedRoutingConfig.ActorClassType
  def accountedRoutingEnabled: Boolean = AccountedRoutingConfig.enabled
  def accountedRoutingWorkerIds: Vector[Long] = AccountedRoutingConfig.workerIds

  def registerJsonKeys(module: SimpleModule): Unit =
    module.addKeyDeserializer(classOf[SubRoutePair], new SubRoutePairKeys.Deserializer)

  def isPersonSource(source: ActorDataSource): Boolean = MobilityScenarioPreflight.isPersonSource(source)

  def validatePreflight(personSources: List[ActorDataSource])(implicit ec: ExecutionContext): Future[Either[ScenarioLoadError, Unit]] =
    MobilityScenarioPreflight
      .validate(personSources, MobilityScenarioPreflight.currentValidationContext())
      .map(_.left.map(e => ScenarioLoadError(e.message)))

  def linkFlow(data: Any): Option[LinkFlowRecord] = data match {
    case f: VehicleLinkFlowData =>
      Some(LinkFlowRecord(f.linkId, f.eventType, f.vehicleId, f.actorType, f.actorCreationType, f.vehicleCountOnLink))
    case _ => None
  }
}
