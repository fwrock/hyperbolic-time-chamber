package org.interscity.htc
package model.mobility.actor

import core.actor.{BaseActor, SimulationBaseActor}
import model.mobility.entity.state.MovableState
import core.entity.event.control.simulation.{AdvanceToTick, TickCompleted}
import core.enumeration.TimePolicyEnum

import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.model.mobility.entity.model.model.Route
import org.interscity.htc.core.entity.actor.properties.{Properties, SimulationBaseProperties}
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, Start}
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{EnterLinkData, LeaveLinkData, ReceiveRoute}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.CityMapUtil
import org.interscity.htc.system.database.redis.RedisClientManager

import scala.collection.mutable

/** TimeStepped version of Movable base class
  *
  * This class adapts the original Movable class to work with TimeStepped simulation:
  *   - Responds to AdvanceToTick instead of SpontaneousEvent
  *   - Maintains state between ticks
  *   - Coordinates with TimeStepped_LTM for synchronization
  */
abstract class TimeSteppedMovable[T <: MovableState](
  private val properties: SimulationBaseProperties
)(implicit m: Manifest[T])
    extends SimulationBaseActor[T](
      properties = properties.copy(timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation))
    ) {

  protected def requestRoute(): Unit = {}

  /** TimeStepped version of spontaneous event handling This should not be called in TimeStepped
    * mode, but provided for compatibility
    */
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    logWarn(
      s"TimeSteppedMovable recebeu SpontaneousEvent, mas deveria usar AdvanceToTick. Status: ${state.movableStatus}"
    )

    // For compatibility, we can handle some basic cases
    state.movableStatus match {
      case Start =>
        logInfo("Starting TimeStepped Movable actor")
        requestRoute()
      case Ready =>
        logInfo("TimeStepped Movable actor is ready to enter link")
        enterLink()
      case _ =>
        logWarn(s"TimeStepped event status not handled in SpontaneousEvent: ${state.movableStatus}")
    }
  }

  /** TimeStepped behavior - this should be overridden by subclasses
    */
  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    val targetTick = event.targetTick
    logDebug(s"TimeSteppedMovable ${getEntityId} processando tick $targetTick")

    // Default implementation - subclasses should override
    state.movableStatus match {
      case Start =>
        requestRoute()
      case Ready =>
        enterLink()
      case _ =>
        logDebug(s"TimeSteppedMovable status ${state.movableStatus} no tick $targetTick")
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: ReceiveRoute => handleReceiveRoute(d)
      case d: LinkInfoData => handleLinkInfo(event, d)
      case _ =>
        logWarn(s"TimeSteppedMovable Event not handled: $event")
    }

  private def handleReceiveRoute(data: ReceiveRoute): Unit = {
    val redisManager = RedisClientManager()
    redisManager.load(data.routeId).map(Route.parseFrom) match {
      case Some(route) =>
        val updatedCost = route.cost
        state.movableBestRoute = Some(mutable.Queue())
        state.movableStatus = Ready
        logDebug(s"Rota recebida do Redis para ${getEntityId}: custo $updatedCost")
      case None =>
        logError(s"Rota não encontrada no Redis: ${data.routeId}")
        state.movableStatus = Finished
    }
    enterLink()
  }

  private def handleLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit =
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event, data)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event, data)
      case _ =>
        logWarn(s"Evento não tratado: $event com dados: $data")
    }

  protected def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit =
    // Default implementation - subclasses should override for TimeStepped behavior
    logDebug(s"TimeSteppedMovable ${getEntityId} entrou no link: ${data.linkLength}m")

  protected def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit =
    // Default implementation - subclasses should override for TimeStepped behavior
    logDebug(s"TimeSteppedMovable ${getEntityId} saiu do link: ${data.linkLength}m")

  protected def onFinish(nodeId: String): Unit =
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
      logInfo(s"TimeSteppedMovable ${getEntityId} chegou ao destino: $nodeId")
    } else {
      state.movableStatus = Finished
      logInfo(s"TimeSteppedMovable ${getEntityId} finalizou no nó: $nodeId")
    }

  protected def enterLink(): Unit =
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            sendMessageTo(
              entityId = edgeLabel.id,
              shardId = edgeLabel.classType,
              data = EnterLinkData(
                actorId = getEntityId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.EnterLink.toString,
              actorType = LoadBalancedDistributed
            )
            logDebug(
              s"TimeSteppedMovable ${getEntityId} entrando no link $linkEdgeGraphId para nó $nextNodeId"
            )
          case None =>
            state.movableStatus = Finished
            logWarn(s"Edge label não encontrado para link $linkEdgeGraphId, finalizando.")
        }
      case None if state.movableBestRoute.isEmpty =>
        state.movableStatus = Finished
        logDebug(
          s"TimeSteppedMovable ${getEntityId} sem caminho atual e sem melhor rota, finalizando."
        )
      case None =>
        logDebug(
          s"TimeSteppedMovable ${getEntityId} sem caminho atual, mas com melhor rota disponível."
        )
        state.movableCurrentPath = getNextPath
        enterLink()
    }

  protected def leavingLink(): Unit =
    state.movableCurrentPath match {
      case Some((linkEdgeGraphId, nextNodeId)) =>
        CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
          case Some(edgeLabel) =>
            sendMessageTo(
              entityId = edgeLabel.id,
              shardId = edgeLabel.classType,
              data = LeaveLinkData(
                actorId = getEntityId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.LeaveLink.toString,
              actorType = LoadBalancedDistributed
            )

            logDebug(s"TimeSteppedMovable ${getEntityId} saindo do link $linkEdgeGraphId")

            if (state.movableBestRoute.isEmpty) {
              logDebug(s"TimeSteppedMovable ${getEntityId} sem melhor rota para continuar")
              onFinish(nextNodeId)
            }
            state.movableCurrentPath = None
          case _ =>
            logWarn(s"TimeSteppedMovable ${getEntityId} - item do caminho não tratado")
        }
      case None =>
        logWarn(s"TimeSteppedMovable ${getEntityId} - nenhum link para sair")
    }

  protected def getNextPath: Option[(String, String)] =
    state.movableBestRoute match {
      case Some(path) if path.nonEmpty =>
        Some(path.dequeue())
      case Some(_) =>
        logDebug(s"TimeSteppedMovable ${getEntityId} - caminho vazio")
        None
      case None =>
        logWarn(s"TimeSteppedMovable ${getEntityId} - nenhum caminho para seguir")
        None
    }

  protected def viewNextPath: Option[(String, String)] =
    state.movableBestRoute match {
      case Some(path) if path.nonEmpty =>
        Some(path.head)
      case Some(_) =>
        logDebug(s"TimeSteppedMovable ${getEntityId} - caminho vazio")
        None
      case None =>
        logWarn(s"TimeSteppedMovable ${getEntityId} - nenhum caminho para visualizar")
        None
    }

  protected def getCurrentNode: String =
    state.movableCurrentPath match {
      case Some(item) =>
        item._2
      case None =>
        null
    }

  protected def getNextLink: String =
    viewNextPath match {
      case Some(item) =>
        item._1
      case None =>
        null
    }

  /** Get current movable statistics for reporting
    */
  def getMovableStatistics: Map[String, Any] =
    Map(
      "entityId" -> getEntityId,
      "status" -> state.movableStatus.toString,
      "origin" -> state.origin,
      "destination" -> state.destination,
      "currentDistance" -> 0.0,
      "reachedDestination" -> state.movableReachedDestination,
      "currentPath" -> state.movableCurrentPath.map(
        p => s"${p._1} -> ${p._2}"
      ),
      "routeLength" -> state.movableBestRoute.map(_.size).getOrElse(0),
      "timePolicy" -> getTimePolicy.toString
    )
}
