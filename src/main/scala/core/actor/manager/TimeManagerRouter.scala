package org.interscity.htc
package core.actor.manager

import core.types.Tick
import core.actor.manager.time._
import core.enumeration.TimePolicyEnum
import core.entity.state.DefaultState

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.routing.{ RoundRobinPool, SmallestMailboxPool }
import org.htc.protobuf.core.entity.event.control.execution.{ RegisterActorEvent, StartSimulationTimeEvent, StopSimulationEvent }
import org.interscity.htc.core.util.StringUtil

import scala.collection.mutable

/**
 * TimeManagerRouter - Roteador responsável por direcionar atores para os LTMs corretos
 * 
 * NOTA: Esta implementação centralizada pode se tornar um gargalo em simulações grandes.
 * Abordagens alternativas mais escaláveis:
 * 1. Usar ClusterSharding do Pekko para distribuir LTMs por nodes
 * 2. Registro direto dos atores nos LTMs apropriados durante criação
 * 3. Uso de receptionist pattern do Pekko para descoberta de serviços
 * 4. Load balancer externo baseado em consistent hashing
 * 
 * Responsabilidades:
 * - Criar e gerenciar diferentes tipos de LocalTimeManagers
 * - Rotear registros de atores baseado em sua política de tempo
 * - Servir como interface única entre o sistema de criação de atores e os LTMs
 * - Coordenar com o GlobalTimeManager
 */
