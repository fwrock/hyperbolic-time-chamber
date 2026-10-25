# Time Warp — Communication Scenarios (Car, Link, Node/Traffic Light, Managers, Rollbacks)

Current-state sequence diagrams, one per scenario. Derived from `Movable.scala`, `CarSignalHandler.scala`,
`NodeEventHandler.scala`, `SimulationBaseActor.scala` (`handleInteractWith`, `rollbackAndCascade`) and the
Optimistic GTM/LTM. Message names are the real event/data types; `Link` internals are summarized, not traced.

Legend: solid arrow = message; dashed = reply; `Note` = actor-local effect.

---

## 1. Meso link traversal — happy path (no rollback)

Enter-link: `Movable.enterLink()` sends `EnterLinkData`, registers an `AwaitedReply`, and calls
`onFinishSpontaneous(Some(t+1))` **immediately after the send** (it does not wait for the reply). The Car sits in
`Waiting` and is re-woken every tick (poll) until the reply arrives; the reply handler then calls
`onFinishSpontaneous(Some(exitTick))`.

Leave-link: `leavingLink()` sends `LeaveLinkData`, registers an `AwaitedReply`, clears `movableCurrentPath` and
calls `onFinishSpontaneous(Some(t+1))` immediately. There is no `Waiting` status and no poll; the Car moves on to
the next link while the reply is still in flight, and the reply handler only reports and reschedules.

```mermaid
sequenceDiagram
    participant LTM as LocalTimeManager
    participant Car
    participant LinkA as Link

    LTM->>Car: SpontaneousEvent(t)
    Note over Car: status = Ready → enterLink()<br/>status = Waiting
    Car->>LinkA: EnterLinkData
    Note over Car: registerAwaitedReply("enter-link:linkId")
    Car->>LTM: onFinishSpontaneous(Some(t+1))
    loop every tick until the reply arrives (poll)
        LTM->>Car: SpontaneousEvent(t+1, t+2, ...)
        Note over Car: Waiting: waitingTicksCounter++
        Car->>LTM: onFinishSpontaneous(Some(tick+1))
    end
    Note over LinkA: vehicleCount++<br/>reads exact count
    LinkA-->>Car: LinkInfoData (ReceiveEnterLinkInfo, requestSeq)
    Note over Car: isAwaitingReply ✔ → consume<br/>speed = linkDensitySpeed(...)<br/>status = Moving, report enter_link
    Car->>LTM: onFinishSpontaneous(Some(exitTick))
    LTM->>Car: SpontaneousEvent(exitTick)
    Note over Car: Moving → requestSignalState() (scenario 2)<br/>→ after access granted: leavingLink()
    Note over Car: status = Ready, currentPath = None
    Car->>LinkA: LeaveLinkData
    Note over Car: registerAwaitedReply("leave-link:linkId")
    Car->>LTM: onFinishSpontaneous(Some(t+1))
    Note over Car: no Waiting, no poll: next tick enters the next link
    LinkA-->>Car: LinkInfoData (ReceiveLeaveLinkInfo, requestSeq)
    Note over Car: isAwaitingReply ✔ → consume<br/>report leave_link<br/>onFinishSpontaneous(Some(tick+1)) or finish trip
```

---

## 2. Intersection: signal and capacity request (Car ↔ Node)

Before leaving a link the Car asks the **Node** for access to the next link. The Node replies on every branch.
The Car calls `onFinishSpontaneous(None)` immediately after sending (no reschedule, hence no poll), and the reply
handler re-wakes it via `scheduleEvent`. This is the difference from enter-link: no self-poll, so its clock does not
advance while waiting.

### 2a. Green / no signal / uncontrolled

```mermaid
sequenceDiagram
    participant Car
    participant Node

    Note over Car: requestSignalState()<br/>status = WaitingSignalState
    Car->>Node: RequestLinkAccessData(targetLinkId)
    Note over Car: registerAwaitedReply("link-access:linkId")<br/>onFinishSpontaneous(None)
    Note over Node: signal Green, or no signal entry,<br/>or no connection → assume Green<br/>replyGreenOrBufferForCapacity
    Node-->>Car: LinkAccessData(phase=Green, requestSeq)
    Note over Car: isAwaitingReply ✔ → consume key<br/>status = WaitingSignal, signalWaitUntil = now
    Car->>Car: scheduleEvent(currentTick)
    Note over Car: next spontaneous → leavingLink()
```

