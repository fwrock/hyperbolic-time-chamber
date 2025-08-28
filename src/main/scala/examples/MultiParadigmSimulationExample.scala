package org.interscity.htc
package examples

import core.actor.manager.{ GlobalTimeManager, TimeManagerRouter }
import core.actor.manager.time.{ DiscreteEventSimulationTimeManager, TimeSteppedSimulationTimeManager, OptimisticSimulationTimeManager }
import core.enumeration.TimePolicyEnum
import core.types.Tick
import model.mobility.actor.{ TimeSteppedCar, TimeSteppedLink }

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.htc.protobuf.core.entity.event.control.execution.{ RegisterActorEvent, StartSimulationTimeEvent }

/**
 * Exemplo de uso da arquitetura multi-paradigma do Hyperbolic Time Chamber
 * 
 * Este exemplo demonstra como:
 * 1. Configurar o GlobalTimeManager e LocalTimeManagers
 * 2. Registrar atores com diferentes políticas de tempo
 * 3. Executar uma simulação multi-paradigma
 */
object MultiParadigmSimulationExample {

  /**
   * Configuração completa da arquitetura multi-paradigma
   */
  def setupMultiParadigmArchitecture(
    system: ActorSystem,
    simulationDuration: Tick = 1000
  ): ActorRef = {
    
    println("=== Configurando Arquitetura Multi-Paradigma ===")
    
    // Criar o SimulationManager (mock para este exemplo)
    val simulationManager = system.actorOf(
      Props.empty,
      name = "ExampleSimulationManager"
    )
    
    // Criar o TimeManagerRouter que gerencia toda a arquitetura
    val timeManagerRouter: ActorRef = system.actorOf(
      TimeManagerRouter.props(simulationDuration, simulationManager),
      name = "TimeManagerRouter"
    )
    
    println(s"TimeManagerRouter criado: ${timeManagerRouter.path}")
    
    timeManagerRouter
  }

  /**
   * Exemplo de registro de atores DES
   */
  def registerDESActors(timeManagerRouter: ActorRef): Unit = {
    println("\n=== Registrando Atores DES ===")
    
    // Exemplo: sensores IoT operam em modo DES
    val sensorProperties = Properties(
      entityId = "sensor-001",
      timePolicy = Some(TimePolicyEnum.DiscreteEventSimulation)
    )
    
    // Registrar ator DES
    timeManagerRouter ! RegisterActorEvent(
      startTick = 0,
      actorId = "sensor-001",
      identify = Some(
        org.htc.protobuf.core.entity.actor.Identify(
          id = "sensor-001",
          resourceId = "iot-resource",
          classType = "org.example.SensorActor",
          actorRef = "/user/sensor-001"
        )
      )
    )
    
    println("Sensor IoT registrado no DES_LTM")
  }

  /**
   * Exemplo de registro de atores TimeStepped (mobilidade)
   */
  def registerTimeSteppedActors(timeManagerRouter: ActorRef): Unit = {
    println("\n=== Registrando Atores TimeStepped (Mobilidade) ===")
    
    // Registrar Links (estradas)
    for (i <- 1 to 3) {
      val linkProperties = Properties(
        entityId = s"link-$i",
        timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation)
      )
      
      timeManagerRouter ! RegisterActorEvent(
        startTick = 0,
        actorId = s"link-$i",
        identify = Some(
          org.htc.protobuf.core.entity.actor.Identify(
            id = s"link-$i",
            resourceId = "mobility-resource",
            classType = "org.interscity.htc.model.mobility.actor.TimeSteppedLink",
            actorRef = s"/user/link-$i"
          )
        )
      )
    }
    
    // Registrar Carros
    for (i <- 1 to 5) {
      val carProperties = Properties(
        entityId = s"car-$i",
        timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation)
      )
      
