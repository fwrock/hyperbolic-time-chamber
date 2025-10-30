package org.interscity.htc
package model.mobility.util

import model.mobility.entity.state.micro.MicroVehicleState

/** Implementação dos modelos de tráfego microscópico IDM e MOBIL
  */
object TrafficModels {

  /** Calcula a aceleração usando o Modelo de Motorista Inteligente (IDM)
    *
    * @param vehicle
    *   Estado do veículo atual
    * @param leader
    *   Estado do veículo líder (opcional)
    * @return
    *   Aceleração calculada em m/s²
    */
  def calculateIDMAcceleration(
    vehicle: MicroVehicleState,
    leader: Option[MicroVehicleState]
  ): Double = {

    val v = vehicle.speed
    val v0 = vehicle.desiredSpeed
    val a = vehicle.maxAcceleration
    val b = vehicle.desiredDeceleration
    val s0 = vehicle.minimumGap
    val T = vehicle.timeHeadway

    // Termo de velocidade livre
    val freeFlowTerm = math.pow(v / v0, 4)

    leader match {
      case Some(lead) =>
        val s = math.max(
          0.1,
          lead.position - vehicle.position - 5.0
        ) // gap real (assumindo 5m de comprimento do veículo)
        val deltaV = v - lead.speed

        // Gap desejado
        val sStar = s0 + v * T + (v * deltaV) / (2 * math.sqrt(a * b))

        // Termo de interação
        val interactionTerm = math.pow(sStar / s, 2)

        // Aceleração IDM
        a * (1 - freeFlowTerm - interactionTerm)

      case None =>
        // Sem líder - aceleração de fluxo livre
        a * (1 - freeFlowTerm)
    }
  }

  /** Avalia se uma troca de faixa é desejável e segura usando o modelo MOBIL
    *
    * @param vehicle
    *   Estado do veículo que deseja trocar de faixa
    * @param targetLane
    *   Faixa de destino
    * @param currentContext
    *   Contexto atual do veículo
    * @param targetLeader
    *   Líder na faixa de destino
    * @param targetFollower
    *   Seguidor na faixa de destino
    * @return
    *   True se a troca de faixa deve ser realizada
    */
  def evaluateLaneChange(
    vehicle: MicroVehicleState,
    targetLane: Int,
    currentLeader: Option[MicroVehicleState],
    targetLeader: Option[MicroVehicleState],
    targetFollower: Option[MicroVehicleState]
  ): Boolean = {

    // Critério de segurança: verificar se a troca causará desaceleração perigosa no seguidor
    val safetyCheck = targetFollower match {
      case Some(follower) =>
        val futureFollowerAccel = calculateIDMAcceleration(
          follower.copy(speed = follower.speed),
          Some(vehicle.copy(position = vehicle.position, lane = targetLane))
        )
        futureFollowerAccel >= -vehicle.maxSafeDeceleration
      case None => true // Sem seguidor - sempre seguro
    }

    if (!safetyCheck) return false

    // Critério de incentivo: a troca oferece vantagem?
    val currentAccel = calculateIDMAcceleration(vehicle, currentLeader)
    val targetAccel = calculateIDMAcceleration(
      vehicle.copy(lane = targetLane),
      targetLeader
    )

    // Mudança na aceleração do veículo atual
    val selfAdvantage = targetAccel - currentAccel

    // Mudança na aceleração do futuro seguidor (custo para outros)
    val followerDisadvantage = targetFollower match {
      case Some(follower) =>
        val currentFollowerAccel = calculateIDMAcceleration(follower, targetLeader)
        val futureFollowerAccel = calculateIDMAcceleration(
          follower,
          Some(vehicle.copy(position = vehicle.position, lane = targetLane))
        )
        currentFollowerAccel - futureFollowerAccel
      case None => 0.0
    }

    // Critério MOBIL: vantagem própria > threshold + politeness * desvantagem do seguidor
    val mobilCriterion = selfAdvantage > vehicle.laneChangeThreshold +
      vehicle.politenessFactor * followerDisadvantage

    mobilCriterion
  }

  /** Atualiza a posição e velocidade do veículo usando equações de movimento
    *
    * @param vehicle
    *   Estado atual do veículo
    * @param acceleration
    *   Aceleração a ser aplicada
    * @param timestep
    *   Intervalo de tempo
    * @return
    *   Novo estado do veículo
    */
  def updateVehicleKinematics(
    vehicle: MicroVehicleState,
    acceleration: Double,
    timestep: Double
  ): MicroVehicleState = {

    // Limitar aceleração para valores realistas
    val limitedAccel =
      math.max(-vehicle.maxSafeDeceleration, math.min(vehicle.maxAcceleration, acceleration))

    // Atualizar velocidade
    val newSpeed = math.max(0.0, vehicle.speed + limitedAccel * timestep)

    // Atualizar posição
    val newPosition = vehicle.position + newSpeed * timestep

    vehicle.copy(
      speed = newSpeed,
      position = newPosition,
      acceleration = limitedAccel
    )
  }

  /** Encontra o veículo líder na faixa especificada
    *
    * @param vehiclePosition
    *   Posição do veículo de referência
    * @param lane
    *   Faixa a ser analisada
    * @param vehicles
    *   Lista de todos os veículos no link
    * @return
    *   Veículo líder mais próximo (opcional)
    */
  def findLeader(
    vehiclePosition: Double,
    lane: Int,
    vehicles: Iterable[MicroVehicleState]
  ): Option[MicroVehicleState] =
    vehicles
      .filter(
        v => v.lane == lane && v.position > vehiclePosition
      )
      .toSeq
      .sortBy(_.position)
      .headOption

  /** Encontra o veículo seguidor na faixa especificada
    *
    * @param vehiclePosition
    *   Posição do veículo de referência
    * @param lane
    *   Faixa a ser analisada
    * @param vehicles
    *   Lista de todos os veículos no link
    * @return
    *   Veículo seguidor mais próximo (opcional)
    */
  def findFollower(
    vehiclePosition: Double,
    lane: Int,
    vehicles: Iterable[MicroVehicleState]
  ): Option[MicroVehicleState] =
    vehicles
      .filter(
        v => v.lane == lane && v.position < vehiclePosition
      )
      .toSeq
      .sortBy(-_.position)
      .headOption

  /** Valida se existe espaço suficiente para uma troca de faixa
    *
    * @param vehicle
    *   Veículo que deseja trocar
    * @param targetLeader
    *   Líder na faixa de destino
    * @param targetFollower
    *   Seguidor na faixa de destino
    * @return
    *   True se há espaço suficiente
    */
  def hasAdequateGap(
    vehicle: MicroVehicleState,
    targetLeader: Option[MicroVehicleState],
    targetFollower: Option[MicroVehicleState]
  ): Boolean = {

    val vehicleLength = 5.0 // metros
    val safetyBuffer = 2.0 // metros
    val minGap = vehicle.minimumGap + safetyBuffer

    // Verificar gap com líder
    val frontGapOk = targetLeader match {
      case Some(leader) => (leader.position - vehicle.position) > (vehicleLength + minGap)
      case None         => true
    }

    // Verificar gap com seguidor
    val rearGapOk = targetFollower match {
      case Some(follower) => (vehicle.position - follower.position) > (vehicleLength + minGap)
      case None           => true
    }

    frontGapOk && rearGapOk
  }
}
