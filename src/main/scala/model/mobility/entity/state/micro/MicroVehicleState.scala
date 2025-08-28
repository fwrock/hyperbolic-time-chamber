package org.interscity.htc
package model.mobility.entity.state.micro

import org.apache.pekko.actor.ActorRef

/**
 * Estado detalhado de um veículo em simulação microscópica
 * Contém todas as informações necessárias para os modelos IDM e MOBIL
 */
case class MicroVehicleState(
  vehicleId: String,
  speed: Double,              // velocidade atual em m/s
  position: Double,           // posição no link em metros (desde o início)
  acceleration: Double,       // aceleração atual em m/s²
  lane: Int,                  // índice da faixa atual (0-baseado)
  actorRef: ActorRef,         // referência para o ator do veículo
  
  // Parâmetros do modelo IDM
  maxAcceleration: Double = 2.0,      // aceleração máxima (m/s²)
  desiredDeceleration: Double = 3.0,   // desaceleração desejada (m/s²)
  desiredSpeed: Double = 30.0,         // velocidade desejada (m/s)
  timeHeadway: Double = 1.5,           // tempo de headway (s)
  minimumGap: Double = 2.0,            // gap mínimo (m)
  
  // Parâmetros do modelo MOBIL
  politenessFactor: Double = 0.2,      // fator de polidez para troca de faixa
  laneChangeThreshold: Double = 0.1,   // limiar para troca de faixa (m/s²)
  maxSafeDeceleration: Double = 4.0,   // desaceleração máxima segura (m/s²)
  
  // Estado da troca de faixa
  laneChangeIntention: Option[Int] = None,  // faixa de destino pretendida
  laneChangeProgress: Double = 0.0          // progresso da troca (0.0 a 1.0)
)

/**
 * Contexto fornecido pelo LinkActor aos veículos para tomada de decisão
 */
case class MicroVehicleContext(
  vehicleId: String,
  currentLane: Int,
  leader: Option[MicroVehicleState],       // veículo da frente na mesma faixa
  follower: Option[MicroVehicleState],     // veículo atrás na mesma faixa
  leftLeader: Option[MicroVehicleState],   // líder na faixa esquerda
  leftFollower: Option[MicroVehicleState], // seguidor na faixa esquerda
  rightLeader: Option[MicroVehicleState],  // líder na faixa direita
  rightFollower: Option[MicroVehicleState], // seguidor na faixa direita
  linkLength: Double,
  speedLimit: Double,
  microTimestep: Double
)

/**
 * Intenção de movimento calculada pelo veículo
 */
case class MicroVehicleIntention(
  vehicleId: String,
  desiredAcceleration: Double,           // aceleração desejada pelo modelo IDM
  desiredLaneChange: Option[Int] = None  // faixa de destino desejada (None = manter faixa)
)
