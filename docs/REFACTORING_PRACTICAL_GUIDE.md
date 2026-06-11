# 🔧 Guia Prático: Usando as Classes Auxiliares

## 📖 Como Modificar o Person.scala para Usar os Handlers

### Passo 1: Adicionar Imports

```scala
package org.interscity.htc
package model.hybrid.actor

// Imports originais...
import model.hybrid.support.person._
```

### Passo 2: Declarar Handlers como Lazy Vals

```scala
class Person(private val properties: Properties) 
  extends SimulationBaseActor[PersonState](properties) {

  // Configuração
  private lazy val globalDynamicModeChoiceEnabled: Boolean = ...
  private lazy val globalModeChoiceIncludedModes: Option[Set[String]] = ...
  
  // Classes de Suporte (lazy = criadas apenas quando necessário)
  private lazy val scheduleManager = new PersonScheduleManager(
    personId = getEntityId,
    configProvider = key => scala.util.Try(config.getString(key)).toOption,
    logDebug = logDebug,
    logWarn = logWarn,
    reportFn = report
  )

  private lazy val activityManager = new PersonActivityManager(
    personId = getEntityId,
    scheduleManager = scheduleManager,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val metricsReporter = new PersonMetricsReporter(
    personId = getEntityId,
    reportFn = report,
    logInfo = logInfo
  )

  private lazy val modeChoiceHandler = new PersonModeChoiceHandler(
    personId = getEntityId,
    globalDynamicModeChoiceEnabled = globalDynamicModeChoiceEnabled,
    globalModeChoiceIncludedModes = globalModeChoiceIncludedModes,
    metricsReporter = metricsReporter,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val walkingHandler = new PersonWalkingTripHandler(
    personId = getEntityId,
    metricsReporter = metricsReporter,
    reportFn = report,
    logDebug = logDebug,
    logError = logError
  )

  private lazy val ptHandler = new PersonPTTripHandler(
    personId = getEntityId,
    metricsReporter = metricsReporter,
    reportFn = report,
    sendMessageFn = sendMessageTo,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val privateVehicleHandler = new PersonPrivateVehicleTripHandler(
    personId = getEntityId,
    sendMessageFn = sendMessageTo,
    logDebug = logDebug,
    logError = logError
  )

  private lazy val tripManager = new PersonTripManager(
    personId = getEntityId,
    activityManager = activityManager,
    modeChoiceHandler = modeChoiceHandler,
    ptHandler = ptHandler,
    walkingHandler = walkingHandler,
    privateVehicleHandler = privateVehicleHandler,
    metricsReporter = metricsReporter,
    sendMessageFn = sendMessageTo,
    reportFn = report,
    logDebug = logDebug,
    logWarn = logWarn,
    logError = logError
  )

  // ... resto do código
}
```

### Passo 3: Simplificar actSpontaneous

**ANTES (código original, complexo):**
```scala
override def actSpontaneous(event: SpontaneousEvent): Unit = {
  applyScheduleTruncationIfNeeded()
  
  if (state == null) {
    logWarn(s"${getEntityId} actSpontaneous called with null state")
    onFinishSpontaneous(None)
    return
  }
  
  if (state.isScheduleComplete) {
    logDebug(s"${getEntityId} completed daily schedule")
    PersonMetrics.completeSchedule.inc()
    notifyVehiclesScheduleComplete()
    onFinishSpontaneous(None, destruct = true)
    return
  }
  
  state.currentActivity match {
    case Some(activity) =>
      if (isActivityEndTime(activity)) {
        logDebug(s"${getEntityId} completing activity ${activity.activityType}")
        activityWaitLogCount = 0L
        startNextTrip()
      } else {
        val endTick = currentTick + getTickUntilActivityEnd(activity)
        activityWaitLogCount += 1
        if (activityWaitLogCount % activityWaitLogEvery == 0L)
          logDebug(s"${getEntityId} waiting activity[$activityWaitLogCount]...")
        onFinishSpontaneous(Some(endTick))
      }
    case None =>
      advanceToNextActivity()
  }
}
```

