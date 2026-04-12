# Modelo de Mobilidade Híbrido Micro-Mesoscópico: Arquitetura, Comunicação entre Atores e Modelagem Matemática

> **Documento técnico para proposta de qualificação de doutorado**
> Sistema: Hyperbolic Time Chamber (HTC)
> Versão: 2.0.0 | Scala 3.3.5 | Apache Pekko 1.4.0

---

## Sumário

1. [Visão Geral do Modelo](#1-visão-geral-do-modelo)
2. [Fundamentação Teórica](#2-fundamentação-teórica)
3. [Representação da Rede Viária](#3-representação-da-rede-viária)
4. [Hierarquia de Atores do Modelo de Mobilidade](#4-hierarquia-de-atores-do-modelo-de-mobilidade)
5. [O Modelo Mesoscópico (MESO)](#5-o-modelo-mesoscópico-meso)
6. [O Modelo Microscópico (MICRO)](#6-o-modelo-microscópico-micro)
7. [A Hibridização: Transição entre Modos](#7-a-hibridização-transição-entre-modos)
8. [Protocolos de Comunicação entre Atores](#8-protocolos-de-comunicação-entre-atores)
9. [O Modelo Centrado em Pessoas (Person-Centric)](#9-o-modelo-centrado-em-pessoas-person-centric)
10. [Semaforização e Controle de Interseções](#10-semaforização-e-controle-de-interseções)
11. [Transporte Público: Ônibus e Metrô](#11-transporte-público-ônibus-e-metrô)
12. [Roteamento Dinâmico com Pesos em Tempo Real](#12-roteamento-dinâmico-com-pesos-em-tempo-real)
13. [Modelagem de Diferentes Tipos de Veículos](#13-modelagem-de-diferentes-tipos-de-veículos)
14. [Sistema de Métricas e Relatórios (SUMO-Compatible)](#14-sistema-de-métricas-e-relatórios-sumo-compatible)
15. [Padrões de Projeto e Decisões de Engenharia](#15-padrões-de-projeto-e-decisões-de-engenharia)
16. [Limitações e Trabalhos Futuros](#16-limitações-e-trabalhos-futuros)

---

## 1. Visão Geral do Modelo

O Hyperbolic Time Chamber (HTC) implementa um **simulador de tráfego urbano híbrido micro-mesoscópico**, utilizando o paradigma de **simulação baseada em atores distribuídos**. O modelo opera sobre uma rede viária representada como grafo dirigido ponderado, onde cada aresta (link) pode operar independentemente em modo **mesoscópico** (MESO) ou **microscópico** (MICRO).

### 1.1. Motivação Científica

A motivação para a abordagem híbrida advém de uma limitação fundamental na simulação de tráfego urbano em larga escala:

- **Simuladores microscópicos** (e.g., SUMO) oferecem alta fidelidade na dinâmica individual de veículos, mas apresentam custo computacional proibitivo para redes com milhões de entidades.
- **Simuladores mesoscópicos** oferecem escalabilidade, mas carecem de detalhamento necessário para análises de corredores específicos, interações veículo-a-veículo e dinâmicas de troca de faixa.

O modelo híbrido proposto resolve este trade-off permitindo que **cada link da rede defina seu modo de simulação** de forma independente. Regiões de interesse (e.g., corredores BRT, interseções complexas) operam em modo MICRO com dinâmica de car-following, enquanto o restante da rede opera em modo MESO com cálculos agregados de velocidade-densidade.

### 1.2. Diferencial Arquitetural

Diferentemente de abordagens híbridas existentes na literatura (Bourrel & Lesort, 2003; Burghout, 2004), o HTC implementa a hibridização **dentro de um framework de atores distribuídos**, onde:

1. A decisão de modo é **estática por link**, definida na configuração do cenário.
2. A transição de modo é **transparente ao veículo**: o mesmo ator-veículo adapta seu comportamento ao entrar em links de diferentes modos.
3. A distribuição é **horizontal**: links MICRO não constituem gargalo computacional pois atuam como gerenciadores de tempo locais com sub-ticks internos.

---

## 2. Fundamentação Teórica

### 2.1. Modelo Mesoscópico: BPR Speed-Density

O modo MESO utiliza uma função velocidade-densidade baseada no **Bureau of Public Roads (BPR)** simplificada:

$$
v(k) = v_f \cdot \left(1 - \left(\frac{k}{k_{\text{max}}}\right)^\beta\right)^\alpha
$$

onde:
- $v(k)$ — velocidade agregada no link (m/s)
- $v_f$ — velocidade de fluxo livre (*freeSpeed*) (m/s)
- $k$ — número de veículos no link
- $k_{\text{max}}$ — capacidade máxima do link
- $\alpha = 1.0$, $\beta = 1.0$ — parâmetros de forma

O tempo de travessia é calculado como:

$$
t_{\text{travel}} = \frac{L}{v(k)}
$$

onde $L$ é o comprimento do link em metros.

**Caso limite:** quando $k \geq k_{\text{max}}$, a velocidade é fixada em $v_{\text{min}} = 1.0$ m/s, evitando deadlock.

A implementação em Scala:

```scala
def linkDensitySpeed(
  length: Double, capacity: Double, numberOfCars: Long,
  freeSpeed: Double, lanes: Int
): Double = {
  val alpha = 1.0; val beta = 1.0
  if numberOfCars >= capacity then 1.0
  else freeSpeed * math.pow(1 - math.pow(numberOfCars / capacity, beta), alpha)
}
```

### 2.2. Modelo Microscópico: Krauss Car-Following

O modo MICRO utiliza o **modelo de car-following de Krauss** (Krauss, 1998), um modelo estocástico de velocidade segura:

#### Velocidade Segura (Safe Velocity)

$$
v_{\text{safe}} = -\tau \cdot b + \sqrt{(\tau \cdot b)^2 + v_{\text{leader}}^2 + 2 \cdot b \cdot g_{\text{eff}}}
$$

onde:
- $\tau$ — tempo de reação do condutor (s)
- $b$ — desaceleração máxima (m/s²)
- $v_{\text{leader}}$ — velocidade do veículo líder (m/s)
- $g_{\text{eff}} = \max(0, g - g_{\text{min}})$ — gap efetivo (m)
- $g$ — distância ao veículo líder (m)
- $g_{\text{min}}$ — gap mínimo de segurança (m)

#### Aceleração com Estocasticidade

$$
v_{\text{target}} = \min(v_{\text{desired}}, v_{\text{safe}}, v_{\text{current}} + a_{\text{max}} \cdot \Delta t)
$$

$$
v_{\text{rand}} = v_{\text{target}} \cdot (1 - \epsilon \cdot \xi)
$$

$$
a = \frac{v_{\text{rand}} - v_{\text{current}}}{\Delta t}
$$

$$
a_{\text{final}} = \max(-b - \epsilon_a, \min(a_{\text{max}}, a))
$$

onde:
- $v_{\text{desired}}$ — velocidade desejada (m/s)
- $a_{\text{max}}$ — aceleração máxima (m/s²)
- $\Delta t$ — passo de tempo (s)
- $\epsilon$ — fator de aleatoriedade ($0.2$ por default)
- $\xi \sim U(0,1)$ — variável aleatória uniforme
- $\epsilon_a$ — margem de segurança de aceleração (0.5 m/s²)

#### Atualização Cinemática

$$
v_{t+1} = \max(0, v_t + a_{\text{final}} \cdot \Delta t)
$$

$$
x_{t+1} = x_t + \frac{v_t + v_{t+1}}{2} \cdot \Delta t
$$

A posição é atualizada usando **velocidade média** (método trapezoidal), garantindo maior precisão que a integração de Euler direta.

### 2.3. Discretização Temporal

O sistema opera com dois níveis de discretização temporal:

| Nível | Granularidade | Escopo |
|-------|--------------|--------|
| **Tick Global** | 1 segundo | Toda a simulação |
| **Sub-tick Micro** | 0.1 segundo | Links em modo MICRO |

Para cada tick global, links MICRO executam $N = 10$ sub-ticks de $\Delta t = 0.1$s, totalizando 1 segundo de simulação microscópica por tick global. Este valor é configurável por link:

$$
T_{\text{global}} = N \cdot \Delta t_{\text{micro}}
$$

---

## 3. Representação da Rede Viária

### 3.1. Grafo Dirigido Ponderado

A rede viária é representada como um grafo dirigido $G = (V, E)$:

- $V = \{v_1, v_2, ..., v_n\}$ — conjunto de nós (interseções), cada um com coordenadas geográficas $(\text{lat}, \text{lon})$
- $E = \{e_1, e_2, ..., e_m\}$ — conjunto de arestas dirigidas (links/segmentos viários)

Cada aresta $e_i \in E$ possui os atributos:

| Atributo | Tipo | Descrição |
|----------|------|-----------|
| `from` | String | Nó de origem |
| `to` | String | Nó de destino |
| `length` | Double | Comprimento em metros |
| `lanes` | Int | Número de faixas |
| `speedLimit` | Double | Velocidade máxima (m/s) |
| `capacity` | Double | Capacidade máxima de veículos |
| `freeSpeed` | Double | Velocidade de fluxo livre (m/s) |
| `simulationMode` | Enum | `MESO` ou `MICRO` |
| `microTimeStep` | Double | Passo de sub-tick ($\Delta t$), default 0.1s |
| `microTicksPerGlobalTick` | Int | Sub-ticks por tick global, default 10 |

### 3.2. Carregamento e Indexação do Mapa

O mapa é carregado em memória uma única vez durante a inicialização via `CityMapUtil`:

1. **Fonte:** Arquivo JSON definido pela variável de ambiente `HTC_MOBILITY_CITY_MAP_FILE`
2. **Estrutura:** `Graph[NodeGraph, Double, EdgeGraph]` — grafo genérico com nós tipados, pesos Double e rótulos de aresta tipados
3. **Índices:** Dois mapas de consulta O(1):
   - `nodesById: Map[String, NodeGraph]` — acesso rápido a nós
   - `edgeLabelsById: Map[String, EdgeGraph]` — acesso rápido a arestas

### 3.3. Separação de Redes

O sistema mantém **redes separadas** para diferentes modos de transporte:

- **Rede viária** (`Link`): Utilizada por carros, ônibus, bicicletas, motocicletas
- **Rede ferroviária** (`RailLink`): Exclusiva para metrô/trem — validação de tipo de veículo na entrada, sem congestionamento, velocidades mais altas

---

## 4. Hierarquia de Atores do Modelo de Mobilidade

### 4.1. Diagrama de Herança

```
BaseActor[T]                           (framework core)
  └── SimulationBaseActor[T]           (tempo, dependências, report)
        ├── Movable[T <: MovableState] (roteamento, enter/leave link)
        │     ├── Car                  (carro privado + PrivateVehicle trait)
        │     ├── Bicycle              (bicicleta + PrivateVehicle trait)
        │     ├── Motorcycle           (motocicleta + PrivateVehicle trait)
        │     ├── Bus                  (ônibus público)
        │     └── Subway               (metrô)
        ├── Link                       (segmento viário híbrido)
        ├── RailLink                   (segmento ferroviário)
        ├── Node                       (interseção)
        ├── TrafficSignal              (semáforo)
        ├── BusStation                 (estação de ônibus / roteamento)
        ├── BusStop                    (parada de ônibus)
        ├── SubwayStation              (estação de metrô)
        └── Person                     (agente pessoal)
```

### 4.2. Composição via Traits

O mecanismo de **mixins** do Scala é usado extensivamente:

- **`Movable[T]`** — Trait abstrato com toda a lógica de roteamento, entrada/saída de links e máquina de estados. É o template method pattern para entidades móveis.
- **`PrivateVehicle[T]`** — Trait que transforma um `Movable` em veículo privado com ciclo de vida gerenciado por um `Person`. Adiciona estados `Parked` e mecanismo de `StartTrip`/`TripCompleted`.

Essa composição permite que `Car`, `Bicycle` e `Motorcycle` compartilhem comportamento de routing e movimento sem duplicação, enquanto `Bus` e `Subway` (veículos públicos) não utilizam `PrivateVehicle`.

### 4.3. Estados do Veículo (Máquina de Estados Finita)

```
                   ┌─────────────────────────────────────────┐
                   │                                         │
  ┌────────┐   StartTrip   ┌───────┐   requestRoute()   ┌───────┐
  │ Parked │──────────────→│ Start │────────────────────→│ Ready │
  └────────┘               └───────┘                     └───┬───┘
                                                             │
                                                        enterLink()
                                                             │
                              ┌───────────┐              ┌───┴────┐
                              │ Moving    │←─────────────│Waiting │
                              │(MESO/MICRO)│   LinkInfo  └────────┘
                              └─────┬─────┘
                                    │ mesoExitTick || position >= length
                                    ▼
                          ┌──────────────────┐
                          │WaitingSignalState│ requestSignalState()
                          └────────┬─────────┘
                                   │ SignalStateData
                     ┌─────────────┼────────────────┐
                     ▼             │                 ▼
              ┌──────────┐        │          ┌─────────────┐
              │WaitSignal│        │          │  leavingLink │
              │(Red light)│       │          └──────┬──────┘
              └─────┬────┘        │                 │
                    │ nextTick    │            getNextPath
                    ▼             │                 │
              leavingLink ────────┘          ┌──────┴──────┐
                                             │             │
                                        ┌────┴───┐  ┌─────┴─────┐
                                        │ Ready  │  │ Finished  │
                                        │(next   │  │(destino   │
                                        │ link)  │  │ alcançado)│
                                        └────────┘  └─────┬─────┘
                                                          │
                                                   TripCompleted
                                                          │
                                                     ┌────┴───┐
                                                     │ Parked │
                                                     └────────┘
```

Os estados são definidos pelo `MovableStatusEnum`:

| Estado | Descrição |
|--------|-----------|
| `Parked` | Veículo privado inativo, aguardando ativação pelo Person |
| `Start` | Iniciando — calculando rota |
| `RouteWaiting` | Aguardando resposta de roteamento |
| `Ready` | Rota calculada, pronto para entrar no próximo link |
| `Waiting` | Aguardando resposta do link (confirmação de entrada) |
| `Moving` | Em trânsito no link (MESO: timer; MICRO: updates do link) |
| `WaitingSignalState` | Requisitou estado do semáforo ao nó |
| `WaitingSignal` | Aguardando sinal verde |
| `Stopped` | Parado (congestionamento ou obstáculo) |
| `WaitingLoadPassenger` | Bus: aguardando carregamento de passageiros |
| `WaitingUnloadPassenger` | Bus: aguardando descarregamento |
| `Finished` | Viagem concluída |

---

## 5. O Modelo Mesoscópico (MESO)

### 5.1. Conceito

No modo mesoscópico, veículos individuais existem como entidades discretas, mas a dinâmica de tráfego é **agregada ao nível do link**. O link calcula uma velocidade uniforme para todos os veículos com base na relação velocidade-densidade, e cada veículo computa seu tempo de travessia individualmente.

### 5.2. Protocolo de Comunicação (MESO)

```
Car (Vehicle Actor)                    Link Actor
      │                                    │
      │    EnterLinkData                   │
      │──────────────────────────────────→ │
      │    {actorId, shardId, actorType,   │
      │     actorSize, actorCreationType}  │
      │                                    │
      │    LinkInfoData                    │
      │ ←─────────────────────────────────│
      │    {linkLength, linkCapacity,      │
      │     linkNumberOfCars, linkFreeSpeed,│
      │     linkLanes}                     │
      │                                    │
      │  [Car calcula velocidade agregada  │
      │   e tempo de travessia]            │
      │                                    │
      │    LeaveLinkData                   │
      │──────────────────────────────────→ │
      │    {actorId, shardId, actorType}   │
      │                                    │
      │    LinkInfoData                    │
      │ ←─────────────────────────────────│
      │    {linkLength ... confirmação}    │
      │                                    │
```

### 5.3. Algoritmo de Travessia MESO

O fluxo completo no veículo ao receber `LinkInfoData` de entrada:

```
1. Receber LinkInfoData do link
2. speed = linkDensitySpeed(length, capacity, numCars, freeSpeed, lanes)
3. time = length / speed
4. exitTick = currentTick + ceil(time)
5. state.movableStatus = Moving
6. Agendar próximo evento espontâneo para exitTick
7. Quando exitTick chegar:
   a. Consultar estado do semáforo no nó destino (RequestSignalState)
   b. Se verde → leavingLink()
   c. Se vermelho → WaitingSignal até nextTick do semáforo
8. leavingLink():
   a. Enviar LeaveLinkData ao link
   b. Receber confirmação (LinkInfoData com ReceiveLeaveLinkInfo)
   c. Atualizar distância percorrida
   d. Obter próximo segmento da rota
   e. Se rota concluída → Finished
   f. Senão → enterLink() no próximo link
```

### 5.4. Responsabilidade do Link (MESO)

No modo MESO, o Link atua como um **contador passivo**:

1. Mantém um conjunto `registered: mutable.Set[LinkRegister]` de veículos presentes
2. Na entrada: registra veículo, envia `LinkInfoData` de volta
3. Na saída: remove registro, envia `LinkInfoData` de confirmação
4. **Não agenda eventos espontâneos** no modo MESO — os veículos auto-gerenciam seus timers

---

## 6. O Modelo Microscópico (MICRO)

### 6.1. Conceito

No modo microscópico, o **Link assume o papel de simulador ativo**, executando dinâmica de car-following para todos os veículos em suas faixas a cada sub-tick. O veículo, por sua vez, torna-se **passivo** — recebe atualizações cinemáticas do link.

### 6.2. Inversão de Controle: Veículo Passivo vs. Link Ativo

Esta inversão é uma decisão arquitetural fundamental:

| Aspecto | MESO | MICRO |
|---------|------|-------|
| **Quem simula** | Veículo (self-managed) | Link (gerencia todos) |
| **Eventos espontâneos** | Veículo agenda próprio timer | Link executa sub-ticks |
| **Comunicação** | Veículo → Link → Veículo | Link → Veículo (broadcast) |
| **Controle temporal** | Timer global (1 tick = 1s) | Sub-ticks locais (Δt = 0.1s) |
| **Complexidade** | O(1) por veículo | O(n) por faixa por sub-tick |

### 6.3. Protocolo de Comunicação (MICRO)

```
Car Actor                  Link Actor (MICRO mode)
    │                            │
    │    EnterLinkData           │
    │──────────────────────────→ │
    │                            │  [Link atribui faixa,
    │    MicroEnterLinkData      │   cria VehicleInLane]
    │ ←────────────────────────│
    │    {linkId, mode=MICRO,    │
    │     assignedLane,          │
    │     linkLength, speedLimit,│
    │     microTimeStep,         │
    │     ticksPerGlobalTick}    │
    │                            │
    │  [Car inicializa           │
    │   MicroCarState,           │
    │   desliga timer próprio]   │
    │                            │
    │                            │ ─── Tick Global ───
    │                            │  [Link executa N sub-ticks:
    │                            │   for each sub-tick:
    │                            │     for each lane:
    │                            │       for each vehicle:
    │                            │         1. Identificar líder
    │                            │         2. Calcular gap
    │                            │         3. Krauss: velocidade segura
    │                            │         4. Atualizar velocidade
    │                            │         5. Atualizar posição
    │    MicroUpdateData         │         6. Rastrear espera]
    │ ←────────────────────────│
    │    {position, velocity,    │
    │     acceleration, lane,    │
    │     leaderVehicle,         │
    │     gapToLeader,           │
    │     leaderVelocity,        │
    │     safeVelocity}          │
    │                            │
    │  [Car atualiza microState] │
    │  [Se position >= length:   │
    │   requestSignalState()]    │
    │                            │
    │    LeaveLinkData           │
    │──────────────────────────→ │
    │                            │
    │    MicroLeaveLinkData      │
    │ ←────────────────────────│
    │    {linkId, finalPosition, │
    │     finalVelocity,         │
    │     travelTime,            │
    │     distanceTraveled,      │
    │     averageSpeed,          │
    │     waitingTimeSeconds}    │
    │                            │
    │  [Car desativa microState, │
    │   volta ao modo MESO]      │
```

### 6.4. Algoritmo de Simulação Micro (DefaultMicroSimulationStrategy)

O link MICRO executa o seguinte procedimento a cada tick global:

```
Algorithm: handleGlobalTick(tick)
Input: tick (Tick global atual)

1. Publicar custo dinâmico se intervalo venceu
2. Para cada faixa ℓ ∈ {0, 1, ..., lanes-1}:
   a. Veículos ordenados por posição (frente → traseira)
   b. Para cada veículo v_i na faixa ℓ:
      i.   IDENTIFICAR LÍDER:
           Se i > 0: líder = v_{i-1}, gap = v_{i-1}.position - v_i.position - v_i.length
           Se i = 0 (primeiro): gap = linkLength - v_i.position, leaderVel = speedLimit/3.6
      
      ii.  CALCULAR VELOCIDADE ALVO:
           Se gap < 50m e tem líder:
             targetVel = min(leaderVel, √(2 · b · gap))  [frenagem segura]
           Senão:
             targetVel = speedLimit / 3.6  [fluxo livre]
      
      iii. SUAVIZAR ACELERAÇÃO:
           velChange = (targetVel - v_i.velocity) · 0.5 · Δt
           newVelocity = max(0, min(v_i.velocity + velChange, speedLimit/3.6))
      
      iv.  ATUALIZAR POSIÇÃO:
           newPosition = min(v_i.position + newVelocity · Δt, linkLength)
      
      v.   CLAMPING: Se position ≥ linkLength → velocity = 0
      
      vi.  RASTREAR ESPERA: Se velocity < 0.1 m/s:
           waitingSeconds[v_i] += Δt
      
      vii. GERAR UPDATE para enviar ao ator-veículo

3. Reordenar veículos em cada faixa por posição (desc)
4. Enviar MicroUpdateData para cada veículo
5. Emitir SUMO summary step
```

### 6.5. Pattern: Strategy para Simulação Micro

A simulação microscópica utiliza o **Strategy Pattern** para permitir plugabilidade:

```
trait MicroSimulationStrategy {
  def initialize(linkLength, speedLimit, lanes, microTimeStep): Unit
  def executeSubTick(...): Seq[MicroVehicleUpdate]
  def selectEntryLane(...): Int
}

trait LaneChangeStrategy {
  def shouldChangeLane(...): Option[Int]
  def isSafeLaneChange(...): Boolean
  def initialize(...): Unit
}
```

**Implementações atuais:**
- `DefaultMicroSimulationStrategy` — Krauss simplificado com processamento por faixa
- `NoLaneChangeStrategy` — Sem troca de faixa (default)

**Implementações futuras planejadas:**
- `IDMModel` — Intelligent Driver Model (Treiber, 2000)
- `GippsModel` — Modelo de Gipps
- `MobilLaneChange` — MOBIL lane change model (Kesting, 2007)

### 6.6. Gerenciamento de Faixas

Em modo MICRO, o link mantém uma estrutura de dados `vehiclesByLane: Map[Int, Queue[VehicleInLane]]`:

```scala
case class VehicleInLane(
  actorId: String,        // ID do ator-veículo
  shardId: String,        // Shard para comunicação
  position: Double,       // Posição no link (metros)
  velocity: Double,       // Velocidade (m/s)
  acceleration: Double,   // Aceleração (m/s²)
  vehicleLength: Double,  // Comprimento do veículo (m)
  entryTick: Tick          // Tick de entrada
)
```

**Atribuição de faixa:** Veículos entrantes são alocados na **faixa menos ocupada** (`selectEntryLane`), garantindo distribuição uniforme.

---

## 7. A Hibridização: Transição entre Modos

### 7.1. Mecanismo de Transição

A transição MESO↔MICRO é **transparente ao veículo** e ocorre na fronteira do link. O veículo mantém um campo `currentSimulationMode` e um `microState: Option[MicroCarState]` opcionais:

```
MESO Link A ──────→ Node ──────→ MICRO Link B ──────→ Node ──────→ MESO Link C
   │                  │              │                   │              │
   │  BPR speed-      │              │  Krauss car-      │              │  BPR speed-
   │  density         │              │  following         │              │  density
   │  calculation     │              │  + sub-ticks       │              │  calculation
   │                  │              │                    │              │
   │  Veículo         │              │  Veículo           │              │  Veículo
   │  self-managed    │              │  passivo           │              │  self-managed
   └──────────────────┘              └────────────────────┘              └──────────────
```

### 7.2. Transição MESO → MICRO (Ativação)

Quando o veículo entra em um link MICRO:

1. **Link** recebe `EnterLinkData`, detecta `isMicroMode = true`
2. **Link** atribui faixa e cria `VehicleInLane` com posição 0
3. **Link** envia `MicroEnterLinkData` ao veículo com parâmetros do link
4. **Veículo** recebe, inicializa `MicroCarState`:
   ```scala
   val initialMicroState = MicroCarState(
     positionInLink = 0.0,
     velocity = speedLimitMs * 0.8,  // Entrada a 80% da velocidade máxima
     acceleration = 0.0,
     currentLane = data.assignedLane,
     desiredVelocity = speedLimitMs
   )
   state.activateMicroMode(initialMicroState)
   ```
5. **Veículo** desliga seu timer espontâneo (`onFinishSpontaneous(None)`)
6. A partir deste ponto, o veículo é atualizado exclusivamente via `MicroUpdateData`

### 7.3. Transição MICRO → MESO (Desativação)

Quando o veículo sai de um link MICRO:

1. **Veículo** detecta `position >= linkLength` via `MicroUpdateData`
2. **Veículo** inicia procedimento de saída: `requestSignalState()` → `leavingLink()`
3. **Link** remove veículo de `vehiclesByLane`, calcula estatísticas finais
4. **Link** envia `MicroLeaveLinkData` com métricas acumuladas:
   ```scala
   MicroLeaveLinkData(
     linkId, finalPosition, finalVelocity,
     travelTime, distanceTraveled, averageSpeed,
     waitingTimeSeconds  // Tempo total parado durante travessia
   )
   ```
5. **Veículo** desativa micro estado:
   ```scala
   state.deactivateMicroMode()  // currentSimulationMode = MESO, microState = None
   ```
6. **Veículo** reativa seu timer espontâneo para o próximo tick

### 7.4. Consistência na Fronteira

A hibridização requer tratamento de condições de borda:

| Aspecto | Tratamento |
|---------|-----------|
| **Velocidade na transição** | Veículo entra no link MICRO com velocidade inicial ≤ speedLimit × 0.8 |
| **Mensagens em voo** | Guard contra MicroLeaveLink/MicroUpdate stale via `currentLinkId.contains(data.linkId)` |
| **Tick desalinhado** | Link MICRO usa `currentTick` (não `event.tick`) para agendar, evitando ticks no passado |
| **Veículo terminando em MICRO** | Guard de simulation end tanto em `actSpontaneous` quanto em `handleMicroUpdate` |
| **Destruição forçada** | `onDestruct` propaga `DestructEvent` a todos os veículos registrados |

---

## 8. Protocolos de Comunicação entre Atores

### 8.1. Taxonomia de Mensagens

O modelo de mobilidade define uma taxonomia de mensagens organizadas hierarquicamente:

```
BaseEventData
├── EnterLinkData              (Veículo → Link: solicitar entrada)
├── LeaveLinkData              (Veículo → Link: solicitar saída)
├── MicroEnterLinkData         (Link → Veículo: ACK de entrada MICRO)
├── MicroUpdateData            (Link → Veículo: atualização cinemática)
├── MicroLeaveLinkData         (Link → Veículo: ACK de saída MICRO)
├── LinkInfoData               (Link → Veículo: info do link MESO)
├── RequestSignalStateData     (Veículo → Node: consulta semáforo)
├── SignalStateData            (Node → Veículo: estado do semáforo)
├── TrafficSignalChangeStatusData (Signal → Node: mudança de fase)
├── StartTripData              (Person → Veículo: ativar veículo)
├── TripCompletedData          (Veículo → Person: viagem concluída)
├── ParkVehicleData            (Person → Veículo: estacionar)
├── RegisterBusStopData        (BusStop → Node: registrar parada)
├── BusRequestPassengerData    (Bus → BusStop: solicitar passageiros)
├── BusLoadPassengerData       (BusStop → Bus: carregar passageiros)
└── ... (subway, route, etc.)
```

### 8.2. Diagrama de Sequência Completo: Viagem de um Carro

```
Person          Car            Link(MESO)       Node         Signal       Link(MICRO)
  │               │                │              │             │              │
  │ StartTripData │                │              │             │              │
  │──────────────→│                │              │             │              │
  │               │                │              │             │              │
  │               │ [requestRoute: A* + dynamic weights]       │              │
  │               │                │              │             │              │
  │               │ EnterLinkData  │              │             │              │
  │               │───────────────→│              │             │              │
  │               │ LinkInfoData   │              │             │              │
  │               │←───────────────│              │             │              │
  │               │                │              │             │              │
  │               │ [BPR speed-density → exitTick]│             │              │
  │               │                │              │             │              │
  │               │ RequestSignalState            │             │              │
  │               │──────────────────────────────→│             │              │
  │               │                │              │             │              │
  │               │                │              │ [lookup signal state]      │
  │               │ SignalStateData│              │             │              │
  │               │←──────────────────────────────│             │              │
  │               │                │              │             │              │
  │               │ LeaveLinkData  │              │             │              │
  │               │───────────────→│              │             │              │
  │               │ LinkInfoData   │              │             │              │
  │               │←───────────────│              │             │              │
  │               │                │              │             │              │
  │               │ EnterLinkData  │              │             │              │
  │               │────────────────────────────────────────────────────────────→│
  │               │ MicroEnterLinkData            │             │              │
  │               │←───────────────────────────────────────────────────────────│
  │               │                │              │             │   [Sub-ticks]│
  │               │ MicroUpdateData│              │             │              │
  │               │←───────────────────────────────────────────────────────────│
  │               │  ... (N updates per tick)     │             │              │
  │               │                │              │             │              │
  │               │ LeaveLinkData  │              │             │              │
  │               │────────────────────────────────────────────────────────────→│
  │               │ MicroLeaveLinkData            │             │              │
  │               │←───────────────────────────────────────────────────────────│
  │               │                │              │             │              │
  │TripCompleted  │                │              │             │              │
  │←──────────────│                │              │             │              │
  │               │                │              │             │              │
```

### 8.3. Mecanismos de Resiliência

O sistema implementa diversos guards para lidar com condições de corrida em ambiente distribuído:

1. **Timeout de Waiting:** Se um veículo fica em estado `Waiting` por mais de 100 ticks, é recuperado automaticamente (avança para próximo segmento).

2. **Guard contra SignalState stale:** Se o veículo recebe `SignalStateData` mas não está em `WaitingSignalState`, a mensagem é descartada:
   ```scala
   if (state.movableStatus != WaitingSignalState) {
     logDebug("Ignoring stale SignalStateData. Race condition guard.")
     return
   }
   ```

3. **Guard contra MicroLeaveLink stale:** Verificação de `currentLinkId.contains(data.linkId)` antes de processar saída.

4. **Retry de RequestSignalState:** Até 100 tentativas antes de forçar saída do link.

5. **Guard de fim de simulação:** Verificação de `currentTick >= simulationEndTick` tanto em `actSpontaneous` quanto em `handleMicroUpdate`.

---

## 9. O Modelo Centrado em Pessoas (Person-Centric)

### 9.1. Conceito

O HTC implementa um **modelo centrado em pessoas** inspirado em simuladores activity-based (MATSim), onde o agente `Person` persiste durante toda a simulação e gerencia sua agenda diária de atividades.

### 9.2. Ciclo de Vida do Agente Person

```
┌────────────────────────────────────────────────────────┐
│                    PERSON LIFECYCLE                     │
├────────────────────────────────────────────────────────┤
│                                                        │
│   ┌──────────┐    endTime    ┌──────────────┐          │
│   │Activity 0│──────────────→│ Mode Choice  │          │
│   │  (Home)  │  reached     │ executeModeChoice()│     │
│   └──────────┘               └──────┬───────┘          │
│                                     │                  │
│                         ┌───────────┼───────────┐      │
│                         ▼           ▼           ▼      │
│                  ┌───────────┐ ┌──────────┐ ┌────────┐ │
│                  │  Car/Moto │ │  Walking │ │Transit │ │
│                  │  /Bicycle │ │          │ │(inst.) │ │
│                  └─────┬─────┘ └────┬─────┘ └───┬────┘ │
│                        │            │           │      │
│               StartTrip│    calcRoute     instant│      │
│               ─────────→    + walkTime     arrival│     │
│                        │            │           │      │
│               TripCompleted   arrivalTick       │      │
│               ←─────────      reached           │      │
│                        │            │           │      │
│                        └──────┬─────┘───────────┘      │
│                               ▼                        │
│                        ┌──────────┐                    │
│                        │Activity 1│                    │
│                        │  (Work)  │                    │
│                        └────┬─────┘                    │
│                             │ endTime                  │
│                             ▼                          │
│                        ┌──────────┐                    │
│                        │Activity 2│                    │
│                        │(Shopping)│                    │
│                        └────┬─────┘                    │
│                             │                          │
│                             ▼                          │
│                      Schedule Complete                 │
│                  (unregister from TimeManager)         │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### 9.3. Modelo de Dados do Person

```scala
case class PersonState(
  dailySchedule: List[Activity],          // Agenda diária
  currentActivityIndex: Int,               // Atividade atual
  ownedVehicles: Map[String, Identify],    // Veículos próprios (mode → referência)
  currentTripVehicleId: Option[String],    // Veículo em uso
  currentTripStartTick: Option[Tick],      // Início da viagem
  totalDistanceTraveled: Double,           // Distância total do dia
  completedTrips: Int                      // Viagens completadas
)

case class Activity(
  sequence: Int,                           // Ordem na agenda
  activityType: String,                    // "Home", "Work", "School", etc.
  nodeId: String,                          // ID do nó de localização
  endTime: String,                         // Horário de término
  arrivalLogistics: Option[ArrivalLogistics] // Como chegar aqui
)

case class ArrivalLogistics(
  mode: String,                            // "car", "bicycle", "walk", etc.
  vehicle: Option[Identify],               // Referência ao veículo privado
  instant: Boolean,                        // Skip routing se mesmo nó
  driverAttributes: DriverAttributes       // Perfil de condução
)
```

### 9.4. DriverAttributes: Heterogeneidade de Motoristas

Cada pessoa possui atributos de condução que **modificam a física do veículo** ao ativá-lo:

```scala
case class DriverAttributes(
  aggressiveness: Double = 0.5,    // [0.0 - 1.0]: afeta aceleração máxima
  maxSpeedFactor: Double = 1.0,    // [0.5 - 1.5]: multiplica velocidade desejada
  reactionTime: Double = 1.0,      // [0.5 - 2.0s]: tempo de reação
  minGapFactor: Double = 1.0       // [0.5 - 2.0]: multiplica gap mínimo
)
```

Aplicação no veículo:
$$
v_{\text{desired}}' = v_{\text{desired}} \cdot f_{\text{speed}}
$$
$$
\tau' = \tau_{\text{driver}}
$$
$$
g_{\text{min}}' = g_{\text{min}} \cdot f_{\text{gap}}
$$
$$
a_{\text{max}}' = a_{\text{max}} \cdot (0.8 + 0.4 \cdot \xi_{\text{aggr}})
$$

onde $f_{\text{speed}}$ é `maxSpeedFactor`, $f_{\text{gap}}$ é `minGapFactor`, e $\xi_{\text{aggr}} \in [0,1]$ é `aggressiveness`.

### 9.5. Protocolo Person ↔ Veículo

```
Person                        Car (PrivateVehicle)
  │                                  │
  │       StartTripData              │
  │─────────────────────────────────→│
  │  {personId, origin, destination, │
  │   driverAttributes, startTick}   │
  │                                  │ [Transition: Parked → Start]
  │                                  │ [applyDriverAttributes()]
  │                                  │ [requestRoute()]
  │                                  │ [... viagem completa ...]
  │                                  │
  │       TripCompletedData          │
  │←─────────────────────────────────│
  │  {vehicleId, personId,           │ [Transition: Finished → Parked]
  │   distanceTraveled, travelTime,  │
  │   finalNode, completionReason}   │
  │                                  │
  │  [advanceToNextActivity()]       │
  │                                  │
```

### 9.6. Caminhada Mesoscópica

Para viagens de caminhada (`mode = "walk"`), o Person calcula a rota na rede viária, soma o comprimento dos links, e determina o tempo de chegada:

$$
t_{\text{walk}} = \frac{d_{\text{total}}}{v_{\text{walk}}} \quad\text{onde } v_{\text{walk}} = 1.4 \text{ m/s}
$$

O Person agenda seu próprio evento espontâneo para o `arrivalTick`, sem ativar nenhum veículo.

---

## 10. Semaforização e Controle de Interseções

### 10.1. Modelo do Semáforo (TrafficSignal)

O ator `TrafficSignal` implementa controle de semáforo com **ciclo fixo** e **múltiplas fases**:

```scala
case class TrafficSignalState(
  cycleDuration: Tick,              // Duração total do ciclo
  offset: Tick,                     // Defasagem (para ondas verdes)
  nodes: List[String],              // Nós controlados
  phases: List[Phase],              // Fases do semáforo
  signalStates: Map[String, SignalState]  // Estado atual por fase
)

case class Phase(
  origin: String,                   // Identificador da fase
  greenStart: Tick,                 // Início do verde no ciclo
  greenDuration: Tick               // Duração do verde
)
```

### 10.2. Algoritmo de Transição de Fase

```
Algorithm: handlePhaseTransition(currentTick)

1. currentCycleTick = (currentTick - startTick + offset) % cycleDuration
2. Para cada fase p:
   a. newState = se (currentCycleTick ∈ [p.greenStart, p.greenStart + p.greenDuration])
                    então Green, senão Red
   b. Se newState ≠ estadoAtual:
      - Enviar TrafficSignalChangeStatusData para todos os nós
      - Atualizar signalStates[p.origin]
3. nextTick = startTick + ((ticksSinceStart / cycleDuration) + 1) * cycleDuration - offset
4. Agendar próximo evento para nextTick
```

### 10.3. Fluxo de Consulta de Semáforo

```
Car                    Node                    TrafficSignal
 │                       │                          │
 │ RequestSignalState    │                          │
 │ {targetLinkId}        │                          │
 │──────────────────────→│                          │
 │                       │ [lookup connections →    │
 │                       │  find signal → lookup    │
 │                       │  signalStates by phaseId]│
 │ SignalStateData       │                          │
 │ {phase, nextTick}     │                          │
 │←──────────────────────│                          │
 │                       │                          │
 │ Se phase == Red:      │                          │
 │   WaitingSignal       │   TrafficSignalChange    │
 │   até nextTick        │   StatusData             │
 │                       │←─────────────────────────│
 │                       │  [Atualiza signalStates] │
```

**Fallback:** Se o nó não possui semáforo registrado para o link consultado, retorna `Green` por default — permitindo que veículos não fiquem bloqueados em interseções não semaforizadas.

---

## 11. Transporte Público: Ônibus e Metrô

### 11.1. Ônibus (Bus)

O ator `Bus` estende `Movable[BusState]` sem o trait `PrivateVehicle` (é público). Possui características específicas:

| Parâmetro | Carro | Ônibus |
|-----------|-------|--------|
| Comprimento | 4.5m | 12.0m |
| Aceleração máx. | 2.6 m/s² | 1.2 m/s² |
| Desaceleração máx. | 4.5 m/s² | 3.5 m/s² |
| Gap mínimo | 2.0m | 3.0m |
| Velocidade desejada | 50 km/h | 40 km/h |
| Tempo de reação | 1.0s | 1.5s |

**Fluxo de operação:**
1. `BusStation` calcula rotas (ida e volta) usando o mapa
2. `BusStation` cria atores `Bus` com rotas pré-computadas
3. `Bus` percorre a rota, parando em `BusStop`s para carga/descarga
4. `BusStop` gerencia filas de passageiros por linha
5. Protocolo de carga: `BusRequestPassengerData` → `BusLoadPassengerData`

### 11.2. Metrô (Subway)

O ator `Subway` utiliza uma **rede ferroviária dedicada** (`RailLink`):

- Rotas **pré-definidas** pela `SubwayStation` (não usa roteamento dinâmico)
- `RailLink` **valida tipo de veículo** — rejeita não-metrôs
- Sem congestionamento (trilhos dedicados)
- Velocidade efetiva considera gradiente e curvatura:

$$
v_{\text{eff}} = v_{\text{limit}} \cdot f_{\text{gradient}} \cdot f_{\text{curvature}}
$$

---

## 12. Roteamento Dinâmico com Pesos em Tempo Real

### 12.1. Algoritmo de Roteamento

O sistema utiliza **Dijkstra com pesos dinâmicos** via `GPSUtil`:

```
Algorithm: calcRoute(origin, destination)

1. Se origin == destination → retorna rota vazia, custo 0
2. Obter NodeGraph para origin e destination
3. Executar dijkstraEdgeTargetsOptimized(origin, destination)
   - Pesos: DynamicWeightCache.getWeight(edgeId, staticWeight)
   - Fallback: peso estático do grafo
4. Recalcular custo total com pesos dinâmicos:
   dynamicCost = Σ DynamicWeightCache.getWeight(e_i.id, e_i.staticWeight)
5. Construir fila de rota: Queue[(edgeId, targetNodeId)]
```

### 12.2. Cache de Pesos Dinâmicos

```
Link Actor ────publishDynamicCost()────→ Kafka ────→ DynamicWeightCache (Local Memory)
                                                            │
Vehicle Actor ←──── GPSUtil.calcRoute() ←── getWeight() ───┘
```

Os links publicam custos dinâmicos periodicamente (default: a cada 10 ticks):

$$
\text{cost}_{\text{dynamic}} = \text{DynamicLinkCost.fromLinkState}(L, v_{\text{current}}, v_f, n, C, f_c, t)
$$

O cache tem TTL configurável (default: 60 ticks). Após expiração, fallback para peso estático.

---

## 13. Modelagem de Diferentes Tipos de Veículos

### 13.1. Parâmetros por Tipo de Veículo

| Parâmetro | Carro | Ônibus | Bicicleta | Motocicleta |
|-----------|-------|--------|-----------|-------------|
| $a_{\text{max}}$ (m/s²) | 2.6 | 1.2 | 1.0 | 3.5 |
| $b_{\text{max}}$ (m/s²) | 4.5 | 3.5 | 3.0 | 5.0 |
| $g_{\text{min}}$ (m) | 2.0 | 3.0 | 1.5 | 1.5 |
| $v_{\text{desired}}$ (km/h) | 50 | 40 | 20 | 60 |
| $\tau$ (s) | 1.0 | 1.5 | 1.2 | 0.9 |
| Comprimento (m) | 4.5 | 12.0 | 2.0 | 2.5 |
| $\epsilon$ (aleatoriedade) | 0.2 | 0.2 | 0.1 | 0.1 |

### 13.2. Características Comportamentais Específicas

- **Ônibus:** Restrição a faixa exclusiva (`busLaneRestricted`), interações em paradas, gerenciamento de passageiros
- **Bicicleta:** Preferência por ciclovias (`prefersBikeLane`), velocidade reduzida, tamanho menor
- **Motocicleta:** Filtragem entre faixas (`canFilterLanes`), aceitação de gaps menores (`aggressiveness`), aceleração superior

### 13.3. Estrutura de Estado Micro por Veículo

Todos os estados micro herdam da trait `MicroMovableState`:

```scala
trait MicroMovableState {
  def positionInLink: Double      // metros desde início do link
  def velocity: Double            // m/s
  def acceleration: Double        // m/s²
  def currentLane: Int            // faixa (0-indexed)
  def leaderVehicle: Option[String]
  def gapToLeader: Double         // metros
  def leaderVelocity: Double      // m/s
  def maxAcceleration: Double
  def maxDeceleration: Double
  def minGap: Double
  def desiredVelocity: Double
  def reactionTime: Double
  def vehicleLength: Double
  def desiredLane: Option[Int]
  def laneChangeProgress: Double
}
```

Métodos derivados:
- `rearPosition: Double = positionInLink - vehicleLength` — posição traseira
- `hasLeader: Boolean = leaderVehicle.isDefined`
- `isSafeGap: Boolean = gapToLeader >= minGap`
- `isSafeVelocity(safeV: Double): Boolean = velocity <= safeV + 0.01`

---

## 14. Sistema de Métricas e Relatórios (SUMO-Compatible)

### 14.1. Compatibilidade com SUMO

O sistema gera relatórios **compatíveis com o formato do SUMO** (Simulation of Urban Mobility), permitindo validação cruzada e reuso de ferramentas de análise:

#### TripInfo (por veículo)

```scala
report("sumo_tripinfo", Map(
  "vehicle_id" → id,
  "depart" → departTick,
  "arrival" → arrivalTick,
  "duration" → durationSeconds,
  "routeLength" → totalDistance,
  "waitingTime" → waitingTimeSeconds,
  "waitingCount" → waitingCount,
  "timeLoss" → (duration - idealTravelTime),
  "departSpeed" → departSpeed,
  "arrivalSpeed" → arrivalSpeed,
  "speedFactor" → driverAttributes.maxSpeedFactor,
  "rerouteNo" → rerouteCount,
  "vaporized" → wasDestructedEarly
))
```

#### Summary Step (por link, por tick)

```scala
report("sumo_summary_step", Map(
  "link_id" → id,
  "loaded" → cumulativeLoaded,
  "running" → vehicleCount,
  "halting" → haltingCount,    // velocidade < 0.1 m/s
  "meanSpeed" → meanSpeed,
  "meanSpeedRelative" → meanSpeed / freeSpeed,
  "meanWaitingTime" → avgWaiting,
  "meanTravelTime" → avgTravel
))
```

### 14.2. Eventos Reportados ao Longo da Simulação

| Evento | Ator Emissor | Dados Chave |
|--------|-------------|-------------|
| `journey_started` | Car | origin, destination, route_cost |
| `route_planned` | Car | route_links, route_nodes |
| `enter_link` / `enter_micro_link` | Car | link_id, mode, speed, travel_time |
| `leave_link` / `leave_micro_link` | Car | distance, travel_time, waiting_time |
| `signal_wait` | Car | phase, wait_until_tick |
| `journey_completed` | Car | total_distance, reached_destination |
| `sumo_tripinfo` | Car | SUMO-compatible trip summary |
| `link_vehicle_entered` / `left` | Link | vehicle_count, congestion |
| `sumo_summary_step` | Link | aggregated link metrics |
| `signal_phase_change` | TrafficSignal | new_state, affected_nodes |
| `person_trip_completed` | Person | travel_time, distance |
| `person_walking_start` / `completed` | Person | walking_time, distance |
| `activity_start` | Person | activity_type, node_id |
| `bus_stop_passengers_loaded` | BusStop | count, route_label |

---

## 15. Padrões de Projeto e Decisões de Engenharia

### 15.1. Padrões de Projeto Aplicados

| Padrão | Onde | Justificativa |
|--------|------|---------------|
| **Actor Model** | Todo o sistema | Encapsulamento de estado, concorrência sem locks |
| **Template Method** | `Movable[T]` | Define esqueleto de routing; subclasses implementam detalhes |
| **Strategy** | `MicroSimulationStrategy`, `LaneChangeStrategy` | Plugabilidade de modelos de car-following |
| **State Machine** | `MovableStatusEnum` | Transições de estado bem definidas e guards |
| **Mixin/Trait Composition** | `PrivateVehicle[T]` | Composição cross-cutting (pessoa-veículo) sem herança múltipla |
| **Publisher-Subscriber** | `DynamicWeightCache` via Kafka | Disseminação de custos dinâmicos |
| **Singleton** | `CityMapUtil`, `GPSUtil` | Dados estáticos do mapa compartilhados (read-only) |
| **Façade** | `GPSUtil.calcRoute()` | Abstrai grafo, cache dinâmico e A*/Dijkstra |
| **Observer** | `report()` em todo ator | Métricas e eventos sem acoplamento |

### 15.2. Decisões Arquiteturais Críticas

#### D1: Link como Simulador Micro (não veículo)
**Decisão:** Em modo MICRO, o Link executa car-following para todos os veículos internos.
**Alternativa:** Cada veículo executar seu próprio car-following.
**Justificativa:** Evita troca excessiva de mensagens ($O(n^2)$ entre veículos para obter gap). O link tem visibilidade total da faixa, reduzindo para $O(n)$.

#### D2: Estado Micro como Optional
**Decisão:** `microState: Option[MicroCarState]` no `CarState`.
**Alternativa:** Herança (MicroCar extends Car).
**Justificativa:** O mesmo ator transita entre modos. Option evita duplicação de atores e permite ativação/desativação in-place.

#### D3: Modo Definido por Link (não por veículo)
**Decisão:** O link decide o modo; todos os veículos se adaptam.
**Alternativa:** Veículos escolherem seu modo.
**Justificativa:** Garante consistência — todos os veículos no mesmo link operam com a mesma granularidade, evitando interações cross-mode dentro de uma faixa.

#### D4: Veículo Passivo em MICRO
**Decisão:** Veículo desliga timer espontâneo em modo MICRO.
**Alternativa:** Veículo e link ambos com timers.
**Justificativa:** Evita conflitos de timing — o link orquestra sub-ticks precisos sem competição com timers dos veículos.

#### D5: Person como Agente Persistente
**Decisão:** Person persiste toda a simulação, ativando/desativando veículos.
**Alternativa:** Criar veículos sob demanda (trip-based).
**Justificativa:** Habilita modelo activity-based, encadeamento de viagens, e reutilização do mesmo veículo para múltiplas viagens.

### 15.3. Mecanismos de Proteção contra Race Conditions

Em ambiente distribuído com passagem de mensagens assíncrona, o sistema implementa:

1. **Idempotency guards:** Verificação de `journeyFinishedReported` para evitar relatórios duplicados de fim de viagem
2. **Stale message guards:** `currentLinkId.contains(data.linkId)` para descartar mensagens de links antigos
3. **Status guards:** Verificação de `state.movableStatus != WaitingSignalState` antes de processar SignalState
4. **Timeout recovery:** 100 ticks máximo em Waiting, recuperação automática
5. **Force-finish on simulation end:** Guard em `actSpontaneous` e `handleMicroUpdate` para terminar viagens ao fim da simulação
6. **Propagation on destruct:** Link propaga `DestructEvent` para veículos registrados, evitando veículos órfãos

---

## 16. Limitações e Trabalhos Futuros

### 16.1. Limitações Atuais

| Limitação | Impacto | Mitigação |
|-----------|---------|-----------|
| **Sem troca de faixa ativa** | Veículos não ultrapassam; fila única por faixa | `NoLaneChangeStrategy` como placeholder; interface MOBIL definida |
| **Sem interseção microscópica** | Conflitos no nó não modelados em MICRO | Tratado via semáforos mesoscópicos |
| **Modo definido estaticamente** | Links não alternam modo durante simulação | Configuração por cenário suficiente para casos de uso atuais |
| **BPR simplificada** | α=1, β=1 — calibração limitada | Parametrização futura com dados reais |
| **Krauss simplificado** | Implementação no strategy usa fórmula simplificada | `KraussModel` formal implementado, integração pendente |
| **Transit instantâneo** | Viagens de PT não usam rede de transporte | Implementação parcial com metrô; ônibus funcional |

### 16.2. Trabalhos Futuros

1. **Modelo MOBIL de troca de faixa** (Kesting, 2007) — interface `LaneChangeStrategy` já definida
2. **IDM (Intelligent Driver Model)** — extensão via `CarFollowingModel`
3. **Interseção microscópica** — `MicroIntersectionController`, zonas de conflito, prioridades
4. **Modo dinâmico por link** — transição MESO↔MICRO baseada em densidade/demanda
5. **Calibração com dados reais** — ajuste de parâmetros BPR com contagens de tráfego
6. **Validação contra SUMO** — pipeline SUMO-HTC usando formato compatível de relatórios

---

## Referências

1. **Krauss, S.** (1998). *Microscopic Modeling of Traffic Flow: Investigation of Collision Free Vehicle Dynamics.* DLR-Forschungsbericht.
2. **Treiber, M., Hennecke, A., & Helbing, D.** (2000). *Congested Traffic States in Empirical Observations and Microscopic Simulations.* Physical Review E, 62(2), 1805–1824.
3. **Kesting, A., Treiber, M., & Helbing, D.** (2007). *General Lane-Changing Model MOBIL for Car-Following Models.* Transportation Research Record, 1999(1), 86–94.
4. **Bourrel, E., & Lesort, J.-B.** (2003). *Mixing Micro and Macro Representations of Traffic Flow: a Hybrid Model based on the LWR Theory.* Transportation Research Record, 1852(1), 73–79.
5. **Burghout, W.** (2004). *Hybrid Microscopic-Mesoscopic Traffic Simulation.* KTH Royal Institute of Technology.
6. **Bureau of Public Roads.** (1964). *Traffic Assignment Manual for Application with a Large, High Speed Computer.* US Department of Commerce.
7. **Hewitt, C.** (1973). *A Universal Modular ACTOR Formalism for Artificial Intelligence.* IJCAI.
8. **Balmer, M., Rieser, M., Meister, K., Charypar, D., Lefebvre, N., Nagel, K., & Axhausen, K.** (2009). *MATSim-T: Architecture and Simulation Times.* Multi-Agent Systems for Traffic and Transportation Engineering, 57–78.

---

> **Nota:** Este documento foi gerado a partir da análise completa do código-fonte do Hyperbolic Time Chamber v2.0.0.
> Todos os algoritmos, estruturas de dados e protocolos de comunicação documentados aqui refletem a implementação real do sistema.
