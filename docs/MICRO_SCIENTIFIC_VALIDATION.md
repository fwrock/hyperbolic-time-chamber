# MICRO timing and scientific validation

This stage separates implementation checks, agreement with SUMO and empirical
traffic validation. Passing a regression tolerance does not establish accuracy for
an arbitrary traffic study. Scientific criteria in
[`sumo_scientific_protocol_v1.json`](../scripts/scenario/sumo_scientific_protocol_v1.json)
are provisional numerical-equivalence targets for the specified experiments.
They were committed as `b68e5e0` before executing the held-out cases. The protocol
SHA-256 is `4c49d7e6e818d45dd5fc38fa062498c6fdbf9a6bc673315698b92b2327ddaacb`.
Any later revision needs a new version and fresh validation cases; failed criteria
remain in the results.

## Physical clock and remainder contract

`Simulation.tickDuration` now specifies physical seconds per integer runtime tick.
The default remains one second. Fractional clocks divide a second into an integer
number of ticks. Existing EventOrder, owner acceptance, anti-message, GVT and report
receipt protocols continue ordering integer ticks; floating-point physical times
never determine mailbox ordering. Configuration times, including departures,
signals and the horizon, must be expressed in the selected runtime ticks.
`SimulationUtil` installs the clock setting on each JVM before shard creation.

The first supported fractional-clock scope is MICRO Car, Link, Node and
TrafficSignal, with static routing and no model plugins, dynamic mode choice or
model-created actors. Startup rejects other source types; Link initialization
rejects MESO links and physics intervals different from `tickDuration`. Person,
Bus, Subway, Bicycle and Motorcycle continue using the one-second clock until
their physical-time calculations have explicit adapters. Their default behavior
is retained; this stage does not imply fractional-clock support for those models.

Setting `carryMicroRemainder=true` on a Link requires one physics step per runtime
tick and a link longer than a speed-limit physics step. A crossing's unused
fraction of that step travels through `MicroLeaveLinkData`, checkpointed
CarJourneyState and `EnterLinkData`. Node signal/capacity grants retain their
existing identities. Car uses the remainder only at the same tick with positive
carried velocity; a capacity/signal stop or later grant discards it. Destination
Link caps velocity to its speed limit and motion to space behind the downstream
rear bumper. A blocked remainder stops the entrant. The entry acknowledgment
contains the actual lane position and velocity, including curve coordinates.

This avoids retroactive integration of an entire old global interval on a Link
that has already advanced. The minimum-length restriction prevents an unsupported
multi-link jump within the same physics step. Supporting smaller links or different
time units requires another explicit transport/integration design.

All behavior-affecting values are in model state/message payloads, copied by
interning and cleared on trip reset. Cold-peer codecs cover nonzero remainders and
entry positions. Car tripinfo and Link elapsed-time/summary metrics use physical
seconds, while `tick` fields remain runtime ticks. The independent comparison
converts timestamps with the recorded clock and uses acknowledged entry positions;
it never fits a time shift. At an inclusive horizon, the accounted runtime owns
finalization: a final MICRO update must not prematurely finish the Car in its
legacy local cap branch.

## Experiment design

The convergence experiment holds 0.1-second integration fixed, compares runtime
quanta of 1, 0.5 and 0.1 seconds on a 1.2 km twelve-link route, and separately
enables remainder transfer at the finest clock. It uses a common one-second
comparison grid across resolutions. Both policies execute the finest carried case.

Held-out cases vary road lengths, turning direction and demand; they include a
heterogeneous ten-link route and a denser protected signal stream. They were not
used to select model parameters. Every case records exact physical inputs,
protocol/executable hashes, completed-trip coverage, signed bias, absolute errors,
trip-time distributions and trajectory errors. Full committed report multisets
compare conservative and optimistic execution.

Additional measures include halted approach queues sampled at equal times,
matched consecutive discharge headways within a green interval, and completed
cohort throughput over the fixed horizon. This throughput does not estimate
saturated capacity. Queue means speed below 0.1 m/s, rather than every slowed
vehicle. Definitions and thresholds are frozen with the protocol.

Five independent seeds perturb input demand headways, shared exactly by HTC and
SUMO. Driver dynamics remain deterministic. Paired inference uses scenario-level
mean errors, not vehicles/time samples as independent observations. A 95% Student-t
interval with four degrees of freedom is exploratory and assumes approximately
normal independent scenario-level differences. It measures variability conditional
on the specified demand distribution, not broader model or field-data uncertainty.

## Empirical pilot

The official [USDOT/FHWA NGSIM dataset](https://data.transportation.gov/d/8ect-6jqj)
provides a bounded exploratory free-flow forecast. Metadata documents feet,
feet/second and 100 ms observations. The fetcher retains query URLs, raw hashes,
license and recommended attribution. It removes exact duplicates and rejects
conflicting timestamps, gaps, lane changes and recorded preceding vehicles.

Subject selection and quality rules are persisted before downloads or forecast
errors. For each of three qualifying subjects, a one-second prefix estimates
initial cruising speed; the next ten seconds are held out for position/speed
prediction by the actual HTC MICRO simulator. The speed is both the initial and
desired free-flow speed, and the link extends beyond the observation horizon.
No forecast observation fits those parameters. Horizon telemetry must remain
censored, with no invented completed trip distance.

This pilot tests a conditional isolated cruising forecast. It does not validate
following, queues, junctions or network performance. Annotation/position derivative
inconsistency is reported. There is no field-derived acceptance threshold or
empirical-validity claim. General traffic validation still needs study-specific
targets, appropriate observed conditions and independent validation data, consistent
with [FHWA validation guidance](https://ops.fhwa.dot.gov/trafficanalysistools/tat_vol3/sect0.htm).

## Reproduction

```bash
sbt test assembly
python3 -m unittest discover -s scripts/scenario -p 'test_*.py' -v
python3 scripts/scenario/run_sumo_scientific_campaign.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/micro-convergence --stage convergence --base-port 44000
python3 scripts/scenario/run_sumo_scientific_campaign.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/micro-held-out --stage held-out --base-port 45000
python3 scripts/scenario/run_sumo_scientific_campaign.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/micro-stochastic --stage stochastic --base-port 46000
python3 scripts/scenario/ngsim_field_pilot.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/micro-field-pilot --base-port 47000
```

Use fresh output directories and the same executable on all JVMs. The campaign
returns failure on engine/measurement/parity failures; a failed scientific
equivalence criterion remains a recorded result in `campaign.json`. Inspect
`allScientificCriteriaPassed` explicitly. The empirical pilot separately reports
whether three eligible forecasts completed and always records that empirical
validity has not been established. Raw observations and simulator output belong
under temporary directories; retained acceptance records must identify any
excluded attempt and why it was excluded.