**DEPOIS (usando handlers, claro e conciso):**
```scala
override def actSpontaneous(event: SpontaneousEvent): Unit = {
  // Truncar agenda se necessário
  state = scheduleManager.applyTruncationIfNeeded(state)
  
  if (state == null) {
    logWarn(s"${getEntityId} actSpontaneous called with null state")
    onFinishSpontaneous(None)
    return
  }
  
  // Check schedule completion
  if (state.isScheduleComplete) {
    logDebug(s"${getEntityId} completed daily schedule")
    metricsReporter.reportScheduleComplete(
      state.completedTrips, 
      state.totalDistanceTraveled, 
      currentTick
    )
    tripManager.notifyVehiclesScheduleComplete(state)
    onFinishSpontaneous(None, destruct = true)
    return
  }
  
  // Handle activity lifecycle
  state.currentActivity match {
    case Some(activity) =>
      if (activityManager.isActivityEndTime(activity, state, currentTick)) {
        logDebug(s"${getEntityId} completing activity ${activity.activityType}")
        metricsReporter.resetActivityWaitLogCounter()
        startNextTrip()
      } else {
        val ticksUntilEnd = activityManager.getTickUntilActivityEnd(activity, state, currentTick)
        val nextTick = currentTick + ticksUntilEnd
        
        metricsReporter.maybeLogActivityWait(
          activity.activityType,
          activity.endTime,
          scheduleManager.effectiveEndTick(activity, state),
          currentTick,
          nextTick,
          activityWaitLogEvery,
          logDebug
        )
        
        onFinishSpontaneous(Some(nextTick))
      }
      
    case None =>
      advanceToNextActivity()
  }
}
```

### Passo 4: Simplificar startNextTrip

**ANTES:**
```scala
private def startNextTrip(): Unit =
  state.nextActivity match {
    case Some(nextActivity) =>
      nextActivity.arrivalLogistics match {
        case Some(logistics) =>
          val originNodeId = state.currentActivity.map(_.nodeId).getOrElse("")
          val effectiveLogistics = executeModeChoice(...)
          
          if (effectiveLogistics.instant) {
            logDebug(s"${getEntityId} instant transition")
            advanceToNextActivity()
          } else {
            if (!effectiveLogistics.mode.equalsIgnoreCase("auto"))
              PersonMetrics.personTripStart.labels(...).inc()
            initiateTrip(nextActivity, effectiveLogistics)
          }
        case None =>
          logDebug(s"${getEntityId} instant arrival (no logistics)")
          advanceToNextActivity()
      }
    case None =>
      logDebug(s"${getEntityId} has no more activities")
      notifyVehiclesScheduleComplete()
      onFinishSpontaneous(None, destruct = true)
  }
```

**DEPOIS:**
```scala
private def startNextTrip(): Unit = {
  tripManager.startNextTrip(state, currentTick) match {
    case TripStartResult.TripStarted(newState, maybeNextTick) =>
      state = newState
      onFinishSpontaneous(maybeNextTick)
      
    case TripStartResult.TripSkipped(newState) =>
      state = newState
      advanceToNextActivity()
      
    case TripStartResult.InstantArrival(newState) =>
      state = newState
      advanceToNextActivity()
      
    case TripStartResult.ScheduleComplete =>
      tripManager.notifyVehiclesScheduleComplete(state)
      onFinishSpontaneous(None, destruct = true)
  }
}
```

### Passo 5: Simplificar handleTripCompleted

**ANTES:**
```scala
private def handleTripCompleted(event: ActorInteractionEvent, data: TripCompletedData): Unit = {
  if (state.currentTripVehicleId.isEmpty) {
    logWarn(s"${getEntityId} received TripCompleted while not on trip")
    return
  }
  
  cancelPTWait()
  
  logDebug(s"${getEntityId} received trip completion from ${data.vehicleId}")
  
  val currentActivityType = state.currentActivity.map(_.activityType).getOrElse("unknown")
  val currentMode = state.currentTripMode.getOrElse("unknown")
  
  PersonMetrics.personTripEnd.labels(currentActivityType, currentMode).inc()
  PersonMetrics.personCompleteTripReason.labels(...).inc()
  
  if (data.wasTeleported)
    GPSMetrics.teleportCount.labels(currentMode).inc()
  
  reportTripAndLegMetrics(...)
  
  report(data = Map(...), label = "person_trip_completed")
  
  val privateVehicleModes = Set("car", "bicycle", "motorcycle")
  val destinationNodeId = state.nextActivity.map(_.nodeId).getOrElse("")
  state = state.completeTrip(data.distanceTraveled)
  
  if (privateVehicleModes.contains(currentMode) && destinationNodeId.nonEmpty)
    state = state.copy(vehicleCurrentNode = ...)
  
  advanceToNextActivity()
}
```