class TimeManagerRouter(
  val simulationDuration: Tick,
  val simulationManager: ActorRef
) extends BaseManager[DefaultState](
      timeManager = null,
      actorId = s"TimeManagerRouter-${System.nanoTime()}"
    ) {

  // Referências para os gerenciadores usando Pekko Routing
  private var globalTimeManager: ActorRef = _
  private val localTimeManagerRouters: mutable.Map[TimePolicyEnum.TimePolicyEnum, ActorRef] = mutable.Map()
  
  // Configuração dos pools
  private val poolSizePerParadigm: Int = 5  // Número de LTMs por paradigma
  
  // Controle de estado
  private var isInitialized: Boolean = false

  override def onStart(): Unit = {
    logInfo("TimeManagerRouter iniciado - Configurando arquitetura multi-paradigma")
    initializeTimeManagers()
  }

  /**
   * Inicializa todos os gerenciadores de tempo
   */
  private def initializeTimeManagers(): Unit = {
    // 1. Criar GlobalTimeManager
    globalTimeManager = context.actorOf(
      GlobalTimeManager.props(simulationDuration, simulationManager),
      name = "GlobalTimeManager"
    )
    
    // 2. Criar pool de DiscreteEventSimulationTimeManagers usando RoundRobinPool
    val desRouter = context.actorOf(
      RoundRobinPool(poolSizePerParadigm).props(
        DiscreteEventSimulationTimeManager.props(globalTimeManager, simulationDuration, simulationManager)
      ),
      name = "DiscreteEventSimulationPool"
    )
    localTimeManagerRouters.put(TimePolicyEnum.DiscreteEventSimulation, desRouter)
    
    // 3. Criar pool de TimeSteppedSimulationTimeManagers usando SmallestMailboxPool
    val timeSteppedRouter = context.actorOf(
      SmallestMailboxPool(poolSizePerParadigm).props(
        TimeSteppedTimeManager.props(globalTimeManager, simulationDuration, simulationManager, stepSize = 1)
      ),
      name = "TimeSteppedSimulationPool"
    )
    localTimeManagerRouters.put(TimePolicyEnum.TimeSteppedSimulation, timeSteppedRouter)
    
    // 4. Criar pool de OptimisticSimulationTimeManagers usando RoundRobinPool
    val optimisticRouter = context.actorOf(
      RoundRobinPool(poolSizePerParadigm).props(
        OptimisticTimeWindowTimeManager.props(globalTimeManager, simulationDuration, simulationManager, windowSize = 100)
      ),
      name = "OptimisticSimulationPool"
    )
    localTimeManagerRouters.put(TimePolicyEnum.OptimisticSimulation, optimisticRouter)
    
    isInitialized = true
    logInfo(s"TimeManagerRouter inicializado com ${localTimeManagerRouters.size} tipos de LTM pools:")
    localTimeManagerRouters.foreach { case (policy, router) =>
      logInfo(s"  - $policy: Pool de $poolSizePerParadigm instâncias (${router.path.name})")
    }
  }

  override def handleEvent: Receive = {
    case register: RegisterActorEvent => routeActorRegistration(register)
    case start: StartSimulationTimeEvent => forwardToGlobalTimeManager(start)
    case stop: StopSimulationEvent => forwardToGlobalTimeManager(stop)
    case msg => forwardToGlobalTimeManager(msg)
  }

  /**
   * Roteia registro de ator para o LTM apropriado baseado na política de tempo
   */
  private def routeActorRegistration(register: RegisterActorEvent): Unit = {
    if (!isInitialized) {
      logWarn("TimeManagerRouter ainda não inicializado, postergando registro")
      context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(100, "milliseconds"),
        self,
        register
      )(context.dispatcher)
      return
    }

    // Determinar política de tempo do ator
    val timePolicy = determineTimePolicy(register)
    
    // Obter router do pool apropriado (Pekko faz o balanceamento automaticamente)
    localTimeManagerRouters.get(timePolicy) match {
      case Some(router) =>
        logDebug(s"Roteando ator ${register.actorId} (${timePolicy}) para pool ${router.path.name}")
        router ! register
        
      case None =>
        logError(s"Router não encontrado para política $timePolicy. Usando DES como fallback.")
        localTimeManagerRouters.get(TimePolicyEnum.DiscreteEventSimulation).foreach(_ ! register)
    }
  }

  /**
   * Determina a política de tempo do ator baseado em heurísticas
   */
  private def determineTimePolicy(register: RegisterActorEvent): TimePolicyEnum.TimePolicyEnum = {
    register.identify match {
      case Some(identify) =>
        val className = identify.classType.toLowerCase
        
        // Heurísticas para determinar política baseada no nome da classe
        if (className.contains("car") || 
            className.contains("vehicle") || 
            className.contains("link") || 
            className.contains("node") || 
            className.contains("mobility") ||
            className.contains("traffic") ||
            className.contains("bus")) {
          TimePolicyEnum.TimeSteppedSimulation // Modelos de mobilidade usam TimeSteppedSimulation
        } else if (className.contains("sensor") || 
                   className.contains("device") || 
                   className.contains("iot") ||
                   className.contains("smart")) {
          TimePolicyEnum.DiscreteEventSimulation // Dispositivos IoT usam DiscreteEventSimulation
        } else {
          TimePolicyEnum.DiscreteEventSimulation // Default para DiscreteEventSimulation
        }
      case None =>
        logWarn(s"Registro sem identify para ator ${register.actorId}, usando DiscreteEventSimulation")
        TimePolicyEnum.DiscreteEventSimulation
    }
  }

  /**
   * Encaminha mensagens para o GlobalTimeManager
   */
  private def forwardToGlobalTimeManager(message: Any): Unit = {
    if (globalTimeManager != null) {
      globalTimeManager ! message
    } else {
      logError(s"GlobalTimeManager não inicializado, não foi possível encaminhar: $message")
    }
  }

  /**
   * Obtém referência para um LTM específico (para debug/teste)
   */
  def getLTMRouter(policy: TimePolicyEnum.TimePolicyEnum): Option[ActorRef] = {
    localTimeManagerRouters.get(policy)
  }

  /**
   * Obtém referência para o GlobalTimeManager (para debug/teste)
   */
  def getGlobalTimeManager: ActorRef = globalTimeManager
}

object TimeManagerRouter {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef
  ): Props =
    Props(classOf[TimeManagerRouter], simulationDuration, simulationManager)
}
