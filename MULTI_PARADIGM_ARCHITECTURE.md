# Arquitetura Multi-Paradigma - Hyperbolic Time Chamber

Este documento descreve a nova arquitetura multi-paradigma implementada no motor de simulação Hyperbolic Time Chamber, que permite a coexistência de diferentes modelos de avanço de tempo dentro de uma única simulação.

## 📋 Visão Geral

A arquitetura multi-paradigma permite que diferentes tipos de atores operem com diferentes políticas de tempo dentro da mesma simulação:

- **DES (Discrete Event Simulation)**: Eventos processados em ordem cronológica
- **TimeStepped**: Passos de tempo fixos e sincronizados  
- **TimeWindow**: Simulação otimística com janelas de tempo (implementação futura)

## 🏗️ Arquitetura

### Componentes Principais

```
Simulação Multi-Paradigma
├── GlobalTimeManager (GTM)
│   ├── Protocolo de sincronização de 4 fases
│   ├── Controle do tempo global (LBTS)
│   └── Coordenação entre LTMs
├── LocalTimeManagers (LTMs)
│   ├── DES_LTM (Discrete Event Simulation)
│   ├── TimeStepped_LTM (Time-Stepped)
│   └── TimeWindow_LTM (Time Window - futuro)
├── TimeManagerRouter
│   ├── Roteamento de atores para LTMs
│   └── Interface única com sistema de criação
└── Atores Especializados
    ├── BaseActor (com política de tempo)
    ├── TimeSteppedMovable/Car/Link
    └── Atores DES originais
```

### Protocolo de Sincronização (4 Fases)

O GlobalTimeManager coordena o avanço de tempo através de um protocolo de 4 fases:

1. **Request Time**: GTM solicita tempo atual de todos os LTMs
2. **Propose Time**: GTM calcula LBTS e propõe novo tempo
3. **Grant Time**: GTM concede permissão para avanço de tempo
4. **Acknowledge**: LTMs confirmam processamento completo

## 🚀 Guia de Uso

### 1. Configuração Básica

```scala
import org.interscity.htc.core.actor.manager.TimeManagerRouter
import org.interscity.htc.core.enumeration.TimePolicyEnum

// Criar TimeManagerRouter (substitui o TimeManager original)
val timeManagerRouter = system.actorOf(
  TimeManagerRouter.props(simulationDuration = 1000, simulationManager),
  name = "TimeManagerRouter"
)
```

### 2. Especificar Política de Tempo

```scala
// Para atores TimeStepped (ex: mobilidade)
val properties = Properties(
  entityId = "car-001",
  timePolicy = Some(TimePolicyEnum.TimeStepped),
  // ... outros parâmetros
)

// Para atores DES (ex: sensores IoT)
val properties = Properties(
  entityId = "sensor-001", 
  timePolicy = Some(TimePolicyEnum.DES),
  // ... outros parâmetros
)

// Automático (sistema escolhe baseado no tipo)
val properties = Properties(
  entityId = "any-actor",
  timePolicy = Some(TimePolicyEnum.Auto), // ou None
  // ... outros parâmetros
)
```

### 3. Atores de Mobilidade TimeStepped

```scala
// Usar TimeSteppedCar em vez de Car
class TimeSteppedCar(properties: Properties) 
  extends TimeSteppedMovable[CarState](properties) {
  
  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    // Processar movimento no tick especificado
    // Todos os carros avançam sincronizadamente
  }
}

// Usar TimeSteppedLink em vez de Link  
class TimeSteppedLink(properties: Properties)
  extends BaseActor[LinkState](properties) {
  
  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    // Atualizar densidade e velocidade do link
    // Processar entradas/saídas de veículos
  }
}
```

## 📊 Comparação de Paradigmas

| Aspecto | DES | TimeStepped | TimeWindow |
|---------|-----|-------------|------------|
| **Avanço de Tempo** | Baseado em eventos | Passos fixos | Janelas otimísticas |
| **Sincronização** | Por evento | Barreira global | Rollback quando necessário |
| **Ideal Para** | IoT, sensores | Mobilidade, tráfego | Simulações financeiras |
| **Precisão Temporal** | Exata | Granularidade fixa | Otimística |
| **Overhead** | Baixo | Médio | Alto |

## 🔄 Guia de Migração

### Migração da Implementação Original

A nova arquitetura é **compatível** com a implementação original:

1. **TimeManager → TimeManagerRouter**: Substituição direta
2. **Atores existentes**: Automaticamente roteados para DES_LTM
3. **Migração gradual**: Paradigma por paradigma

#### Exemplo de Migração

