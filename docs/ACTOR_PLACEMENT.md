# Actor host placement for visualization

`ReportEvent.node` records the reporting actor's JVM (`NodeIdentity.selfStamp`).
Parquet's `node` column and the Kafka JSON envelope's `node` field expose the host
label. The live bridge forwards that label. Payload references, such as the car
inside a Link's physics report, do not imply that the referenced actor shares its
reporter's host.

`SimulationBaseActor` emits `actor_placement` at initialization and migration
resume, including for reactive infrastructure without periodic model reports.
The payload contains `actor_type`; host identity remains envelope metadata.
Report selection respects the configured default sink and the enabled Kafka
mirror, with duplicate sinks removed.

Conservative placement metadata uses `PublishConservativePlacement`, separate
from model reports. An empty request ID consumes the owner's one-time registration
permit for that physical actor. A migration request ID consumes the pending
placement permit during an authorized resume. The owner validates the registered
entity and metadata label and tracks normal committed sink receipts. Migration
resume is acknowledged only after every frozen actor submits its placement and
all resulting reports receive sink acknowledgments. Ordinary model reports keep
their existing batch/finalization restriction. Dormant runtime-born actors send
an empty target list at registration so they publish no pre-birth output.

Optimistic and legacy initialization reports retain their existing reporting
and commitment paths. Ordinary event reports continue to carry their host stamp.
Replay history is based on report simulation ticks; host observations between
model events use the actor's current simulation tick.

Rebuild the engine and live bridge for the new live envelope. Re-run the replay
ETL to create `placements.json` from Parquet with host metadata. Old artifacts
still load and display unknown host colors where the metadata is unavailable.
