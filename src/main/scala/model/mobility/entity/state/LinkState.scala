package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import org.interscity.htc.model.mobility.entity.state.micro.MicroVehicleState

import scala.collection.mutable

case class LinkState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = true,
  from: String,
  to: String,
  length: Double,
  lanes: Int,
  speedLimit: Double,
  capacity: Double,
  freeSpeed: Double,
  jamDensity: Double = 0.0,
  permLanes: Double = 1.0,
  typeLink: String = "normal",
  modes: List[String] = List("car"),
  var currentSpeed: Double = 0.0,
  var congestionFactor: Double = 1.0,
  registered: mutable.Set[LinkRegister] = mutable.Set(),
  
  // Configuração de simulação
  simulationType: String = "meso", // "meso" ou "micro"
  
  // Parâmetros para simulação microscópica
  globalTickDuration: Double = 1.0,
  microTimestep: Double = 0.1,
  
  // Estado microscópico dos veículos
  var microVehicles: mutable.Map[String, MicroVehicleState] = mutable.Map.empty
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    )
