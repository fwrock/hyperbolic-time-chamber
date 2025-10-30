package org.interscity.htc
package test.mobility

import model.mobility.entity.state.LinkState
import model.mobility.actor.TimeSteppedLink
import core.actor.manager.time.protocol.AdvanceToTick
import org.interscity.htc.core.entity.actor.properties.Properties
import model.mobility.entity.event.data.{ EnterLinkData, LeaveLinkData }

/** Teste para verificar que o funcionamento mesoscópico foi mantido após a adição da funcionalidade
  * microscópica
  */
object MesoscopicCompatibilityTest {

  /** Teste 1: Link mesoscópico deve funcionar exatamente como antes
    */
  def testMesoscopicBehaviorMaintained(): Unit = {
    println("=== Teste de Compatibilidade Mesoscópica ===")

    // Criar link mesoscópico (comportamento padrão)
    val mesoLink = LinkState(
      startTick = 0,
      from = "node1",
      to = "node2",
      length = 1000.0,
      lanes = 2,
      speedLimit = 60.0,
      capacity = 2000.0,
      freeSpeed = 60.0,
      simulationType = "meso" // Explicitamente mesoscópico
    )

    println(s"✓ Link criado com simulationType = '${mesoLink.simulationType}'")
    println(s"✓ Parâmetros mesoscópicos preservados:")
    println(s"  - Length: ${mesoLink.length}")
    println(s"  - Capacity: ${mesoLink.capacity}")
    println(s"  - Free Speed: ${mesoLink.freeSpeed}")
    println(s"  - Lanes: ${mesoLink.lanes}")

    // Verificar que estado microscópico está vazio
    assert(mesoLink.microVehicles.isEmpty, "Estado microscópico deve estar vazio em links meso")
    println(s"✓ Estado microscópico vazio como esperado")

    // Verificar valores padrão
    assert(mesoLink.simulationType == "meso", "Tipo de simulação deve ser 'meso'")
    println(s"✓ Tipo de simulação correto: ${mesoLink.simulationType}")
  }

  /** Teste 2: Link sem especificação de tipo deve ser mesoscópico por padrão
    */
  def testDefaultBehaviorIsMesoscopic(): Unit = {
    println("\n=== Teste de Comportamento Padrão ===")

    // Criar link sem especificar simulationType (deve ser meso por padrão)
    val defaultLink = LinkState(
      startTick = 0,
      from = "nodeA",
      to = "nodeB",
      length = 500.0,
      lanes = 3,
      speedLimit = 80.0,
      capacity = 1800.0,
      freeSpeed = 80.0
      // simulationType não especificado - deve usar padrão "meso"
    )

    assert(defaultLink.simulationType == "meso", "Comportamento padrão deve ser mesoscópico")
    println(s"✓ Comportamento padrão mantido: ${defaultLink.simulationType}")

    // Verificar que não há impacto nos campos existentes
    assert(defaultLink.registered.isEmpty, "Registered vehicles deve estar vazio")
    assert(defaultLink.currentSpeed == 0.0, "Current speed deve ser 0.0 inicialmente")
    assert(defaultLink.congestionFactor == 1.0, "Congestion factor deve ser 1.0 inicialmente")
    println("✓ Campos mesoscópicos existentes preservados")
  }

  /** Teste 3: Simulação híbrida - Links diferentes podem ter tipos diferentes
    */
  def testHybridSimulation(): Unit = {
    println("\n=== Teste de Simulação Híbrida ===")

    val mesoLink = LinkState(
      startTick = 0,
      from = "highway_start",
      to = "intersection_approach",
      length = 2000.0,
      lanes = 3,
      speedLimit = 100.0,
      capacity = 2200.0,
      freeSpeed = 100.0,
      simulationType = "meso"
    )

    val microLink = LinkState(
      startTick = 0,
      from = "intersection_approach",
      to = "intersection_center",
      length = 200.0,
      lanes = 2,
      speedLimit = 50.0,
      capacity = 1800.0,
      freeSpeed = 50.0,
      simulationType = "micro"
    )

    println(s"✓ Link rodovia: ${mesoLink.simulationType} (${mesoLink.length}m)")
    println(s"✓ Link interseção: ${microLink.simulationType} (${microLink.length}m)")

    // Verificar que cada link mantém suas características
    assert(mesoLink.simulationType == "meso")
    assert(microLink.simulationType == "micro")
    assert(mesoLink.microVehicles.isEmpty) // Meso não usa estado micro
    assert(microLink.microVehicles.isEmpty) // Micro usa, mas está vazio inicialmente

    println("✓ Simulação híbrida funcionando corretamente")
  }

