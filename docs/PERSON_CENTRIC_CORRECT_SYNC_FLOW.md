# Person-Centric Model: CORRECT Synchronization Flow

## Compreensão Correta da Arquitetura

A arquitetura do Hyperbolic Time Chamber possui:

1. **CreatorLoadData** - Orquestra criação de atores em lotes
2. **SimulationBaseActor** - Base com `onInitialize()` e `preStart()`
3. **TimeManager** - Gerencia ticks e scheduling de atores

### Fluxo de Inicialização de Ator

```
1. CreatorLoadData envia InitializeEvent para novo ator
   ↓
2. SimulationBaseActor.onInitialize() é chamado
   ├─ Carrega state do JSON
   ├─ Se state.scheduleOnTimeManager == true:
   │  └─ Registra NO TIMEMANAGER IMEDIATAMENTE (linha 177)
   └─ Envia InitializeEntityAckEvent de volta
   ↓
3. CreatorLoadData recebe InitializeEntityAckEvent
   └─ Continua orquestração...
```

### O Ponto-Chave

**Em `SimulationBaseActor.onInitialize()` (linhas 162-186):**

```scala
override protected def onInitialize(event: InitializeEvent): Unit = {
  // ...
  state = JsonUtil.convertValue[T](event.data.data)
  // ...
  if (state != null) {
    startTick = state.getStartTick  // ← Salva startTick do JSON
    if (state.isSetScheduleOnTimeManager) {
      registerOnTimeManager()  // ← Se true, registra AGORA com startTick
    }
  }
  // Send ack
  event.actorRef ! InitializeEntityAckEvent(entityId = entityId)
}
```

**Então quando o TimeManager recebe o registro, usa `state.getStartTick` como primeiro tick!**

## Solução Correta

### JSON Configuration

**Person (deve ter `scheduleOnTimeManager: false`)**:
```json
{
  "id": "htcaid:person;person_0",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [...],
      "currentActivityIndex": 0,
      "ownedVehicles": {"car": {...}},
      "scheduleOnTimeManager": false,  // ← NOT scheduled initially
      "startTick": null  // ← Or omitted - not used because scheduleOnTimeManager=false
      // Note: firstActivityStartTick is metadata for display, not used by framework
    }
  }
}
```

**Autonomous Car (deve ter `scheduleOnTimeManager: true`)**:
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "status": "Start",
      "scheduleOnTimeManager": true,  // ← Scheduled
      "startTick": 154  // ← Used as first tick when registering with TimeManager
      "ownedBy": null
    }
  }
}
```

**Owned Car (deve ter `scheduleOnTimeManager: false`)**:
```json
{
  "id": "htcaid:car;trip_189",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "status": "Parked",
      "scheduleOnTimeManager": false,  // ← NOT scheduled
      "startTick": null  // ← Or omitted - not used
      "ownedBy": "htcaid:person;person_0"
    }
  }
}
```

### Expected Runtime Behavior

**Timeline:**

```
Tick 0: Initialization
├─ CreatorLoadData sends InitializeEvent for all actors
├─ Persons: onInitialize() 
│  ├─ state.scheduleOnTimeManager = false
│  ├─ registerOnTimeManager() NOT called ✓
│  └─ Send InitializeEntityAckEvent
├─ Autonomous Cars: onInitialize()
│  ├─ state.scheduleOnTimeManager = true
│  ├─ registerOnTimeManager() called with startTick=154 ✓
│  └─ Send InitializeEntityAckEvent
├─ Owned Cars: onInitialize()
│  ├─ state.scheduleOnTimeManager = false
│  ├─ registerOnTimeManager() NOT called ✓
│  └─ Send InitializeEntityAckEvent
└─ CreatorLoadData collects all ACKs, finishes batch

Tick 1-154: Autonomous Operation
├─ TimeManager ticks only autonomous cars (400)
├─ Persons receive NO ticks
└─ Owned cars remain Parked (no ticks)

Tick 154+: ??? 
└─ How do Persons get registered with TimeManager?
   → This is the MISSING PIECE!
