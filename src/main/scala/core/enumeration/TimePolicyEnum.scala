package org.interscity.htc
package core.enumeration

/** Enumeration das políticas de tempo disponíveis na simulação multi-paradigma
  *
  * Define qual LocalTimeManager (LTM) será responsável por gerenciar o tempo dos atores registrados
  * com cada política.
  */
object TimePolicyEnum extends Enumeration {
  type TimePolicyEnum = Value

  /** DiscreteEventSimulation
    *   - Eventos são processados em ordem cronológica
    *   - Ideal para sistemas orientados a eventos
    *   - Gerenciado pelo DiscreteEventSimulationTimeManager
    */
  val DiscreteEventSimulation = Value("DiscreteEventSimulation")

  /** TimeSteppedSimulation
    *   - Simulação baseada em passos de tempo fixos
    *   - Todos os atores avançam sincronizadamente
    *   - Ideal para modelos de mobilidade e sistemas que precisam de sincronização temporal
    *   - Gerenciado pelo TimeSteppedSimulationTimeManager
    */
  val TimeSteppedSimulation = Value("TimeSteppedSimulation")

  /** OptimisticSimulation (implementação futura)
    *   - Simulação otimística com janelas de tempo
    *   - Permite processamento otimístico com rollback
    *   - Gerenciado pelo OptimisticSimulationTimeManager
    */
  val OptimisticSimulation = Value("OptimisticSimulation")

  /** Auto (política padrão)
    *   - O sistema escolhe automaticamente baseado no tipo do ator
    *   - Fallback para DiscreteEventSimulation se não conseguir determinar
    */
  val Auto = Value("Auto")

  /** Converte string para TimePolicyEnum
    */
  def fromString(str: String): TimePolicyEnum =
    str.toLowerCase match {
      case "des" | "discrete" | "event" | "discreteeventsimulation" => DiscreteEventSimulation
      case "timestepped" | "time-stepped" | "stepped" | "timesteppedsimulation" =>
        TimeSteppedSimulation
      case "timewindow" | "time-window" | "window" | "optimistic" | "optimisticsimulation" =>
        OptimisticSimulation
      case "auto" | "automatic" => Auto
      case _ =>
        println(s"Política de tempo desconhecida: $str. Usando Auto como fallback.")
        Auto
    }

  /** Determina política automática baseada no nome da classe do ator
    */
  def autoDetect(actorClassName: String): TimePolicyEnum = {
    val className = actorClassName.toLowerCase

    // Heurísticas para determinar política baseada no nome da classe
    if (
      className.contains("car") ||
      className.contains("vehicle") ||
      className.contains("link") ||
      className.contains("node") ||
      className.contains("mobility") ||
      className.contains("traffic")
    ) {
      TimeSteppedSimulation // Modelos de mobilidade usam TimeSteppedSimulation
    } else if (
      className.contains("sensor") ||
      className.contains("device") ||
      className.contains("iot")
    ) {
      DiscreteEventSimulation // Dispositivos IoT usam DiscreteEventSimulation
    } else {
      DiscreteEventSimulation // Default para DiscreteEventSimulation
    }
  }

  /** Retorna descrição da política
    */
  def getDescription(policy: TimePolicyEnum): String =
    policy match {
      case DiscreteEventSimulation =>
        "Discrete Event Simulation - eventos processados em ordem cronológica"
      case TimeSteppedSimulation =>
        "Time-Stepped Simulation - passos de tempo sincronizados entre atores"
      case OptimisticSimulation =>
        "Optimistic Simulation - simulação otimística com janelas de tempo"
      case Auto => "Automático - sistema escolhe baseado no tipo do ator"
    }
}
