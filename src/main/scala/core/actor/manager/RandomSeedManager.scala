package org.interscity.htc
package core.actor.manager

import core.entity.configuration.Simulation

import java.util.{ Random => JavaRandom }
import scala.util.{ Random => ScalaRandom }

/** Gerenciador de seeds para garantir reprodutibilidade determinística
  */
object RandomSeedManager {

  private var javaRandom: Option[JavaRandom] = None
  private var scalaRandom: Option[ScalaRandom] = None
  private var currentSeed: Option[Long] = None

  /** Inicializa os geradores de random com seed baseado na configuração da simulação
    * @param simulation
    *   Configuração da simulação
    */
  def initialize(simulation: Simulation): Unit = {
    val seed = simulation.randomSeed.getOrElse(System.currentTimeMillis())

    println(s"Configurando random seed: $seed")

    ScalaRandom.setSeed(seed)
    System.setProperty("java.util.Random.seed", seed.toString)

    javaRandom = Some(new JavaRandom(seed))
    scalaRandom = Some(new ScalaRandom(new JavaRandom(seed)))
    currentSeed = Some(seed)

    println(s"Random seed $seed configurado para reprodutibilidade")
  }

  /** Gera UUID determinístico baseado no seed + contador
    */
  private var uuidCounter: Long = 0

  def deterministicUUID(): String = {
    uuidCounter += 1
    val seedPart = currentSeed.getOrElse(0L)
    val uuidValue = seedPart + uuidCounter
    f"htc-$uuidValue%016x-$uuidCounter%08x"
  }

  /** Gera ID determinístico para simulação
    */
  def deterministicSimulationId(simulationName: String): String = {
    val seedPart = currentSeed.getOrElse(System.currentTimeMillis())
    s"${simulationName}_seed_${seedPart}"
  }

  /** Obter instância determinística do Java Random
    */
  def getJavaRandom(): JavaRandom =
    javaRandom.getOrElse {
      println("⚠️ Random não inicializado! Usando seed padrão")
      initialize(createDefaultSimulation())
      javaRandom.get
    }

  /** Obter instância determinística do Scala Random
    */
  def getScalaRandom(): ScalaRandom =
    scalaRandom.getOrElse {
      println("⚠️ Random não inicializado! Usando seed padrão")
      initialize(createDefaultSimulation())
      scalaRandom.get
    }

  /** Retorna o seed atual sendo usado
    */
  def getCurrentSeed(): Option[Long] = currentSeed

  /** Cria simulação padrão com seed fixo para casos de emergência
    */
  private def createDefaultSimulation(): Simulation = {
    import core.types.Tick
    import java.time.LocalDateTime

    Simulation(
      name = "default",
      description = "Default simulation for random seed initialization",
      id = None,
      startTick = 0L,
      startRealTime = LocalDateTime.now(),
      timeUnit = "seconds",
      timeStep = 1L,
      duration = 100L,
      randomSeed = Some(42L),
      actorsDataSources = List.empty
    )
  }

  /** Reset completo do sistema (útil para testes)
    */
  def reset(): Unit = {
    javaRandom = None
    scalaRandom = None
    currentSeed = None
    uuidCounter = 0
    println("🔄 RandomSeedManager resetado")
  }

  /** Informações de debug sobre o estado atual
    */
  def debugInfo(): String =
    s"""RandomSeedManager Status:
       |  Seed: ${currentSeed.getOrElse("Not initialized")}
       |  UUID Counter: $uuidCounter
       |  Java Random: ${if (javaRandom.isDefined) "Initialized" else "Not initialized"}
       |  Scala Random: ${if (scalaRandom.isDefined) "Initialized" else "Not initialized"}
       |""".stripMargin
}