```scala
// ANTES: Implementação original
val timeManager = system.actorOf(TimeManager.props(...))
val car = system.actorOf(Car.props(properties))

// DEPOIS: Multi-paradigma  
val timeManagerRouter = system.actorOf(TimeManagerRouter.props(...))
val car = system.actorOf(TimeSteppedCar.props(
  properties.copy(timePolicy = Some(TimePolicyEnum.TimeStepped))
))
```

## 🛠️ Implementação Técnica

### Classes Principais

- **`GlobalTimeManager`**: Coordenador central do tempo global
- **`LocalTimeManager`**: Trait base para LTMs especializados
- **`DES_LTM`**: LTM para simulação de eventos discretos
- **`TimeStepped_LTM`**: LTM para simulação time-stepped
- **`TimeManagerRouter`**: Roteador de atores para LTMs
- **`TimePolicyEnum`**: Enumeração das políticas de tempo
- **`TimeSteppedMovable`**: Classe base para atores de mobilidade time-stepped

### Eventos e Protocolos

```scala
// Protocolo de sincronização
case class TimeRequestEvent(globalTime: Tick)
case class TimeProposeEvent(proposedTime: Tick, lbts: Tick)  
case class GrantTimeAdvanceEvent(grantedTime: Tick)
case class TimeAcknowledgeEvent(processedUntilTime: Tick)

// Eventos TimeStepped
case class AdvanceToTick(targetTick: Tick)
case class TickCompleted(completedTick: Tick, actorId: String)
```

## 📈 Vantagens da Nova Arquitetura

### 1. **Flexibilidade**
- Múltiplos paradigmas em uma simulação
- Escolha do paradigma mais adequado por tipo de ator
- Extensibilidade para novos paradigmas

### 2. **Coordenação Temporal**
- Sincronização rigorosa entre paradigmas
- Garantia de ordem causal global
- LBTS (Lower Bound Time Stamp) para coordenação

### 3. **Escalabilidade**
- Distribuição de carga entre LTMs
- Paralelização dentro de cada paradigma
- Isolamento de falhas por paradigma

### 4. **Compatibilidade**
- Implementação original continua funcionando
- Migração gradual possível
- Reutilização de código existente

## 🎯 Casos de Uso

### Simulação de Mobilidade Urbana
- **Links/Nós**: TimeStepped para sincronização de tráfego
- **Veículos**: TimeStepped para movimento coordenado
- **Sensores**: DES para eventos espontâneos
- **Semáforos**: TimeStepped para coordenação temporal

### Smart Cities
- **Dispositivos IoT**: DES para eventos baseados em sensores
- **Tráfego**: TimeStepped para simulação de fluxo
- **Energia**: DES para eventos de consumo/geração
- **Comunicação**: TimeWindow para protocolos com rollback

### Simulações Financeiras (Futuro)
- **Trading**: TimeWindow para simulação otimística
- **Market Making**: DES para eventos de mercado
- **Risk Management**: TimeStepped para avaliação periódica

## 🚦 Status da Implementação

### ✅ Implementado
- [x] GlobalTimeManager com protocolo de 4 fases
- [x] DES_LTM (refatoração do TimeManager original)
- [x] TimeStepped_LTM com barreira de sincronização
- [x] TimeManagerRouter com roteamento automático
- [x] BaseActor com suporte a políticas de tempo
- [x] TimeSteppedCar e TimeSteppedLink para mobilidade
- [x] Exemplo completo e documentação

### 🔄 Em Desenvolvimento
- [ ] TimeWindow_LTM (implementação esqueleto criada)
- [ ] Otimizações de performance
- [ ] Métricas e monitoramento
- [ ] Testes unitários abrangentes

### 🔮 Planejado
- [ ] Interface web para configuração
- [ ] Visualização em tempo real
- [ ] Suporte a simulação distribuída
- [ ] Algoritmos avançados de sincronização

## 📚 Referências

- [Fujimoto, R. M. (2000). Parallel and Distributed Simulation Systems](http://www.cc.gatech.edu/~fujimoto/pads-book/)
- [Chandy, K. M., & Misra, J. (1979). Distributed simulation: A case study in design and verification of distributed programs](https://www.semanticscholar.org/paper/Distributed-simulation%3A-A-case-study-in-design-and-Chandy-Misra/8b4a4c4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4)
- [Jefferson, D. R. (1985). Virtual time](https://dl.acm.org/doi/10.1145/3916.3988)

## 🤝 Contribuição

Esta implementação serve como base para estudos e desenvolvimento de simulações multi-paradigma. Contribuições são bem-vindas para:

- Otimizações de performance
- Novos paradigmas de simulação
- Casos de uso específicos
- Testes e validação

---

**Nota**: Esta implementação demonstra a viabilidade da arquitetura multi-paradigma e pode ser estendida conforme necessidades específicas de cada simulação.