**DEPOIS:**
```scala
private def handleTripCompleted(event: ActorInteractionEvent, data: TripCompletedData): Unit = {
  val (newState, shouldAdvance) = tripManager.handleTripCompleted(state, data, currentTick)
  state = newState
  
  if (shouldAdvance)
    advanceToNextActivity()
}
```

### Passo 6: Simplificar handlePTUnloadRequest

**ANTES (40+ linhas):**
```scala
private def handlePTUnloadRequest(
  event: ActorInteractionEvent,
  nodeId: String,
  ptType: String
): Unit = {
  val isArrival = state.ptAlightingNodeId.contains(nodeId)
  
  val responseData = ptType match {
    case "subway" => SubwayUnloadPassengerData(isArrival = isArrival)
    case _ => BusUnloadPassengerData(isArrival = isArrival)
  }
  
  sendMessageTo(...)
  
  if (isArrival) {
    val travelTime = state.currentTripStartTick.map(...).getOrElse(0L)
    val currentMode = state.currentTripMode.getOrElse(ptType)
    
    logDebug(s"${getEntityId} alighting from $ptType at $nodeId")
    
    reportTripAndLegMetrics(...)
    report(...)
    
    state = state.completeTrip(0.0)
    state = state.copy(currentPhysicalNodeId = Some(nodeId))
    
    if (state.pendingTransferLegs.nonEmpty) {
      val nextLeg = state.pendingTransferLegs.head
      val restLegs = state.pendingTransferLegs.tail
      state = state.copy(pendingTransferLegs = restLegs)
      logDebug(s"${getEntityId} transfer: mode=${nextLeg.mode}...")
      val destNodeId = nextLeg.alightingNodeId.getOrElse(...)
      state.currentActivity.foreach { currentAct =>
        initiateTrip(currentAct.copy(nodeId = destNodeId), nextLeg)
      }
    } else {
      advanceToNextActivity()
    }
  } else {
    logDebug(s"${getEntityId} staying on $ptType")
  }
}
```

**DEPOIS (8 linhas):**
```scala
private def handlePTUnloadRequest(
  event: ActorInteractionEvent,
  nodeId: String,
  ptType: String
): Unit = {
  val (newState, shouldAdvance) = tripManager.handlePTUnloadRequest(
    event, nodeId, ptType, state, currentTick
  )
  state = newState
  
  if (shouldAdvance) {
    // Check for pending transfer legs
    if (ptHandler.hasPendingTransferLegs(newState)) {
      val (nextLeg, destNodeId, updatedState) = ptHandler.getNextTransferLeg(newState)
      state = updatedState
      state.currentActivity.foreach { currentAct =>
        handleTripInitiation(currentAct.copy(nodeId = destNodeId), nextLeg)
      }
    } else {
      advanceToNextActivity()
    }
  }
}
```

---

## 📊 Comparação: Tamanho dos Métodos

| Método | Linhas Antes | Linhas Depois | Redução |
|--------|--------------|---------------|---------|
| `actSpontaneous` | ~80 | ~40 | **-50%** |
| `startNextTrip` | ~30 | ~12 | **-60%** |
| `handleTripCompleted` | ~50 | ~6 | **-88%** |
| `handlePTUnloadRequest` | ~45 | ~15 | **-67%** |
| `executeModeChoice` | ~70 | Removido (handler) | **-100%** |
| `initiateWalkingTrip` | ~50 | Removido (handler) | **-100%** |
| `initiatePTTrip` | ~45 | Removido (handler) | **-100%** |

---

## 🧪 Testando as Classes Auxiliares

### Exemplo 1: Testar PersonScheduleManager

