# MICRO following across Link boundaries

The opt-in `LinkState.downstreamLookahead` keeps a following vehicle constrained
by its leader after the leader's front bumper enters another Link. Without this
observation, the approach's front vehicle sees free road as soon as its leader
leaves the approach queue, even while that leader's rear bumper is still nearby.
This error compounds during queue discharge.

## Input contract

Enable `downstreamLookahead` on single-lane MICRO Links with exactly one physics
step per runtime tick. The step must match the scenario's `tickDuration`. The
validated fractional-clock scope remains static-routing Car/Link/Node/TrafficSignal.
Defaults retain the existing mixed-model behavior. A downstream Link must also
provide single-lane MICRO observations with the same physics step; incompatible
observations fail instead of being treated as empty roads.

`VehicleInLane.nextLinkId` retains the requested next road. For finite junctions,
the approach must supply `downstreamConnectors`, mapping that road's ID to the
connector Link ID, consistently with the Node's `junctionMovements`. Point nodes
need no mapping. The SUMO fixture generator writes both representations from the
same compiled movement. This is not a general network geometry importer.

For example, if road 7 reaches road 9 through connector 8, road 7 supplies:

```json
{
  "downstreamLookahead": true,
  "microTimeStep": 0.1,
  "microTicksPerGlobalTick": 1,
  "downstreamConnectors": {"9": 8},
  "stopLineGapM": 1.0
}
```

The independent `stopLineGapM` places a red-signal stop before the lane end; its
native default remains zero. The matched v2 fixtures explicitly use one metre
in both simulators, matching SUMO's documented default `jmStoplineGap`.
See [SUMO junction parameters](https://sumo.dlr.de/docs/Definition_of_Vehicles%2C_Vehicle_Types%2C_and_Routes.html#junction_model_parameters).
No fitted crossing delay or per-vehicle correction is added.

## Exchange and ordering

On first vehicle entry for a next road, the approach subscribes to that road and
its optional connector through ordinary `sendMessageTo` transport. The downstream
Link sends an initial immutable occupancy snapshot and publishes after entry,
leave and occupied physics steps, including the final empty state. Its nearest
rear bumper, actual velocity and vehicle length define the boundary leader.
The approach also looks through an empty connector onto the next road, adding
the connector's measured length to the coordinate.

Each snapshot carries its tick and a checkpointed publication sequence. A
receiver keeps the latest sequence for each of two distinct tick cohorts.
Physics integrating `(t-1,t]` uses only a snapshot strictly before `t`; a
same-tick publication cannot overwrite that start-of-step view. Conservative
channel closure delivers preceding-tick output before the next tick, while late
optimistic delivery cancels and replays affected speculation normally.

Subscription and publication introduce no awaited reply or additional spontaneous
completion. Receivers do not republish upon receiving an observation, so cyclic
roads cannot create a same-tick publication loop. Existing Link spontaneous
branches continue to finish independently. Subscriber IDs, requested IDs,
publication sequence and observation histories all live in `LinkState`, and
JSON restore retains numeric map keys and typed snapshots. Existing owner-ledger,
positive/anti-message, GVT and report-commit paths remain authoritative.
If rollback undoes a subscription, a positive publication can still arrive before
its anti-message. The receiver consumes and ignores that publication while it has
no requested boundary, without creating state, new output or a wait obligation.

## Scope and cost

The implementation adds boundary messages and checkpoint state per subscribed
adjacency. It does not provide multi-lane downstream selection, cross-junction
conflict priority or a proof of city-scale cost. Observation extends through one
optional connector and the next road; very short sequences needing leaders beyond
that road require a longer observation horizon and are not established by these
fixtures. Subscriber sets remain enrolled
for the static Link lifetime. Bounded policy/layout report comparisons are needed
alongside traffic measurements; engine termination alone is insufficient.