### 2b. Red signal

```mermaid
sequenceDiagram
    participant Car
    participant Node
    participant Reporter as report() pipeline

    Car->>Node: RequestLinkAccessData(targetLinkId)
    Note over Car: onFinishSpontaneous(None)
    Note over Node: Red → queuePos = signalWaitingCounts++
    Node-->>Car: LinkAccessData(phase=Red, nextTick, queuePosition)
    Node-)Reporter: signal_state_requested
    Note over Car: waitUntil = nextTick + queuePos × 2 ticks<br/>status = WaitingSignal<br/>signalWaitNeedsReverify = true
    Car-)Reporter: signal_wait
    Car->>Car: scheduleEvent(waitUntil)
    Note over Car: on wake: re-request access (re-verify)
```

### 2c. Target link full (capacity backpressure)

```mermaid
sequenceDiagram
    participant Car
    participant Node
    participant NextLink as Next Link

    NextLink-)Node: RegisterLinkCapacityData
    Car->>Node: RequestLinkAccessData(targetLinkId)
    Note over Node: signal OK but capacity Full → buffer request
    Node-->>Car: LinkAccessData(capacityState=Full)
    Note over Car: status = WaitingCapacity<br/>key NOT consumed (second reply expected)
    NextLink-)Node: LinkCapacityFreedData
    Node-->>Car: LinkAccessData (unsolicited grant, same key)
    Note over Car: now consume key → proceed as 2a
    opt Car destroyed while WaitingCapacity
        Car->>Node: CancelLinkAccessRequestData
    end
```

---

## 3. Traffic light phase change (TrafficSignal → Node → waiting Cars)

```mermaid
sequenceDiagram
    participant LTM as LocalTimeManager
    participant Phase as TrafficSignalPhaseHandler (in Node)
    participant Node
    participant Car

    LTM->>Node: SpontaneousEvent(phaseEndTick)
    Phase->>Node: advance to next phase
    Note over Node: state.signals(id).state = Green/Red<br/>reset signalWaitingCounts
    Node->>Node: scheduleEvent(next phase end)
    Note over Car: was WaitingSignal(Red) until waitUntil
    Car->>Node: RequestLinkAccessData (re-verify at waitUntil)
    Node-->>Car: LinkAccessData(Green) or Red again (new queue pos)
    Note over Car: Green → leavingLink()
```

---

## 4. Time Warp: straggler caused by the `Waiting` self-poll (main gap)

Root cause documented in `KNOWN_GAPS.md` (Cause 3) and audit §5.4. The poll advances the Car's clock without
business progress; the Link's correct reply is then stamped earlier than `currentTick`.

```mermaid
sequenceDiagram
    participant LTM as OptimisticLTM
    participant Car
    participant LinkA as Link

    LTM->>Car: SpontaneousEvent(t=100)
    Car->>LinkA: EnterLinkData (tick 100)
    Car->>LTM: onFinishSpontaneous(Some(101))
    loop Waiting poll (no real work)
        LTM->>Car: SpontaneousEvent(101..105)
        Note over Car: waitingTicksCounter++<br/>currentTick, highestProcessedTick climb
        Car->>LTM: onFinishSpontaneous(Some(t+1))
    end
    Note over LinkA: busy (other vehicles on its LTM routee)
    LinkA-->>Car: ReceiveEnterLinkInfo (tick 100)
    Note over Car: event.tick (100) < currentTick (105)<br/>→ STRAGGLER → rollbackAndCascade(100)
```

---

## 5. Time Warp: rollback and anti-message cascade

`SimulationBaseActor.rollbackAndCascade` + `RollbackHistoryHandler`.

```mermaid
sequenceDiagram
    participant Car
    participant History as RollbackHistoryHandler
    participant Report as reportBuffer
    participant LinkA as Link
    participant Node
    participant LTM as OptimisticLTM

    Note over Car: straggler (or anti-message) at target tick T
    Car->>History: rollbackTo(T)
    History-->>Car: restore state from checkpoint ≤ T,<br/>list of retracted sent messages
    Car->>Report: filterInPlace(tick < T)<br/>(discard buffered reports ≥ T)
    Note over Car: onRollbackDiscardPending(T)<br/>clears actor-local guards (journeyFinishedReported, …)
    Car--)LinkA: AntiMessage(messageId of EnterLinkData)
    Car--)Node: AntiMessage(messageId of RequestLinkAccess)
    Car->>LTM: resyncScheduleAfterRollback()<br/>scheduleEvent(currentTick, resync = true)
    Note over Car: replay logged events forward,<br/>then process the straggler in order
    LinkA->>LinkA: anti-message → find LoggedEvent → rollbackAndCascade<br/>(may emit further anti-messages: cascade)
    Node->>Node: same
```

