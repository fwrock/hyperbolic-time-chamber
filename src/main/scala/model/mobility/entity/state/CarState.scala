package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable

case class CarState(
  override val startTick: Tick,
  override val reporterType: ReportTypeEnum = null,
  override val scheduleOnTimeManager: Boolean = true,
  name: String,
  override val origin: String,
  override val destination: String = null,
  var bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
  var bestCost: Double = Double.MaxValue,
  var currentNode: String,
  var currentPath: Option[(RoutePathItem, RoutePathItem)] = None,
  var lastNode: String,
  var digitalRails: Boolean = false,
  var distance: Double = 0,
  var eventCount: Int = 0, // Contador de eventos gerados por este veículo
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var status: MovableStatusEnum = Start,
  
  // Parâmetros para simulação microscópica (personalidade do motorista)
  maxAcceleration: Double = 2.0,        // aceleração máxima (m/s²)
  desiredDeceleration: Double = 3.0,     // desaceleração desejada (m/s²)
  desiredSpeed: Double = 30.0,           // velocidade desejada (m/s)
  timeHeadway: Double = 1.5,             // tempo de headway (s)
  minimumGap: Double = 2.0,              // gap mínimo (m)
  politenessFactor: Double = 0.2,        // fator de polidez para troca de faixa
  laneChangeThreshold: Double = 0.1,     // limiar para troca de faixa (m/s²)
  maxSafeDeceleration: Double = 4.0,     // desaceleração máxima segura (m/s²)
  aggressiveness: Double = 0.5           // nível de agressividade do motorista (0.0 a 1.0)
) extends MovableState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager,
      movableBestCost = bestCost,
      movableStatus = status,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    )
