package org.interscity.htc
package model.mobility.entity.event.micro

import model.mobility.entity.state.micro.{MicroVehicleContext, MicroVehicleIntention}

/**
 * Eventos para comunicação entre LinkActor e VehicleActor em simulação microscópica
 */

/**
 * Mensagem enviada pelo LinkActor ao VehicleActor com o contexto do sub-tick
 */
case class ProvideMicroContext(
  context: MicroVehicleContext,
  subTick: Int,
  totalSubTicks: Int
)

/**
 * Resposta do VehicleActor com suas intenções de movimento
 */
case class MyMicroIntention(
  intention: MicroVehicleIntention,
  subTick: Int
)

/**
 * Notificação de início de simulação microscópica para um tick
 */
case class StartMicroSimulation(
  tick: Long,
  globalTickDuration: Double,
  microTimestep: Double
)

/**
 * Notificação de fim de simulação microscópica para um tick
 */
case class CompleteMicroSimulation(
  tick: Long,
  subTicksCompleted: Int
)
