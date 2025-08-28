package org.interscity.htc
package examples.microscopic

import model.mobility.entity.state.{LinkState, CarState}
import model.mobility.entity.state.enumeration.ActorTypeEnum
import core.types.Tick
import org.interscity.htc.core.enumeration.ReportTypeEnum

/**
 * Exemplo de configuração para simulação microscópica de tráfego
 * 
 * Este exemplo demonstra como configurar:
 * 1. Links com diferentes tipos de simulação (micro vs meso)
 * 2. Veículos com diferentes personalidades
 * 3. Cenários de teste específicos
 */
object MicroscopicSimulationExample {

  /**
   * Configuração de uma interseção urbana com simulação microscópica
   */
  def createUrbanIntersectionScenario(): Map[String, LinkState] = {
    Map(
      // Links de aproximação da interseção - simulação microscópica
      "north_approach" -> LinkState(
        startTick = 0,
        from = "node_n1",
        to = "intersection_center",
        length = 200.0,
        lanes = 2,
        speedLimit = 50.0 / 3.6, // 50 km/h em m/s
        capacity = 1800.0,       // veículos/hora/faixa
        freeSpeed = 50.0 / 3.6,
        simulationType = "micro",
        globalTickDuration = 1.0,
        microTimestep = 0.1
      ),
      
      "south_approach" -> LinkState(
        startTick = 0,
        from = "node_s1", 
        to = "intersection_center",
        length = 200.0,
        lanes = 2,
        speedLimit = 50.0 / 3.6,
        capacity = 1800.0,
        freeSpeed = 50.0 / 3.6,
        simulationType = "micro",
        globalTickDuration = 1.0,
        microTimestep = 0.1
      ),
      
      "east_approach" -> LinkState(
        startTick = 0,
        from = "node_e1",
        to = "intersection_center", 
        length = 150.0,
        lanes = 3,
        speedLimit = 60.0 / 3.6,
        capacity = 1900.0,
        freeSpeed = 60.0 / 3.6,
        simulationType = "micro",
        globalTickDuration = 1.0,
        microTimestep = 0.1
      ),
      
      "west_approach" -> LinkState(
        startTick = 0,
        from = "node_w1",
        to = "intersection_center",
        length = 150.0,
        lanes = 3, 
        speedLimit = 60.0 / 3.6,
        capacity = 1900.0,
        freeSpeed = 60.0 / 3.6,
        simulationType = "micro",
        globalTickDuration = 1.0,
        microTimestep = 0.1
      ),
      
      // Links distantes - simulação mesoscópica para performance
      "north_feeder" -> LinkState(
        startTick = 0,
        from = "node_n_origin",
        to = "node_n1",
        length = 1000.0,
        lanes = 2,
        speedLimit = 70.0 / 3.6,
        capacity = 2000.0,
        freeSpeed = 70.0 / 3.6,
        simulationType = "meso" // Mesoscópico
      ),
      
      "south_feeder" -> LinkState(
        startTick = 0,
        from = "node_s_origin",
        to = "node_s1",
        length = 1000.0,
        lanes = 2,
        speedLimit = 70.0 / 3.6,
        capacity = 2000.0,
        freeSpeed = 70.0 / 3.6,
        simulationType = "meso"
      )
    )
  }

  /**
   * Configuração de uma rodovia com gargalo - simulação microscópica
   */
  def createHighwayBottleneckScenario(): Map[String, LinkState] = {
    Map(
      // Seção antes do gargalo - mesoscópica
      "highway_upstream" -> LinkState(
        startTick = 0,
        from = "hw_start",
        to = "bottleneck_start",
        length = 2000.0,
        lanes = 3,
        speedLimit = 120.0 / 3.6,
        capacity = 2200.0,
        freeSpeed = 120.0 / 3.6,
        simulationType = "meso"
      ),
      
      // Gargalo - microscópica para capturar trocas de faixa
      "highway_bottleneck" -> LinkState(
        startTick = 0,
        from = "bottleneck_start",
        to = "bottleneck_end",
        length = 500.0,
        lanes = 2, // Redução de 3 para 2 faixas
        speedLimit = 100.0 / 3.6,
        capacity = 1800.0,
        freeSpeed = 100.0 / 3.6,
        simulationType = "micro",
        globalTickDuration = 1.0,
        microTimestep = 0.05 // Alta resolução para capturar trocas de faixa
      ),
      
      // Seção após o gargalo - mesoscópica
      "highway_downstream" -> LinkState(
        startTick = 0,
        from = "bottleneck_end",
        to = "hw_end",
        length = 2000.0,
        lanes = 3,
        speedLimit = 120.0 / 3.6,
        capacity = 2200.0,
        freeSpeed = 120.0 / 3.6,
        simulationType = "meso"
      )
    )
  }

