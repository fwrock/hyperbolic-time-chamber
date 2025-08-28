package org.interscity.htc
package model.mobility.util

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import model.mobility.entity.state.micro.MicroVehicleState

/**
 * Testes unitários para os modelos de tráfego microscópico
 */
class TrafficModelsTest extends AnyFunSuite with Matchers {

  test("IDM acceleration calculation - free flow") {
    val vehicle = createTestVehicle(speed = 20.0, desiredSpeed = 30.0)
    
    // Sem líder - deve acelerar em direção à velocidade desejada
    val acceleration = TrafficModels.calculateIDMAcceleration(vehicle, None)
    
    acceleration should be > 0.0
    acceleration should be <= vehicle.maxAcceleration
  }

  test("IDM acceleration calculation - following") {
    val follower = createTestVehicle(speed = 25.0, position = 0.0)
    val leader = createTestVehicle(speed = 20.0, position = 50.0)
    
    // Seguindo veículo mais lento - deve desacelerar
    val acceleration = TrafficModels.calculateIDMAcceleration(follower, Some(leader))
    
    acceleration should be < 0.0
  }

  test("IDM acceleration calculation - at desired speed") {
    val vehicle = createTestVehicle(speed = 30.0, desiredSpeed = 30.0)
    
    // Na velocidade desejada sem líder - aceleração próxima de zero
    val acceleration = TrafficModels.calculateIDMAcceleration(vehicle, None)
    
    math.abs(acceleration) should be < 0.1
  }

  test("MOBIL lane change evaluation - beneficial change") {
    val vehicle = createTestVehicle(speed = 15.0, position = 100.0)
    val slowLeader = createTestVehicle(speed = 10.0, position = 120.0)
    val fastTargetLeader = createTestVehicle(speed = 25.0, position = 200.0)
    val targetFollower = createTestVehicle(speed = 20.0, position = 80.0)
    
    // Mudança para faixa com líder mais rápido deve ser aprovada
    val shouldChange = TrafficModels.evaluateLaneChange(
      vehicle = vehicle,
      targetLane = 1,
      currentLeader = Some(slowLeader),
      targetLeader = Some(fastTargetLeader),
      targetFollower = Some(targetFollower)
    )
    
    shouldChange should be(true)
  }

  test("MOBIL lane change evaluation - unsafe change") {
    val vehicle = createTestVehicle(speed = 30.0, position = 100.0)
    val currentLeader = createTestVehicle(speed = 25.0, position = 150.0)
    val targetLeader = createTestVehicle(speed = 35.0, position = 110.0) // Muito próximo
    val targetFollower = createTestVehicle(speed = 35.0, position = 95.0) // Muito próximo e rápido
    
    // Mudança insegura deve ser rejeitada
    val shouldChange = TrafficModels.evaluateLaneChange(
      vehicle = vehicle,
      targetLane = 1,
      currentLeader = Some(currentLeader),
      targetLeader = Some(targetLeader),
      targetFollower = Some(targetFollower)
    )
    
    shouldChange should be(false)
  }

  test("Vehicle kinematics update") {
    val vehicle = createTestVehicle(speed = 20.0, position = 100.0)
    val acceleration = 2.0
    val timestep = 0.1
    
    val updatedVehicle = TrafficModels.updateVehicleKinematics(vehicle, acceleration, timestep)
    
    // Velocidade deve aumentar
    updatedVehicle.speed should be(20.2 +- 0.01)
    
    // Posição deve aumentar
    updatedVehicle.position should be > vehicle.position
    
    // Aceleração deve ser armazenada
    updatedVehicle.acceleration should be(acceleration)
  }

  test("Vehicle kinematics update - negative acceleration") {
    val vehicle = createTestVehicle(speed = 20.0, position = 100.0)
    val acceleration = -3.0
    val timestep = 0.1
    
    val updatedVehicle = TrafficModels.updateVehicleKinematics(vehicle, acceleration, timestep)
    
    // Velocidade deve diminuir
    updatedVehicle.speed should be(19.7 +- 0.01)
    
    // Velocidade não deve ficar negativa
    updatedVehicle.speed should be >= 0.0
  }

  test("Find leader in lane") {
    val vehicles = List(
      createTestVehicle(position = 50.0, lane = 0),
      createTestVehicle(position = 150.0, lane = 0),
      createTestVehicle(position = 200.0, lane = 0),
      createTestVehicle(position = 100.0, lane = 1) // Faixa diferente
    )
    
    val leader = TrafficModels.findLeader(vehiclePosition = 100.0, lane = 0, vehicles = vehicles)
    
    leader should be(defined)
    leader.get.position should be(150.0) // Primeiro à frente
  }