```

## The MISSING Piece: How Persons Get Scheduled

This is where the **Creator/LoadManager orchestration** comes in.

Currently there are two options:

### Option A: LoadManager registers Persons AFTER all creation ACKs

After CreatorLoadData receives all InitializeEntityAckEvent ACKs, it could:

1. Process regular actors as normal
2. **For Persons (special handling)**: Send a `RegisterPersonEvent` or similar
3. Person receives event and calls `registerOnTimeManager()` with `firstActivityStartTick`

**Requires:**
- New event type: `RegisterPersonEvent(personId, firstActivityStartTick)`
- Person has method to handle it:
  ```scala
  def handleRegisterOnTimeManager(tick: Tick): Unit = {
    startTick = tick
    registerOnTimeManager()
  }
  ```
- CreatorLoadData logic to detect Persons and send register events

### Option B: LoadManager uses `ScheduleEvent` to wake Persons

Simpler approach:

1. After CreatorLoadData finishes batch
2. For each Person, send `ScheduleEvent` to TimeManager
3. TimeManager schedules first tick for Person at `firstActivityStartTick`
4. When tick arrives, Person receives `SpontaneousEvent`
5. Person processes activities normally

**Requires:**
- CreatorLoadData reads `firstActivityStartTick` from Person state
- Sends `ScheduleEvent(tick=firstActivityStartTick, personId)` to TimeManager
- TimeManager adds to its schedule

### Option C: Person is "always scheduled" with dynamic checks

Persons have `scheduleOnTimeManager: true` but `startTick` is very large (max_int):

1. Registered with TimeManager at initialization
2. But first tick is unreachable
3. Person's `actSpontaneous()` checks if activity time reached
4. If not, reschedules for later

**Disadvantage:** Wastes TimeManager resources checking Persons that aren't ready

## Recommendation

**Option B is cleanest:**

1. Persons have `scheduleOnTimeManager: false` + `startTick: null` (not scheduled)
2. LoadManager detects Persons during creation phase
3. After initial actor batch is ready, LoadManager sends `ScheduleEvent` to TimeManager
4. TimeManager adds Person to schedule at `firstActivityStartTick`
5. When tick arrives, Person processes normally

### Code Changes Needed

**In `CreatorLoadData.scala`:**

```scala
private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
  // ... existing code ...
  
  // Check if this is a Person that needs deferred scheduling
  if (isPersonActor(event.entityId)) {
    val actorState = getPersonState(event.entityId)  // Load state
    val firstActivityStartTick = actorState.getFirstActivityStartTick()
    
    // Send ScheduleEvent to TimeManager
    timeManager ! ScheduleEvent(
      tick = firstActivityStartTick,
      actorRef = event.entityId,
      // ... other fields ...
    )
    logInfo(s"Person ${event.entityId} scheduled for first activity at tick $firstActivityStartTick")
  }
}
```

**In `PersonState.scala`:**

Already has `firstActivityStartTick` field from Python generation ✓

### Python Generation (ALREADY DONE)

Current script already generates:
- Persons: `scheduleOnTimeManager: false`, `firstActivityStartTick: X`
- Owned Cars: `scheduleOnTimeManager: false`
- Autonomous Cars: `scheduleOnTimeManager: true`, `startTick: X`

✓ This is correct for the architecture!

## Code Revert Required

**Person.scala** should be SIMPLE:
- No `isRegisteredWithTimeManager` flag
- No special auto-registration logic
- Just normal `actSpontaneous()` handling activities
- Calls `onFinishSpontaneous(Some(nextTick))` when needs rescheduling

Current Person.scala is fine as-is (already reverted).

## Summary

The solution was **ALMOST correct** in Python generation, but the **missing piece is in CreatorLoadData**:

1. ✅ Python: Generates Persons with `scheduleOnTimeManager: false` + `firstActivityStartTick`
2. ✅ Python: Generates Owned Cars with `scheduleOnTimeManager: false`
3. ✅ Python: Generates Autonomous Cars with `scheduleOnTimeManager: true`
4. ❌ **MISSING**: CreatorLoadData needs to send `ScheduleEvent` for Persons after ACKs received
5. ✅ Person.scala: Simple, no special logic needed

The architecture already handles all the pieces—we just need to wire them together in CreatorLoadData!

---

**Next Step**: Modify `CreatorLoadData.scala` to detect Persons and send `ScheduleEvent` with `firstActivityStartTick`

