# Conservative channel closure

The simulation selects one policy before startup. The production conservative
factory, `ConservativeGlobalTimeManager.props`, constructs
`ChannelClosedGlobalTimeManager` and `ChannelAwareLocalTimeManager` workers. The
old `SelectiveBarrier` class remains for compatibility tests; direct construction
of that class does not obtain the new guarantees.

See [the validation record](CONSERVATIVE_CHANNEL_VALIDATION_2026_10_23.md) for the
487-test build and simulator results. The later
[distributed validation](DISTRIBUTED_OPTIMISTIC_VALIDATION_2026_10_23.md) fixes the
MICRO summary counters and records the current artifact.

## Ownership and execution

The existing GTM fixes LTM membership and assigns each typed logical process
identity to one owner. Passive infrastructure registers too. Private scheduler
handles isolate numeric IDs belonging to different model classes. Initialization
acknowledgement follows ownership registration.

There is no additional conservative coordinator actor. Ordinary model delivery is:

```mermaid
sequenceDiagram
    participant A as Source LP
    participant L as Source LTM
    participant R as Destination LTM
    participant B as Destination LP
    participant G as Existing GTM
    A->>L: Timestamped interaction
    Note over L: Record outgoing token before sending
    L->>R: ConservativeTransfer
    R->>B: ConservativeDelivery
    Note over B: Queue input without executing the model
    B-->>R: Input accepted and queue minimum
    R-->>L: Transfer accepted
    L-->>G: Batch complete after local channels drain
    Note over G: Wait for every LTM; collect fresh frontier summaries
    G->>R: Execute minimum tick and causal microstep
    R->>B: Execute authorized batch
```

The destination acknowledges queue acceptance, without waiting for a business
reply to the original sender. Every send, schedule update and report precedes
that LP's batch completion on the same LP-to-owner channel. An LTM completes only
after its callbacks, outgoing transfers, incoming acceptances and report receipts
are settled. The GTM then collects fresh summaries from every LTM; an early idle
summary from before another worker's delivery is never reused.

The next grant is the global minimum `(tick, causal microstep)`. Each LP orders all
inputs at that stage by the full stable `EventOrder` key. Generated messages must
advance the causal stage. Independent LPs can execute concurrently; no global
permission is requested for each individual model message. This closes channels
before execution instead of repairing unsafe execution with rollback.

A registered reply obligation may keep a spontaneous dispatch in flight while
later causal batches deliver its answer. Returning from a callback and accepting
a transport delivery are distinct from completing that obligation. Empty queues
with unresolved obligations fail the run; an idle timeout cannot authorize success.

## Retirement and output

`selfDestruct` records terminal intent. Ordinary physical retirement is authorized
at a subsequent grant after the previous batch has drained globally, and only
without pending input, replies or an in-flight spontaneous dispatch. Worker-local
completion alone is insufficient. Final retirement waits for explicit ACKs; finite
horizon truncation is distinguished from settled completion. Retirement does not
run model cleanup that could create unaccounted messages.

Reports go from the owning LTM to the actual sink. Each sink acknowledges acceptance
before the batch closes. At the end, the GTM gathers the actual sink references,
requests an asynchronous flush through ReportManager, and publishes
`[CONSERVATIVE-COMPLETE]` only after successful drain. Receipt and durable flush
are separate barriers. This does not provide crash-safe exactly-once delivery.

## Scope and remaining costs

Progressive scenario loading remains available through the existing window
request/ready contract: execution cannot pass accepted input coverage and normal
completion requires explicit producer exhaustion. Late actors register only while
waiting for input and above the executed physical frontier. This is deterministic
external input, not arbitrary model-caused birth.

The supported model path rejects live actor spawn, shared dynamic routing feedback,
untyped destinations and unaccounted model input. Transit station birth and shared
routing need accounted adapters before use. Unexpected LP/LTM loss fails the run;
independent restart and shard migration are not supported recovery mechanisms.

The GTM still performs one global agreement per causal batch, with work proportional
to the number of LTMs, and advancement waits for the slowest worker. LTM ledgers
distribute per-message work but add delivery ACKs. No city-scale throughput result
has been established. Hierarchical frontier aggregation and lookahead are possible
later optimizations, subject to measured need and the same safety tests.

Optimistic execution now also uses LTM-owned ledgers, with a separate frozen-cut
protocol covering positives, anti-messages, controls and commitment. Its production
factory no longer creates the centralized reference coordinator. The policies use
different execution protocols and remain fixed for each whole run. See
[the optimistic protocol](DISTRIBUTED_OPTIMISTIC_RUNTIME.md).
