# Progressive input in optimistic execution

A run still selects one policy before startup. Production Time Warp can now load
known JSON/SQLite scenario actors in successive windows instead of exhausting
all progressive sources before execution. The existing GTM coordinates the window;
no additional coordinator or model-specific core API is introduced.

Loading and model execution **alternate**. Enrollment pauses all LPs; background
prefetch concurrent with model execution is not implemented. This bounds the time
range materialized ahead of execution, not the number of live actors or heap bytes.

## Input boundary

The producer registers before startup. The GTM requests
`LoadOptimisticInputWindow(epoch, window, fromTick, throughTick)`. One request may
be outstanding. The producer immediately echoes `OptimisticInputWindowOpened`;
the GTM binds and watches the actual producer actor. A singleton proxy is not the
replying actor, so comparing its reference with the sender would reject valid input.
The epoch/window correlation establishes the initial actor, and subsequent replies
must come from that same actor. Independent restart requires coordinated recovery
and fails this implementation.

Each loader indexes immutable source data and streams matching records in chunks
of 500, with creator backpressure. JSON now binds records directly from the input
stream, including empty arrays and buffer refills, rather than copying the whole
file into a byte array before parsing. JSON still rescans the source for every
window; SQLite uses indexed range queries. Count indexes grow with distinct source
timestamps, and all actors sharing one timestamp still need enrollment together. Sharded creators wait for initialization ACKs;
pool creators wait for startup ACKs. In the accounted runtime, both ACK paths wait
for `RuntimeJoined`. Ownership and initial schedules are therefore installed before
the producer can acknowledge the window. The GTM also waits for outstanding
`LocalProcessRegistered` acknowledgements before accepting its coverage.

`OptimisticInputWindowReady` carries the correlated request, accepted inclusive
`loadedThrough`, actor count and explicit `closed` flag. While input remains open:

- The execution ceiling is `min(run horizon, GVT + speculation window, loadedThrough)`.
- The earliest remaining source input is `loadedThrough + 1`; GVT cannot exceed it.
- An empty model queue cannot close the producer or declare settled completion.
- A requested horizon, uncorrelated legacy ready message, or forwarded EOF from
  SimulationManager cannot advance the bound. Its different sender could otherwise
  overtake the producer's final registration acknowledgement.

Input windows start at zero, matching the existing progressive source contract.
For a later simulation start, earlier windows are accepted before execution begins.
A finite run requests no actors beyond its horizon. An open bound beyond that
horizon permits explicit horizon truncation, rather than claiming source exhaustion.

`htc.time-warp.input-window-ticks` defaults to 1000 and can be set with
`HTC_TIME_WARP_INPUT_WINDOW_TICKS`. It caps the producer's registered look-ahead;
the adaptive density algorithm may choose a shorter range. It is a positive tick
span, not an actor limit: an inclusive window can cover span + 1 timestamps, and
all actors at one tick still need enrollment.

## Enrollment between safe rounds

```mermaid
sequenceDiagram
    participant G as Existing GTM
    participant W as Owning LTMs
    participant P as Input producer
    participant L as New LPs
    G->>W: Freeze / collect / commit GVT at next unloaded tick
    G->>W: Retire eligible committed LPs
    W-->>G: Retirement acknowledged; owners paused
    G->>W: BeginInputEnrollment(round)
    W-->>G: InputEnrollmentOpened(round)
    G->>P: LoadOptimisticInputWindow(epoch, window, from, through)
    P-->>G: OptimisticInputWindowOpened(epoch, window)
    L->>G: JoinRuntime
    G->>W: RegisterLocalProcess
    W-->>G: LocalProcessRegistered
    W->>L: RuntimeJoined / execution horizon
    L-->>P: Initialization ACK through creators/loaders
    P-->>G: OptimisticInputWindowReady(coverage, closed)
    G->>W: CompleteInputEnrollment(round, coverage)
    W->>L: Fixed execution limits with updated ceiling
    W-->>G: InputEnrollmentCompleted(round)
    G->>W: ResumeRuntime(round)
```

Every owner acknowledges opening before the producer receives the request. Joins
are rejected during running, freezing, collection, commitment and retirement.
During enrollment, a new LP's start tick must lie in the requested range and be at
or above GVT; its initial wake must also be at or above GVT. Equality is safe because
only events strictly below GVT have committed. The LP joins frozen and receives
the finite run horizon and execution grant before resume. All owners acknowledge
completion before any resumes. Per-sender ordering puts grants ahead of resume.

The just-committed cut retains its old membership. The next round freezes and
collects the expanded membership afresh. No stale pre-enrollment snapshot authorizes
commitment of the new cohort. At the boundary, a frozen execution ceiling can be
one tick below GVT until the next window is accepted.

## Failure handling and model obligations

JSON/SQLite index, open/query, read and indexed-count mismatches are explicit source
failures. They never become successful zero-actor windows. Async results update
loader state through the mailbox. Unknown or duplicate creator batch replies do
not release backpressure. Producer/source loss and uncoordinated restart fail the
run; source contents must remain immutable and available on their loader nodes.
Count validation does not detect arbitrary same-count edits to input data.

This contract covers deterministic source enrollment. Models must initialize
state before joining. Executing actors introduce speculative children through the
separate [ordered birth protocol](RUNTIME_ACTOR_BIRTH.md), not through this producer
path. Its owner queue can retain traffic overtaking an accounted birth; unexplained
unknown/retired targets still fail. Shared dynamic feedback, recovery/migration,
and arbitrary live streams with revisable timestamps need separate adapters. New models still provide complete checkpoints, deterministic
replay inputs, explicit reply obligations and supported effects.

See [validation](PROGRESSIVE_OPTIMISTIC_VALIDATION_2026_10_24.md) and
[distributed accounting](DISTRIBUTED_OPTIMISTIC_RUNTIME.md) for evidence and limits.