      timeManagerRouter ! RegisterActorEvent(
        startTick = 0,
        actorId = s"car-$i",
        identify = Some(
          org.htc.protobuf.core.entity.actor.Identify(
            id = s"car-$i",
            resourceId = "mobility-resource",
            classType = "org.interscity.htc.model.mobility.actor.TimeSteppedCar",
            actorRef = s"/user/car-$i"
          )
        )
      )
    }
    
    println("3 Links e 5 Carros registrados no TimeStepped_LTM")
  }

  /**
   * Exemplo de registro de atores TimeWindow (futuro)
   */
  def registerTimeWindowActors(timeManagerRouter: ActorRef): Unit = {
    println("\n=== Registrando Atores TimeWindow (Implementação Futura) ===")
    
    // Exemplo: simulação financeira com rollback otimístico
    val tradingProperties = Properties(
      entityId = "trading-agent-001",
      timePolicy = Some(TimePolicyEnum.OptimisticSimulation)
    )
    
    timeManagerRouter ! RegisterActorEvent(
      startTick = 0,
      actorId = "trading-agent-001",
      identify = Some(
        org.htc.protobuf.core.entity.actor.Identify(
          id = "trading-agent-001",
          resourceId = "financial-resource",
          classType = "org.example.TradingAgent",
          actorRef = "/user/trading-agent-001"
        )
      )
    )
    
    println("Trading Agent registrado no TimeWindow_LTM (esqueleto)")
  }

  /**
   * Iniciar simulação multi-paradigma
   */
  def startMultiParadigmSimulation(timeManagerRouter: ActorRef): Unit = {
    println("\n=== Iniciando Simulação Multi-Paradigma ===")
    
    val startEvent = StartSimulationTimeEvent(
      startTick = 0,
      data = Some(
        org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData(
          startTime = System.currentTimeMillis()
        )
      )
    )
    
    timeManagerRouter ! startEvent
    
    println("Simulação iniciada! Os paradigmas DES, TimeStepped e TimeWindow estão coordenados pelo GlobalTimeManager")
    println("\nProtocolo de Sincronização de 4 Fases:")
    println("1. Request Time: GTM solicita tempo atual de todos os LTMs")
    println("2. Propose Time: GTM calcula LBTS e propõe novo tempo")
    println("3. Grant Time: GTM concede permissão para avanço de tempo")
    println("4. Acknowledge: LTMs confirmam processamento completo")
  }

  /**
   * Demonstrar diferenças entre paradigmas
   */
  def demonstrateParadigmDifferences(): Unit = {
    println("\n=== Diferenças entre Paradigmas ===")
    
    println("\n1. DES (Discrete Event Simulation):")
    println("   - Eventos processados em ordem cronológica")
    println("   - Ideal para: sensores IoT, sistemas de eventos espontâneos")
    println("   - Avanço de tempo: baseado em eventos agendados")
    
    println("\n2. TimeStepped:")
    println("   - Passos de tempo fixos e sincronizados")
    println("   - Ideal para: modelos de mobilidade, sistemas físicos")
    println("   - Avanço de tempo: passos regulares com barreira de sincronização")
    
    println("\n3. TimeWindow (implementação futura):")
    println("   - Simulação otimística com janelas de tempo")
    println("   - Ideal para: simulações financeiras, sistemas com rollback")
    println("   - Avanço de tempo: janelas otimísticas com possibilidade de rollback")
  }

  /**
   * Exemplo completo
   */
  def runExample(): Unit = {
    println("🚀 Exemplo: Arquitetura Multi-Paradigma do Hyperbolic Time Chamber\n")
    
    // Criar sistema Pekko
    implicit val system: ActorSystem = ActorSystem("MultiParadigmExample")
    
    try {
      // 1. Configurar arquitetura
      val timeManagerRouter = setupMultiParadigmArchitecture(system)
      
      // Aguardar inicialização
      Thread.sleep(1000)
      
      // 2. Registrar atores de diferentes paradigmas
      registerDESActors(timeManagerRouter)
      registerTimeSteppedActors(timeManagerRouter)
      registerTimeWindowActors(timeManagerRouter)
      
      // 3. Demonstrar diferenças
      demonstrateParadigmDifferences()
      
      // 4. Iniciar simulação
      startMultiParadigmSimulation(timeManagerRouter)
      
      // 5. Aguardar um pouco para ver a coordenação
      println("\n⏳ Aguardando execução da simulação por 10 segundos...")
      Thread.sleep(10000)
      
      println("\n✅ Exemplo concluído!")
      
    } finally {
      system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    runExample()
  }
}

/**
 * Exemplo específico para configuração de mobilidade TimeStepped
 */
object MobilityTimeSteppedExample {
  
