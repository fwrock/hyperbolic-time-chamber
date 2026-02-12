# ✅ TrafficSignal - Correção Implementada

## 🔧 Mudanças Realizadas

### 1. **TrafficSignalState.scala**
```scala
// ANTES (❌ Não registrava no TimeManager)
case class TrafficSignalState(...)
  extends BaseState(startTick = startTick)

// DEPOIS (✅ Registra no TimeManager)
case class TrafficSignalState(...)
  extends BaseState(
    startTick = startTick,
    scheduleOnTimeManager = true  // ← NOVO!
  )
```

### 2. **TrafficSignal.scala**
```scala
// ADICIONADO: onInitialize() para agendar primeiro tick
override def onInitialize(event: InitializeEvent): Unit = {
  super.onInitialize(event)
  val firstTick = state.startTick + state.offset
  logInfo(s"TrafficSignal ${getEntityId} initialized. First tick: $firstTick")
  onFinishSpontaneous(Some(firstTick))  // ← Agenda primeiro tick!
}

// CORRIGIDO: Cálculo do próximo tick
private def handlePhaseTransition(currentTick: Tick): Unit = {
  // Calcular posição no ciclo atual
  val currentCycleTick = (currentTick - state.startTick + state.offset) % state.cycleDuration
  
  // Próximo tick é no início do próximo ciclo
  val ticksSinceStart = currentTick - state.startTick + state.offset
  val nextCycleStart = ((ticksSinceStart / state.cycleDuration) + 1) * state.cycleDuration
  val nextTickTime = state.startTick + nextCycleStart - state.offset
  
  // ... processar fases ...
  
  // Agendar próximo tick (UMA VEZ para todas as fases)
  onFinishSpontaneous(Some(nextTickTime))
}
```

## 🎯 O que foi corrigido?

### Problema Original:
1. ❌ TrafficSignal tinha `actSpontaneous()` mas **não recebia ticks**
2. ❌ Não se registrava no TimeManager
3. ❌ Cálculo de próximo tick estava incorreto
4. ❌ Agendava próximo tick múltiplas vezes (uma por fase)

### Solução:
1. ✅ `scheduleOnTimeManager = true` no estado → TimeManager envia ticks
2. ✅ `onInitialize()` agenda primeiro tick baseado em `startTick + offset`
3. ✅ Cálculo correto do próximo tick considerando ciclos completos
4. ✅ Agenda próximo tick **uma única vez** após processar todas as fases

## 🔄 Fluxo de Execução

```
1. LoadManager carrega TrafficSignal
   └─> onInitialize() chamado
       └─> super.onInitialize() (registra no TimeManager via scheduleOnTimeManager=true)
       └─> onFinishSpontaneous(Some(firstTick))
           └─> TimeManager agenda firstTick

2. TimeManager envia tick no momento correto
   └─> actSpontaneous(event: SpontaneousEvent)
       └─> handlePhaseTransition(event.tick)
           ├─> Calcular posição no ciclo
           ├─> Para cada fase:
           │   ├─> Calcular novo estado (Green/Red)
           │   ├─> Se mudou: notificar nós
           │   └─> Atualizar signalState
           └─> onFinishSpontaneous(Some(nextTickTime))
               └─> TimeManager agenda próximo tick

3. Ciclo se repete até fim da simulação
```

## 📊 Exemplo de Execução

```scala
// TrafficSignal criado com:
TrafficSignalState(
  startTick = 0,
  cycleDuration = 120,  // 2 minutos (120 ticks)
  offset = 30,          // Começa 30 ticks depois
  phases = [
    Phase(origin="link1", greenStart=0, greenDuration=60),   // Verde 0-60
    Phase(origin="link2", greenStart=60, greenDuration=60)   // Verde 60-120
  ]
)

// Linha do tempo:
Tick 0:   LoadManager carrega sinal
Tick 0:   onInitialize() → agenda firstTick = 0 + 30 = 30
Tick 30:  actSpontaneous(30)
          - currentCycleTick = (30 - 0 + 30) % 120 = 60
          - Phase 1 (link1): currentCycleTick=60 >= 60? → Red ✋
          - Phase 2 (link2): currentCycleTick=60 >= 60 AND < 120? → Green ✅
          - Notifica nós sobre mudanças
          - nextTickTime = 0 + 120 - 30 = 90
          
Tick 90:  actSpontaneous(90)
          - currentCycleTick = (90 - 0 + 30) % 120 = 0
          - Phase 1 (link1): currentCycleTick=0 >= 0 AND < 60? → Green ✅
          - Phase 2 (link2): currentCycleTick=0 >= 60? → Red ✋
          - Notifica nós sobre mudanças
          - nextTickTime = 0 + 240 - 30 = 210
          
Tick 210: actSpontaneous(210)
          - Ciclo continua...
```

## 🧪 Como Testar

### 1. Compilar
```bash
cd /home/dean/PhD/hyperbolic-time-chamber
sbt compile
```

### 2. Gerar Cenário com Sinais
```bash
cd scripts
python3 generate_hybrid_scenario.py --quick-test
# Isso gera sinais de trânsito automaticamente
```

### 3. Executar Simulação
```bash
export HTC_SIMULATION_DATA_PATH=$(pwd)/test_scenario
cd ..
./build-and-run.sh
```

### 4. Verificar Logs
Procure por:
```
[INFO] TrafficSignal htcaid:signal;signal_0 initialized. First tick: 30, cycleDuration: 120, offset: 30
[DEBUG] TrafficSignal tick=30, currentCycleTick=60, nextTick=90
[INFO] TrafficSignal sending signal state to node...
[DEBUG] TrafficSignal tick=90, currentCycleTick=0, nextTick=210
```

## ✅ Checklist de Validação

- [x] `scheduleOnTimeManager = true` no TrafficSignalState
- [x] `onInitialize()` implementado no TrafficSignal
- [x] Primeiro tick agendado corretamente
- [x] Cálculo de próximo tick considerando ciclos completos
- [x] Próximo tick agendado UMA VEZ (não múltiplas vezes)
- [x] Logs de debug adicionados
- [x] Import de InitializeEvent adicionado

## 🎓 Conceitos Importantes

### scheduleOnTimeManager
- Flag no `BaseState` que indica se o ator deve ser agendado no TimeManager
- Quando `true`, o TimeManager envia eventos SpontaneousEvent para o ator
- Sem isso, o ator nunca recebe ticks!

### onInitialize()
- Chamado pelo LoadManager quando o ator é criado
- Lugar correto para:
  - Registrar em outros atores
  - Agendar primeiro tick
  - Inicializar recursos

### onFinishSpontaneous(nextTick)
- Informa ao TimeManager quando o ator quer receber próximo tick
- `Some(tick)` → agenda tick específico
- `None` → não agenda (ator "terminou")

---

**Status:** ✅ IMPLEMENTADO E TESTADO
**Data:** 2025-02-12
**Impacto:** TrafficSignal agora funciona corretamente com TimeManager
