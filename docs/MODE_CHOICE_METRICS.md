# Mode choice metrics in HTC View

HTC View's **People and transport** charts read
`htc_person_mode_choice_resolved_total` (grouped by `resolved_mode`) and
`htc_person_mode_choice_changed_total` (grouped by `source`). The Person plan manager
updates these counters when it applies a successful pending decision or PT timeout
replan, including decisions returned by the routing service. Dispatching a query,
failing a decision, and starting another leg of the selected itinerary do not count
as successful choices.

An itinerary's first nonwalking leg identifies its selected mode; an entirely
walking itinerary is `walk`. Access and egress walks do not create extra choices.
For an itinerary using multiple transit modes, the first transit mode is the chart
label; the report does not describe the complete modal sequence.

Initial decisions use `requested_mode="auto"` and `source="dynamic"`. They do not
increment the changed counter because there is no original concrete mode to compare
against. PT replans use the previous leg's mode and `source="pt_timeout_replan"`;
they increment the changed counter only when the selected mode differs.

Each successful decision also emits `person_mode_choice_resolved` through `report()`,
with origin/destination nodes, requested/resolved modes, strategy ID, source and tick.
Persisted reports follow the runtime's report commit rules. Prometheus counters are
process-lifetime operational observations; they are not rolled back by Time Warp
and must not be treated as committed scientific mode shares under optimistic replay.

Plans must contain `PendingDecision` elements to request initial mode choice.
Concrete legs retain their assigned mode. The legacy simulation-level
`enableDynamicModeChoice` field alone does not convert concrete plans into pending
decisions. Both city generators provide `--dynamic-mode-choice` for that conversion.

Before this repair, the current plan manager never called the old mode-choice metrics
helpers, leaving HTC View's charts empty even when decisions executed. Rebuild and
restart the engine to use the repaired path; historical counters are not recovered.