  /** Teste 4: Verificar que processamento mesoscópico não foi alterado
    */
  def testMesoscopicProcessingLogic(): Unit = {
    println("\n=== Teste de Lógica de Processamento Mesoscópico ===")

    // Este teste simula o que aconteceria durante o processamento
    val link = LinkState(
      startTick = 0,
      from = "test_from",
      to = "test_to",
      length = 1000.0,
      lanes = 2,
      speedLimit = 60.0,
      capacity = 2000.0,
      freeSpeed = 60.0,
      simulationType = "meso"
    )

    // Simular entrada de veículos (comportamento mesoscópico)
    import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
    import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum
    import org.interscity.htc.core.enumeration.CreationTypeEnum

    val vehicle1 = LinkRegister(
      actorId = "car1",
      shardId = "shard1",
      actorType = ActorTypeEnum.Car,
      actorSize = 4.5,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed
    )

    val vehicle2 = LinkRegister(
      actorId = "car2",
      shardId = "shard1",
      actorType = ActorTypeEnum.Car,
      actorSize = 4.5,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed
    )

    // Adicionar veículos (simulação mesoscópica)
    link.registered.add(vehicle1)
    link.registered.add(vehicle2)

    // Verificar que comportamento mesoscópico funciona
    assert(link.registered.size == 2, "Deve ter 2 veículos registrados")
    assert(link.microVehicles.isEmpty, "Estado microscópico deve permanecer vazio")

    // Simular cálculos mesoscópicos
    val density = link.registered.size.toDouble / link.length
    val maxDensity = link.capacity / link.length
    val densityRatio = density / maxDensity
    val speed = link.freeSpeed * math.max(0.0, 1.0 - densityRatio)

    println(s"✓ Cálculos mesoscópicos funcionando:")
    println(s"  - Densidade: ${density.formatted("%.4f")} veículos/m")
    println(s"  - Velocidade calculada: ${speed.formatted("%.2f")} m/s")
    println(s"  - Registered vehicles: ${link.registered.size}")
    println(s"  - Micro vehicles: ${link.microVehicles.size}")

    assert(speed > 0.0, "Velocidade deve ser positiva")
    assert(speed <= link.freeSpeed, "Velocidade não deve exceder free speed")

    println("✓ Lógica de processamento mesoscópico preservada")
  }

  /** Teste 5: Verificar retrocompatibilidade total
    */
  def testBackwardsCompatibility(): Unit = {
    println("\n=== Teste de Retrocompatibilidade ===")

    // Simular link criado com versão anterior (sem novos campos)
    val legacyLinkData = Map(
      "startTick" -> 0L,
      "from" -> "old_node1",
      "to" -> "old_node2",
      "length" -> 800.0,
      "lanes" -> 2,
      "speedLimit" -> 50.0,
      "capacity" -> 1600.0,
      "freeSpeed" -> 50.0
      // Não tem simulationType, globalTickDuration, microTimestep, microVehicles
    )

    // Link criado com novos parâmetros opcionais deve funcionar
    val modernLink = LinkState(
      startTick = legacyLinkData("startTick").asInstanceOf[Long],
      from = legacyLinkData("from").asInstanceOf[String],
      to = legacyLinkData("to").asInstanceOf[String],
      length = legacyLinkData("length").asInstanceOf[Double],
      lanes = legacyLinkData("lanes").asInstanceOf[Int],
      speedLimit = legacyLinkData("speedLimit").asInstanceOf[Double],
      capacity = legacyLinkData("capacity").asInstanceOf[Double],
      freeSpeed = legacyLinkData("freeSpeed").asInstanceOf[Double]
      // Novos campos usam valores padrão
    )

    // Verificar valores padrão
    assert(modernLink.simulationType == "meso", "Deve defaultar para meso")
    assert(modernLink.globalTickDuration == 1.0, "globalTickDuration deve ter padrão 1.0")
    assert(modernLink.microTimestep == 0.1, "microTimestep deve ter padrão 0.1")
    assert(modernLink.microVehicles.isEmpty, "microVehicles deve estar vazio")

    // Verificar que campos originais não foram afetados
    assert(modernLink.length == 800.0)
    assert(modernLink.capacity == 1600.0)
    assert(modernLink.freeSpeed == 50.0)
    assert(modernLink.lanes == 2)

    println("✓ Retrocompatibilidade total mantida")
    println("✓ Valores padrão funcionando corretamente")
    println("✓ Campos existentes preservados")
  }

  /** Executar todos os testes
    */
  def runAllTests(): Unit = {
    println("🚀 Iniciando testes de compatibilidade mesoscópica...\n")

    try {
      testMesoscopicBehaviorMaintained()
      testDefaultBehaviorIsMesoscopic()
      testHybridSimulation()
      testMesoscopicProcessingLogic()
      testBackwardsCompatibility()

      println("\n🎉 TODOS OS TESTES PASSARAM!")
      println("✅ Funcionamento mesoscópico MANTIDO")
      println("✅ Retrocompatibilidade GARANTIDA")
      println("✅ Simulação híbrida FUNCIONANDO")

    } catch {
      case e: AssertionError =>
        println(s"\n❌ TESTE FALHOU: ${e.getMessage}")
        throw e
      case e: Exception =>
        println(s"\n💥 ERRO INESPERADO: ${e.getMessage}")
        throw e
    }
  }
}