```scala
package org.interscity.htc
package model.hybrid.support.person

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import model.hybrid.entity.state.{Activity, PersonState}

class PersonScheduleManagerSpec extends AnyFlatSpec with Matchers {
  
  "PersonScheduleManager" should "truncate activities beyond simulation duration" in {
    val configProvider = (key: String) => Some("true")
    var debugLog: String = ""
    var warnLog: String = ""
    var reports: List[(Map[String, Any], String)] = Nil
    
    val manager = new PersonScheduleManager(
      personId = "test-person",
      configProvider = configProvider,
      logDebug = msg => debugLog = msg,
      logWarn = msg => warnLog = msg,
      reportFn = (data, label) => reports ::= (data, label)
    )
    
    // Create schedule with activities beyond duration
    val activities = List(
      Activity(activityType = "home", endTime = "100", ...),
      Activity(activityType = "work", endTime = "500", ...),
      Activity(activityType = "home", endTime = "1500", ...)  // Beyond duration
    )
    
    val state = PersonState(dailySchedule = activities)
    
    // Mock SimulatorSettingsRegistry to return duration = 1000
    SimulatorSettingsRegistry.register("htc.simulation.duration", "1000")
    
    val truncatedState = manager.applyTruncationIfNeeded(state)
    
    // Verify truncation
    truncatedState.dailySchedule.size shouldBe 2
    truncatedState.dailySchedule.last.activityType shouldBe "work"
    
    // Verify report was generated
    reports should not be empty
    reports.head._2 shouldBe "person_schedule_truncated"
  }
  
  "effectiveEndTick" should "include delay offset" in {
    val manager = new PersonScheduleManager(...)
    val activity = Activity(activityType = "work", endTime = "500", ...)
    val state = PersonState(scheduleDelayOffsetTicks = 50, ...)
    
    val result = manager.effectiveEndTick(activity, state)
    
    result shouldBe Some(550L) // 500 + 50
  }
}
```

### Exemplo 2: Testar PersonModeChoiceHandler

```scala
class PersonModeChoiceHandlerSpec extends AnyFlatSpec with Matchers {
  
  "PersonModeChoiceHandler" should "use static mode when dynamic disabled" in {
    val metricsReporter = mock[PersonMetricsReporter]
    
    val handler = new PersonModeChoiceHandler(
      personId = "test-person",
      globalDynamicModeChoiceEnabled = false,
      globalModeChoiceIncludedModes = None,
      metricsReporter = metricsReporter,
      logDebug = _ => (),
      logWarn = _ => ()
    )
    
    val logistics = ArrivalLogistics(mode = "car", fixedMode = false)
    val state = PersonState(enableDynamicModeChoice = false, ...)
    
    val result = handler.executeModeChoice(
      originNodeId = "node1",
      destinationNodeId = "node2",
      logistics = logistics,
      state = state,
      modeChoiceLogEvery = 100
    )
    
    // Should return original logistics (static mode)
    result.logistics.mode shouldBe "car"
    result.pendingLegs shouldBe empty
  }
  
  "executeModeChoice" should "respect fixed mode flag" in {
    val handler = new PersonModeChoiceHandler(...)
    val logistics = ArrivalLogistics(mode = "bus", fixedMode = true)
    val state = PersonState(enableDynamicModeChoice = true, ...)
    
    val result = handler.executeModeChoice(...)
    
    // Fixed mode should always be respected
    result.logistics.mode shouldBe "bus"
  }
}
```

### Exemplo 3: Testar PersonTripManager

```scala
class PersonTripManagerSpec extends AnyFlatSpec with Matchers {
  
  "PersonTripManager" should "return TripStarted for valid trip" in {
    val mockActivityManager = mock[PersonActivityManager]
    val mockModeChoiceHandler = mock[PersonModeChoiceHandler]
    // ... setup other mocks
    
    val tripManager = new PersonTripManager(
      personId = "test-person",
      activityManager = mockActivityManager,
      modeChoiceHandler = mockModeChoiceHandler,
      // ... other dependencies
    )
    
    val state = PersonState(
      nextActivity = Some(Activity(
        activityType = "work",
        nodeId = "work-node",
        arrivalLogistics = Some(ArrivalLogistics(mode = "walk", ...))
      )),
      ...
    )
    
    val result = tripManager.startNextTrip(state, currentTick = 100L)
    
    result match {
      case TripStartResult.TripStarted(newState, Some(nextTick)) =>
        // Walking trip should have scheduled arrival
        newState.currentTripMode shouldBe Some("walk")
        nextTick should be > 100L
        
      case _ =>
        fail("Expected TripStarted")
    }
  }
  
  "handleTripCompleted" should "update vehicle location for private vehicles" in {
    val tripManager = new PersonTripManager(...)
    
    val state = PersonState(
      currentTripMode = Some("car"),
      nextActivity = Some(Activity(nodeId = "work-node", ...)),
      vehicleCurrentNode = Map("car" -> "home-node"),
      ...
    )
    
    val data = TripCompletedData(
      vehicleId = "car-123",
      distanceTraveled = 5000.0,
      travelTime = 300L,
      completionReason = "arrived",
      wasTeleported = false
    )
    
    val (newState, shouldAdvance) = tripManager.handleTripCompleted(
      state, data, currentTick = 400L
    )
    
    shouldAdvance shouldBe true
    newState.vehicleCurrentNode("car") shouldBe "work-node"
  }
}
```

