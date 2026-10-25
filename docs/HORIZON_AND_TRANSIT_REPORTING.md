# Horizon finalization and measured transit distance

Production policies finalize unfinished vehicle reports from the globally closed
horizon cut. A vehicle no longer needs a spontaneous wake exactly at the cap to
emit a final record. Events exactly at the inclusive horizon remain executable.

## Final reporting contract

For optimistic runs, a proven truncated cut carries `finalHorizon` in
`CommitRuntime`. The owning LTM validates the horizon and frontier, then forwards
the grant to its frozen LPs. An LP invokes `onHorizonFinalization` before flushing
buffered reports. The horizon must be strictly below GVT, all in-horizon work must
already be resolved, and the commit must come from the owner in a fresh frozen
round. These reports pass through the existing owner report ledger and actual sink
receipts before retirement and final storage flush.

Conservative GTM shutdown carries the horizon after every worker has closed its
channels and reported no next event inside the cap. Its LPs report before their
retirement ACK. The owner accepts these reports only for LPs in the authorized
final retirement set at that horizon, and cannot acknowledge stop until their sink
receipts arrive. The GTM then performs its existing sink-flush handshake.

The callback is a reporting-only view of the final model state. It temporarily
uses the horizon as the report timestamp, then restores the actor's last processed
tick. Model sends, scheduling, spontaneous completion, birth and destruction are
rejected inside this scope. Finalized optimistic LPs cannot resume execution.
This adds no model dispatch, timeout or new coordinator. Reply obligations still
constrain settled completion; at an authorized truncated cut their historical send
ticks do not represent unprocessed in-horizon work.

`JourneyHorizonReporter` is shared by Car, Bicycle, Motorcycle, Bus and Subway.
It emits one journey record and one SUMO record for an actually departed unfinished
journey. Completed or parked private vehicles and inactive/unborn services are
excluded. The records carry:

- `completion_reason = horizon_truncated`, `horizon_truncated = true`;
- `reached_destination = false` and `final_node = null`;
- SUMO `arrival = -1`, elapsed duration at the cap, and confirmed physical distance;
- the active link when known, without estimating position or partial-link distance.

The existing `journey_completed` event label is retained for consumers; the explicit
censor fields distinguish this record from a destination completion. Censored SUMO
records intentionally contain measured terminal fields rather than invented
waiting-time, speed or lane statistics. Callback deduplication is part of the final
transaction, not speculative model state: there is no rollback or migration after
that final cut.

## Passenger distance and rail arrivals

Bus/Subway boarding messages now include the vehicle's cumulative distance.
Person stores the boarded vehicle's typed identity, odometer and boarding tick in
checkpointed `PTWaitState`. Unload requests carry the current odometer. Matching
alighting computes the difference, includes it in leg/PT metrics and accumulates
it into the Person's daily distance. A request from a different typed vehicle
still receives a negative unload response and cannot end the ride. An invalid or
backwards odometer fails explicitly.

Subway previously added link distance at cleanup after station dwell. The arriving
passenger therefore saw an odometer missing the just-traversed link; unequal rail
lengths expose the error. Subway now counts physical arrival before unloading or
boarding, emits `subway_arrival`, and checkpoints the current link length and
arrival-counted guard. Cleanup cannot add the distance a second time. A rail arrival
at the horizon is counted even if its station dwell/cleanup ends after the cap.
The train's first physical departure tick is also checkpointed for final telemetry.

All these messages use the existing `sendMessageTo` ordering, delivery and anti
accounting. Restoring a checkpoint restores passenger baselines and train distance;
replaying or cancelling a boarding/arrival does not retain a discarded odometer.
Daily distance includes measured private-vehicle and transit legs. The subsequent
[walking follow-up](WALKING_DISTANCE.md) adds completed walking legs and preserves
the accumulated prefix across every leg transition; an unfinished walk is excluded.

See the [validation record](HORIZON_TRANSIT_VALIDATION_2026_10_24.md) for bounded
physical checks, policy comparisons and remaining limits.