  /**
   * Configurar cenário de mobilidade urbana com TimeStepped
   */
  def setupUrbanMobilityScenario(system: ActorSystem): Unit = {
    println("🚗 Configurando Cenário de Mobilidade Urbana TimeStepped")
    
    // Criar TimeManagerRouter
    val simulationManager = system.actorOf(Props.empty, "MobilitySimManager")
    val timeRouter = system.actorOf(
      TimeManagerRouter.props(simulationDuration = 1000, simulationManager),
      "MobilityTimeRouter"
    )
    
    // Configurar links da cidade
    val cityLinks = List(
      ("main-street-1", 1000.0, 80.0, 2, 100), // comprimento, velocidade livre, lanes, capacidade
      ("main-street-2", 800.0, 60.0, 3, 150),
      ("avenue-1", 1200.0, 100.0, 4, 200),
      ("local-road-1", 500.0, 40.0, 1, 50)
    )
    
    cityLinks.foreach { case (linkId, length, freeSpeed, lanes, capacity) =>
      timeRouter ! RegisterActorEvent(
        startTick = 0,
        actorId = linkId,
        identify = Some(
          org.htc.protobuf.core.entity.actor.Identify(
            id = linkId,
            resourceId = "mobility-links",
            classType = "org.interscity.htc.model.mobility.actor.TimeSteppedLink",
            actorRef = s"/user/$linkId"
          )
        )
      )
      println(s"  Link registrado: $linkId (${length}m, ${freeSpeed}km/h, $lanes lanes)")
    }
    
    // Configurar frota de veículos
    val vehicles = (1 to 20).map { i =>
      s"vehicle-$i"
    }
    
    vehicles.zipWithIndex.foreach { case (vehicleId, i) =>
      timeRouter ! RegisterActorEvent(
        startTick = i * 10, // Entrada escalonada
        actorId = vehicleId,
        identify = Some(
          org.htc.protobuf.core.entity.actor.Identify(
            id = vehicleId,
            resourceId = "mobility-vehicles",
            classType = "org.interscity.htc.model.mobility.actor.TimeSteppedCar",
            actorRef = s"/user/$vehicleId"
          )
        )
      )
    }
    
    println(s"  ${vehicles.size} veículos registrados com entrada escalonada")
    
    // Iniciar simulação
    timeRouter ! StartSimulationTimeEvent(
      startTick = 0,
      data = Some(
        org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData(
          startTime = System.currentTimeMillis()
        )
      )
    )
    
    println("🎯 Simulação de mobilidade TimeStepped iniciada!")
    println("   - Todos os veículos avançam sincronizadamente")
    println("   - Links calculam densidade e velocidade em cada tick")
    println("   - Barreira de sincronização garante coordenação temporal")
  }
}

/**
 * Comparação entre paradigmas DES vs TimeStepped para mobilidade
 */
object ParadigmComparisonExample {
  
  def compareParadigms(): Unit = {
    println("📊 Comparação: DES vs TimeStepped para Simulação de Mobilidade\n")
    
    println("🔸 DES (Implementação Original):")
    println("   ✅ Vantagens:")
    println("      - Precisão temporal exata")
    println("      - Eficiente para eventos esparsos")
    println("      - Menor overhead computacional")
    println("   ❌ Desvantagens:")
    println("      - Difícil sincronização entre veículos")
    println("      - Complexo para modelos de tráfego denso")
    println("      - Ordem de eventos pode afetar resultados")
    
    println("\n🔸 TimeStepped (Nova Implementação):")
    println("   ✅ Vantagens:")
    println("      - Sincronização perfeita entre veículos")
    println("      - Ideal para modelos de tráfego")
    println("      - Facilita análise de densidade temporal")
    println("      - Comportamento determinístico")
    println("   ❌ Desvantagens:")
    println("      - Overhead de sincronização")
    println("      - Pode ser menos eficiente para eventos esparsos")
    println("      - Granularidade temporal fixa")
    
    println("\n🎯 Recomendações de Uso:")
    println("   📱 DES: IoT, sensores, eventos espontâneos")
    println("   🚗 TimeStepped: mobilidade, tráfego, sistemas físicos")
    println("   💰 TimeWindow: simulações financeiras, sistemas com rollback")
  }
}

/**
 * Guia de migração da implementação original para multi-paradigma
 */
object MigrationGuide {
  
  def showMigrationSteps(): Unit = {
    println("🔄 Guia de Migração: Implementação Original → Multi-Paradigma\n")
    
    println("📝 Passo 1: Atualizar TimeManager")
    println("   Antes: TimeManager único para toda simulação")
    println("   Depois: TimeManagerRouter → GlobalTimeManager → LocalTimeManagers")
    println("   Código: Substituir TimeManager por TimeManagerRouter\n")
    
    println("📝 Passo 2: Adicionar Política de Tempo nos Atores")
    println("   Antes: Todos os atores usavam DES implicitamente")
    println("   Depois: Especificar timePolicy nas Properties")
    println("   Código: Properties(..., timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation))\n")
    
    println("📝 Passo 3: Migrar Atores de Mobilidade")
    println("   Antes: Car extends Movable → DES com SpontaneousEvent")
    println("   Depois: TimeSteppedCar extends TimeSteppedMovable → TimeStepped com AdvanceToTick")
    println("   Código: Substituir Car por TimeSteppedCar\n")
    
    println("📝 Passo 4: Atualizar Criação de Atores")
    println("   Antes: CreatorManager → TimeManager direto")
    println("   Depois: CreatorManager → TimeManagerRouter → LTM apropriado")
    println("   Código: Routing automático baseado em heurísticas\n")
    
    println("✅ Compatibilidade: Implementação original continua funcionando!")
    println("   - DES_LTM mantém comportamento original")
    println("   - Atores existentes são automaticamente roteados para DES_LTM")
    println("   - Migração pode ser gradual, paradigma por paradigma")
  }
}