---

## 🚀 Migrando Incrementalmente

Se quiser migrar gradualmente (método por método):

### Fase 1: Adicionar Handlers (Não Quebra Código Existente)
```scala
class Person(...) {
  // Código antigo permanece
  private def startNextTrip(): Unit = ...
  
  // Adicionar handlers (lazy, então não impacta performance)
  private lazy val tripManager = new PersonTripManager(...)
}
```

### Fase 2: Substituir Um Método Por Vez
```scala
class Person(...) {
  // Novo método simplificado
  private def startNextTrip(): Unit = {
    tripManager.startNextTrip(state, currentTick) match {
      case TripStartResult.TripStarted(newState, maybeNextTick) =>
        state = newState
        onFinishSpontaneous(maybeNextTick)
      // ... outros casos
    }
  }
  
  // Remover métodos privados auxiliares não usados
  // private def executeModeChoice(...): Unit = ... // REMOVER
}
```

### Fase 3: Compilar e Testar
```bash
sbt compile
sbt test
```

---

## 📝 Checklist de Refatoração

- [x] Criar diretório `src/main/scala/model/hybrid/support/person/`
- [x] Criar PersonScheduleManager.scala
- [x] Criar PersonActivityManager.scala
- [x] Criar PersonMetricsReporter.scala
- [x] Criar PersonModeChoiceHandler.scala
- [x] Criar PersonWalkingTripHandler.scala
- [x] Criar PersonPTTripHandler.scala
- [x] Criar PersonPrivateVehicleTripHandler.scala
- [x] Criar PersonTripManager.scala
- [ ] Modificar Person.scala para usar handlers
- [ ] Remover métodos privados antigos de Person.scala
- [ ] Compilar: `sbt compile`
- [ ] Testar: `sbt test`
- [ ] Criar testes unitários para handlers
- [ ] Documentar mudanças

---

## 💡 Dicas Importantes

1. **Lazy Initialization**: Todos os handlers são `lazy val`, então são criados apenas quando necessários.

2. **Funções como Parâmetros**: Passamos funções (`logDebug`, `logWarn`, `report`, etc.) como parâmetros para os handlers terem acesso às funções do ator sem herança.

3. **Imutabilidade**: Handlers retornam novos estados ao invés de modificar diretamente, seguindo princípios funcionais.

4. **Composição**: `TripManager` compõe todos os outros handlers, evitando que `Person` precise conhecer todos os detalhes.

5. **Pattern Matching**: Usar sealed traits (`TripStartResult`) torna o código mais seguro (compilador avisa se esquecer um caso).

---

## ❓ FAQ

**Q: Os handlers afetam a performance?**
A: Não. São lazy vals, criados apenas quando necessários, e não há overhead de chamadas.

**Q: Posso testar os handlers isoladamente?**
A: Sim! Cada handler pode ser instanciado e testado independentemente com mocks.

**Q: E se eu precisar modificar a lógica de métricas?**
A: Basta modificar `PersonMetricsReporter`. Nenhuma outra classe precisa mudar.

**Q: Como debugar com handlers?**
A: Use breakpoints nos handlers. Como são classes separadas, é mais fácil isolar problemas.

**Q: Posso compartilhar handlers entre diferentes tipos de Person?**
A: Sim! Os handlers são independentes do tipo específico de Person.