  test("Find follower in lane") {
    val vehicles = List(
      createTestVehicle(position = 50.0, lane = 0),
      createTestVehicle(position = 150.0, lane = 0),
      createTestVehicle(position = 200.0, lane = 0),
      createTestVehicle(position = 100.0, lane = 1) // Faixa diferente
    )
    
    val follower = TrafficModels.findFollower(vehiclePosition = 100.0, lane = 0, vehicles = vehicles)
    
    follower should be(defined)
    follower.get.position should be(50.0) // Último atrás
  }

  test("Adequate gap check - sufficient space") {
    val vehicle = createTestVehicle(position = 100.0)
    val leader = createTestVehicle(position = 120.0)
    val follower = createTestVehicle(position = 80.0)
    
    val hasGap = TrafficModels.hasAdequateGap(vehicle, Some(leader), Some(follower))
    
    hasGap should be(true)
  }

  test("Adequate gap check - insufficient space") {
    val vehicle = createTestVehicle(position = 100.0)
    val leader = createTestVehicle(position = 105.0) // Muito próximo
    val follower = createTestVehicle(position = 98.0) // Muito próximo
    
    val hasGap = TrafficModels.hasAdequateGap(vehicle, Some(leader), Some(follower))
    
    hasGap should be(false)
  }

  test("IDM parameters validation") {
    val vehicle = createTestVehicle()
    
    // Parâmetros devem estar em faixas realistas
    vehicle.maxAcceleration should be > 0.0
    vehicle.maxAcceleration should be < 5.0
    
    vehicle.desiredDeceleration should be > 0.0
    vehicle.desiredDeceleration should be < 10.0
    
    vehicle.desiredSpeed should be > 0.0
    vehicle.desiredSpeed should be < 50.0 // ~180 km/h
    
    vehicle.timeHeadway should be > 0.5
    vehicle.timeHeadway should be < 5.0
    
    vehicle.minimumGap should be > 0.0
    vehicle.minimumGap should be < 10.0
  }

  test("MOBIL parameters validation") {
    val vehicle = createTestVehicle()
    
    // Parâmetros devem estar em faixas realistas
    vehicle.politenessFactor should be >= 0.0
    vehicle.politenessFactor should be <= 1.0
    
    vehicle.laneChangeThreshold should be > 0.0
    vehicle.laneChangeThreshold should be < 1.0
    
    vehicle.maxSafeDeceleration should be > 0.0
    vehicle.maxSafeDeceleration should be < 15.0
  }

  test("Acceleration limits") {
    val vehicle = createTestVehicle(maxAcceleration = 2.0, maxSafeDeceleration = 4.0)
    
    // Teste aceleração extrema
    val highAccel = TrafficModels.updateVehicleKinematics(vehicle, 10.0, 0.1)
    highAccel.acceleration should be <= vehicle.maxAcceleration
    
    // Teste desaceleração extrema  
    val highDecel = TrafficModels.updateVehicleKinematics(vehicle, -10.0, 0.1)
    highDecel.acceleration should be >= -vehicle.maxSafeDeceleration
  }

  // Função auxiliar para criar veículos de teste
  private def createTestVehicle(
    vehicleId: String = "test_vehicle",
    speed: Double = 25.0,
    position: Double = 100.0,
    lane: Int = 0,
    maxAcceleration: Double = 2.0,
    desiredDeceleration: Double = 3.0,
    desiredSpeed: Double = 30.0,
    timeHeadway: Double = 1.5,
    minimumGap: Double = 2.0,
    politenessFactor: Double = 0.25,
    laneChangeThreshold: Double = 0.1,
    maxSafeDeceleration: Double = 4.0
  ): MicroVehicleState = {
    MicroVehicleState(
      vehicleId = vehicleId,
      speed = speed,
      position = position,
      acceleration = 0.0,
      lane = lane,
      actorRef = null,
      maxAcceleration = maxAcceleration,
      desiredDeceleration = desiredDeceleration,
      desiredSpeed = desiredSpeed,
      timeHeadway = timeHeadway,
      minimumGap = minimumGap,
      politenessFactor = politenessFactor,
      laneChangeThreshold = laneChangeThreshold,
      maxSafeDeceleration = maxSafeDeceleration
    )
  }
}