---

## 6. Time Warp: superseded reply dropped *without* rollback

`isKnownSupersededReply` (Movable) consulted before the straggler trigger.

```mermaid
sequenceDiagram
    participant Car
    participant LinkA as Link

    Car->>LinkA: EnterLinkData (requestSeq=1)
    Note over Car: rolled back, re-sends
    Car->>LinkA: EnterLinkData (requestSeq=2)
    LinkA-->>Car: ReceiveEnterLinkInfo (requestSeq=1, old, tick < currentTick)
    Note over Car: isKnownSupersededReply:<br/>isAwaitingReply(key, "EnterLinkData", 1) = false
    Note over Car: DROP, no rollback<br/>(htc_stale_reply_dropped_without_rollback_total)
    LinkA-->>Car: ReceiveEnterLinkInfo (requestSeq=2)
    Note over Car: normal processing
```

---

## 7. Time Warp: rollback-orphaned reply (AwaitedReply protection)

Fix from 2026-09-05. A rollback restores `state.status` from before the request, which would make a
legitimately awaited reply look stale; the actor-local `AwaitedReply` registry (not in `state`) is the identity check.

```mermaid
sequenceDiagram
    participant Car
    participant Node

    Car->>Node: RequestLinkAccessData (requestSeq=7)
    Note over Car: registerAwaitedReply("link-access:L", seq=7)
    Note over Car: unrelated rollback restores state.status<br/>(no longer WaitingSignalState)
    Node-->>Car: LinkAccessData (requestSeq=7)
    alt Time Warp: identity check
        Note over Car: isAwaitingReply(key, "LinkAccessData", 7) ✔<br/>→ handled, not discarded as stale
    else conservative: status check
        Note over Car: status must be WaitingSignalState/WaitingCapacity
    end
```

---

## 8. Manager plane: scheduling, LVT/GVT, termination, report flush

```mermaid
sequenceDiagram
    participant Sim as Car / LinkA / Node
    participant LTM as OptimisticLocalTimeManager
    participant GTM as OptimisticGlobalTimeManager
    participant RM as ReportManager

    Sim->>LTM: ScheduleEvent(tick) / registerActor
    Note over LTM: hasStarted gate: no dispatch<br/>before StartSimulationTimeEvent
    GTM->>LTM: StartSimulationTimeEvent
    LTM->>Sim: SpontaneousEvent(tick)  (optimistic, no barrier)
    Sim->>LTM: onFinishSpontaneous(Some(next) | None)
    Note over Sim: report() buffered in reportBuffer<br/>until tick < GVT

    loop GVT rounds
        LTM->>GTM: LvtReportEvent(LVT)<br/>(min highestProcessedTick, MaxValue if no actors)
        Note over GTM: MarginBasedGVTEstimation: min over LTMs − margin
        GTM->>LTM: GvtUpdateEvent(GVT)
        LTM->>Sim: flushBufferedReportsUpTo(GVT)
        Sim-)RM: report rows (committed)
    end

    Note over GTM: TerminationPlateauDetector:<br/>GVT flat + all LTMs idle for N rounds
    GTM->>LTM: force-destruct actors
    Sim->>LTM: DestructAckEvent (after final flush)
    LTM->>GTM: LocalDestructCompleteEvent
    GTM->>RM: StopSimulationEvent (only after all acks)
    Note over RM: closes parquet writers
```

---

## 9. Putting it together: where rollbacks originate today

| Source of rollback | Trigger | Scenario |
|---|---|---|
| Car ← Link `ReceiveEnterLinkInfo` behind `currentTick` (poll inflated the clock) | Straggler | 4 → 5 (dominant: ~34 of ~42) |
| Car ← Link reply for a superseded request | Dropped without rollback | 6 |
| Car/Link/Node ← anti-message | Cascade | 5 |
| Car ← Node `LinkAccessData` behind `currentTick` | Straggler (Car resolved with `None`, so no poll — rarer) | 2 → 5 |
| Rolled-back Car, reply from before the request | Handled via AwaitedReply identity | 7 |