  /**
   * Criação de veículos com diferentes personalidades
   */
  def createVehiclePersonalities(): Map[String, CarState] = {
    Map(
      // Motorista agressivo
      "aggressive_driver" -> CarState(
        startTick = 0,
        name = "Aggressive Car",
        origin = "origin_1",
        destination = "dest_1",
        currentNode = "origin_1",
        lastNode = "origin_1",
        actorType = ActorTypeEnum.Car,
        size = 4.5,
        
        // Parâmetros IDM para comportamento agressivo
        maxAcceleration = 3.0,      // Acelera rapidamente
        desiredDeceleration = 4.0,   // Freia bruscamente
        desiredSpeed = 35.0,         // Velocidade alta
        timeHeadway = 1.0,           // Distância pequena
        minimumGap = 1.5,            // Gap mínimo pequeno
        
        // Parâmetros MOBIL para trocas agressivas
        politenessFactor = 0.1,      // Pouco educado
        laneChangeThreshold = 0.05,  // Troca facilmente
        maxSafeDeceleration = 5.0,   // Aceita desaceleração alta
        aggressiveness = 0.9
      ),
      
      // Motorista conservador
      "conservative_driver" -> CarState(
        startTick = 0,
        name = "Conservative Car",
        origin = "origin_2",
        destination = "dest_2",
        currentNode = "origin_2",
        lastNode = "origin_2",
        actorType = ActorTypeEnum.Car,
        size = 4.5,
        
        // Parâmetros IDM para comportamento conservador
        maxAcceleration = 1.5,       // Acelera suavemente
        desiredDeceleration = 2.5,   // Freia suavemente
        desiredSpeed = 22.0,         // Velocidade moderada
        timeHeadway = 2.5,           // Distância grande
        minimumGap = 3.0,            // Gap mínimo grande
        
        // Parâmetros MOBIL para trocas cuidadosas
        politenessFactor = 0.5,      // Muito educado
        laneChangeThreshold = 0.3,   // Só troca se muita vantagem
        maxSafeDeceleration = 3.0,   // Evita desaceleração alta
        aggressiveness = 0.1
      ),
      
      // Motorista normal
      "normal_driver" -> CarState(
        startTick = 0,
        name = "Normal Car",
        origin = "origin_3",
        destination = "dest_3",
        currentNode = "origin_3",
        lastNode = "origin_3",
        actorType = ActorTypeEnum.Car,
        size = 4.5,
        
        // Parâmetros IDM padrão
        maxAcceleration = 2.0,
        desiredDeceleration = 3.0,
        desiredSpeed = 30.0,
        timeHeadway = 1.5,
        minimumGap = 2.0,
        
        // Parâmetros MOBIL padrão
        politenessFactor = 0.25,
        laneChangeThreshold = 0.15,
        maxSafeDeceleration = 4.0,
        aggressiveness = 0.5
      ),
      
      // Caminhão (veículo pesado)
      "truck_driver" -> CarState(
        startTick = 0,
        name = "Truck",
        origin = "origin_4",
        destination = "dest_4",
        currentNode = "origin_4",
        lastNode = "origin_4",
        actorType = ActorTypeEnum.Car, // Usando Car por simplicidade
        size = 12.0, // Veículo maior
        
        // Parâmetros IDM para veículo pesado
        maxAcceleration = 0.8,       // Acelera lentamente
        desiredDeceleration = 2.0,   // Freia lentamente
        desiredSpeed = 25.0,         // Velocidade mais baixa
        timeHeadway = 2.0,           // Distância moderada
        minimumGap = 3.5,            // Gap maior por ser maior
        
        // Parâmetros MOBIL para trocas conservadoras
        politenessFactor = 0.4,      // Relativamente educado
        laneChangeThreshold = 0.4,   // Troca só quando necessário
        maxSafeDeceleration = 2.5,   // Evita frenagem brusca
        aggressiveness = 0.2
      )
    )
  }

  /**
   * Configuração de um teste de calibração
   */
  def createCalibrationScenario(): (LinkState, List[CarState]) = {
    val testLink = LinkState(
      startTick = 0,
      from = "calib_start",
      to = "calib_end", 
      length = 1000.0,
      lanes = 2,
      speedLimit = 80.0 / 3.6,
      capacity = 2000.0,
      freeSpeed = 80.0 / 3.6,
      simulationType = "micro",
      globalTickDuration = 1.0,
      microTimestep = 0.1
    )
    
    // Criar população diversificada de veículos
    val vehicles = (1 to 50).map { i =>
      val aggressiveness = scala.util.Random.nextDouble()
      
      CarState(
        startTick = i * 2, // Espaçamento temporal
        name = s"Vehicle_$i",
        origin = "calib_start",
        destination = "calib_end",
        currentNode = "calib_start",
        lastNode = "calib_start",
        actorType = ActorTypeEnum.Car,
        size = 4.0 + scala.util.Random.nextGaussian() * 0.5,
        
        // Parâmetros variados baseados em distribuições realistas
        maxAcceleration = 1.5 + aggressiveness * 1.5 + scala.util.Random.nextGaussian() * 0.2,
        desiredDeceleration = 2.0 + aggressiveness * 2.0 + scala.util.Random.nextGaussian() * 0.3,
        desiredSpeed = 22.0 + aggressiveness * 8.0 + scala.util.Random.nextGaussian() * 2.0,
        timeHeadway = 2.5 - aggressiveness * 1.0 + scala.util.Random.nextGaussian() * 0.3,
        minimumGap = 2.0 + scala.util.Random.nextGaussian() * 0.4,
        
        politenessFactor = 0.5 - aggressiveness * 0.3 + scala.util.Random.nextGaussian() * 0.1,
        laneChangeThreshold = 0.1 + aggressiveness * 0.2 + scala.util.Random.nextGaussian() * 0.05,
        maxSafeDeceleration = 3.0 + aggressiveness * 2.0 + scala.util.Random.nextGaussian() * 0.5,
        aggressiveness = aggressiveness
      )
    }.toList
    
    (testLink, vehicles)
  }

  /**
   * Configuração para teste de comparação micro vs meso
   */
  def createComparisonScenario(): (LinkState, LinkState) = {
    val baseConfig = LinkState(
      startTick = 0,
      from = "test_start",
      to = "test_end",
      length = 1000.0,
      lanes = 3,
      speedLimit = 100.0 / 3.6,
      capacity = 2000.0,
      freeSpeed = 100.0 / 3.6
    )
    
    val microLink = baseConfig.copy(
      simulationType = "micro",
      globalTickDuration = 1.0,
      microTimestep = 0.1
    )
    
    val mesoLink = baseConfig.copy(
      simulationType = "meso"
    )
    
    (microLink, mesoLink)
  }
}
