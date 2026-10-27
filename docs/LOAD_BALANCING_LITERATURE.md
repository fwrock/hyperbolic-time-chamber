# Applied load-balancing literature for HTC

Literature checked on 2026-10-25. Companion to
[Load Balancing and Attribution](LOAD_BALANCING.md), which documents the code,
foundational references, and conservative migration scope. All entries below are
application or simulation-method papers, not merely data-structure references.
BibTeX is retained in [references/load-balancing.bib](references/load-balancing.bib).

This is a focused literature review, not an exhaustive systematic review. It uses
publisher records, author manuscripts, and institutional repositories. An applied
paper can support a design rationale without being the historical source of HTC's
implementation. No inspected paper establishes the complete HTC strategy as its
own algorithm, and no benchmark result below is transferred to HTC.

## Mapping the literature to HTC

| HTC aspect | Application papers | Strength and boundary of the match |
|---|---|---|
| Geographic/quadtree grouping in Hybrid | [A1] dSUMO; [A2] Distributed MASON | Direct use of quadtree partitioning in traffic/spatial simulation; not HTC's complete quadtree + 2:1 refinement + scalar-weight combination |
| Recursive partitioning component | [A3] TRANSIMS; [A8] GENESIS CGDYN | Applied geometric/graph bisection and cell-based k-d trees; HTC currently partitions a scalar-weight list |
| Locality/load tradeoff in GeoAffinity | [A5] SEMSim Traffic; [A6] PATRIC; [A10] SPEEDES study | Direct application concern, but graph/clustering/communication-aware methods rather than HTC's exact weighted affinity formula |
| TypeAware grouping | [A10] class-informed placement; [A4] agent versus environment distribution | Related comparisons only; no direct match found for HTC's sequential same-class capacity buckets |
| Distributed execution without within-run rebalancing | [A3] TRANSIMS; [A10] static PDES placement | Direct examples of initial placement/static execution choices; not evidence that they use HTC's hash extractor |
| Prediction before migration | [A7] A-RESCUE 2.0; [A11] spatial PDES | Predictive traffic weighting/future-event workload estimates; not HTC's ordinary-least-squares extrapolation |
| Conservative synchronization for relocation | [A9] synchronization algorithms; [A1] dSUMO | Direct conservative-mode relevance; decentralized algorithms rather than HTC's singleton-coordinated exchange |
| Alternative conservative scheduling | [A12] WS-DLB | Shared-memory LP grouping plus work stealing; not cross-JVM shard migration |
| Road-network-guided spatial partitioning | [A13] SParTSim | Direct traffic application; road-aware regions rather than midpoint geographic quadrants |

The exact 2:1 refinement inspiration remains Sundar, Sampath, and Biros [R3 in the
companion]. The applied quadtree papers below do not by themselves establish use
of that refinement algorithm. Likewise, the reviewed papers do not establish a
prior publication for the exact GeoAffinity score or TypeAware bucket rule. This
limited search result is not a novelty claim.

For the explicit distinction between established techniques, HTC-specific choices,
and candidate research contributions, see the
[novelty assessment](LOAD_BALANCING.md#research-contribution-and-novelty-assessment).

## Similar approaches and different alternatives

The comparison below is an interpretation of the cited methods relative to HTC,
not a cross-paper performance ranking. These are algorithm families; one paper
can combine several families, and the timing of adaptation is a separate choice.

| Approach | Representative simulation papers | Main partitioning/balancing decision | Similarity or difference from HTC |
|---|---|---|---|
| Measured initial placement, retained within a run | TRANSIMS [A3]; static SPEEDES placement [A10] | Choose placement using estimated or previously measured costs | Supports the no-within-run-rebalancing option; TRANSIMS can improve the next run's placement |
| Quadtree geographic decomposition | dSUMO [A1]; Distributed MASON [A2] | Divide space into hierarchical regions and balance regional work | Closest family to Hybrid's geographic layer; refinement and migration units differ |
| Geometric k-d-tree decomposition | GENESIS CGDYN [A8] | Adjust spatial cell assignments using hierarchical geometric splits | Related to the tree terminology; HTC's current list partitioner is scalar rather than geometric |
| Road-network-guided regions | SParTSim [A13] | Use road structure to construct partitions | Retains spatial meaning while incorporating network structure missing from plain quadrants |
| Graph partitioning | SEMSim Traffic [A5]; A-RESCUE [A7] | Partition a weighted network while accounting for links between parts | Alternative to a geographic tree or a fixed affinity score; can represent communication relationships explicitly |
| Traffic clustering | PATRIC [A6] | Identify and distribute interacting traffic clusters | Similar locality objective to GeoAffinity; grouping follows traffic structure rather than fixed geographic correspondence |
| Diffusive balancing | Mastio et al. [A4] | Redistribute load between computation units through a diffusive method | Alternative redistribution mechanism to HTC's global migration-plan execution |
| Class/domain-informed placement | Carlisle and Merkle [A10] | Co-locate objects using domain knowledge and communication measurements | Related to TypeAware's use of class information, but permits grouping objects of different classes |
| Work stealing plus estimated LP grouping | WS-DLB [A12] | Assign LP groups and let idle threads acquire executable LP work | A conservative scheduling alternative for shared memory; transferring execution to another thread has different costs and requirements from moving actor state between JVMs |

For the thesis, compare these methods along the same dimensions: the object being
partitioned, load signal, communication/locality objective, adaptation timing,
unit of movement, and synchronization/memory architecture. For example, a graph
partitioner using measured computation and communication costs addresses a
different objective from equalizing entity counts. A shared-memory scheduler's
results cannot establish the migration overhead of a distributed actor system.

## Mobility and traffic simulation

### [A1] Bragard, Ventresque, and Murphy — dSUMO (2017)

**Self-Balancing Decentralized Distributed Platform for Urban Traffic Simulation.**
IEEE Transactions on Intelligent Transportation Systems.
[DOI](https://doi.org/10.1109/TITS.2016.2603171);
[author manuscript, UCD](https://researchrepository.ucd.ie/bitstreams/46718144-aed9-443e-ab31-7e40505bf554/download).

dSUMO evaluates QuadTree, Smart QuadTree, and SParTSim partitioning of a road
network. It combines local conservative synchronization with global synchronization
and dynamic load redistribution. Section V describes the evaluated partitions;
Sections III–IV describe synchronization and rebalancing.

**Use for HTC:** a close mobility precedent for geographic partitioning and
conservative relocation. Its decentralized boundary redistribution differs from
moving a Pekko shard through a singleton coordinator. Cite it for the algorithm
family and application, not as the source of HTC's complete Hybrid protocol.
The repository dates the journal publication to 2017; the DOI includes 2016.

### [A3] Nagel and Rickert — TRANSIMS (2001)

**Parallel implementation of the TRANSIMS micro-simulation.** Parallel Computing,
27(12), 1611–1639.
[DOI](https://doi.org/10.1016/S0167-8191(01)00106-5);
[author manuscript, TU Berlin](https://svn.vsp.tu-berlin.de/repos/public-svn/publications/kn-old/parallel/parallel.pdf).

The traffic model uses geographic domain decomposition and compares orthogonal
bisection with METIS graph partitioning. Section 7's adaptive balancing collects
execution costs during one run and feeds them into partitioning for the next run.
This is adaptation between runs, not within-run repartitioning.

**Use for HTC:** an important mobility example for choosing good initial
partitions while retaining them during execution. Its recursive/graph partitioning
also supplies a comparison for the custom scalar-weight partitioner. Distinguish
vehicle transfers across domain boundaries from changing domain ownership.

### [A4] Mastio, Zargayouna, Scemama, and Rana (2018)

**Distributed agent-based traffic simulations.** IEEE Intelligent Transportation
Systems Magazine, 10(1), 145–156.
[DOI](https://doi.org/10.1109/MITS.2017.2776162);
[publication record and paper, Cardiff](https://orca.cardiff.ac.uk/id/eprint/108450/).

The study compares distributing agents equally across computation units with
partitioning their environment. It also proposes diffusive dynamic load balancing.
The two distribution patterns are evaluated with macroscopic and microscopic
agent-based traffic models.

**Use for HTC:** a direct mobility reference for why actor grouping and interaction
structure matter. Equal agent distribution is related to an entity-count baseline;
environment partitioning is related to geographic grouping. Neither establishes
HTC's TypeAware capacity-bucket rule or exact hash implementation.

### [A5] Xu, Cai, Aydt, and Lees — SEMSim Traffic (2014)

**Efficient graph-based dynamic load-balancing for parallel large-scale agent-based
traffic simulation.** Proceedings of the Winter Simulation Conference, 3483–3494.
[DOI](https://doi.org/10.1109/WSC.2014.7020180);
[paper, TUM repository](https://mediatum.ub.tum.de/doc/1281606/631471.pdf).

The authors develop graph-based dynamic load balancing for SEMSim Traffic and
evaluate it with real-world traffic data. Their objective includes communication
overhead as well as workload distribution.

**Use for HTC:** a close application comparison for GeoAffinity's locality/load
tradeoff. Graph-based communication minimization differs from favoring a node
that hosts matching or adjacent static shards. HTC should compare measured remote
messages and migration cost rather than assume its affinity score has the same
effect. The paper's abstract was inspected through the repository's indexed text;
the full-text endpoint returned HTTP 429 during this review.

### [A6] Wan, Yin, Wang, Ben-Dor, Ogulenko, and Huang — PATRIC (2023)

**PATRIC: A high performance parallel urban transport simulation framework based
on traffic clustering.** Simulation Modelling Practice and Theory, 126, 102775.
[DOI and publisher record](https://doi.org/10.1016/j.simpat.2023.102775).

PATRIC uses traffic clusters to distribute work and reduce inter-node interactions.
It adjusts partitions as traffic-cluster distributions change. The publisher's
experiment description reports integration with MATSim and MPJ Express.

**Use for HTC:** applied evidence for considering interaction locality alongside
load. It is a clustering approach, not a quadtree implementation or HTC's affinity
score. The accessible publisher abstract and section previews support this
description; this entry does not claim a full algorithm reproduction.

### [A7] Gehlot, Zhan, Qian, Thompson, Kulkarni, and Ukkusuri — A-RESCUE 2.0 (2019)

**A-RESCUE 2.0: A High-Fidelity, Parallel, Agent-Based Evacuation Simulator.**
Journal of Computing in Civil Engineering, 33(2), article 04018059.
[DOI and publisher abstract](https://doi.org/10.1061/(ASCE)CP.1943-5487.0000802).

The evacuation simulator periodically repartitions its road network using a
multilevel graph partitioner. A predictive network-weighting scheme incorporates
current and anticipated traffic loading when representing computational work.

**Use for HTC:** direct mobility evidence for prediction-informed balancing.
The accessible abstract establishes predictive weighting, but does not establish
that it uses HTC's linear-regression predictor. Cite 2019 for the issue year;
the publisher records online publication in November 2018. Full-text algorithm
details were not available during this review.

### [A13] Ventresque et al. — SParTSim (2012)

**SParTSim: A Space Partitioning Guided by Road Network for Distributed Traffic
Simulations.** IEEE/ACM DS-RT, 202–209.
[DOI](https://doi.org/10.1109/DS-RT.2012.37);
[IBM Research publication record](https://research.ibm.com/publications/spartsim-a-space-partitioning-guided-by-road-network-for-distributed-traffic-simulations).

The paper describes distributed SUMO and a road-network-guided spatial
partitioner intended to balance load and limit communication. The IBM record
reports a comparison with uniform spatial partitioning.

**Use for HTC:** a mobility alternative between purely geometric grouping and
communication-aware graph methods. Its use of road structure differs from dividing
world bounds into quadrants. This entry relies on the author/institution abstract;
the later dSUMO manuscript [A1] also evaluates SParTSim as a comparator.

## Spatial agents and physical simulation

### [A2] Wang, Wei, Simon, Luke, Crooks, Freelan, and Spagnuolo — Distributed MASON (2018)

**Scalability in the MASON Multi-Agent Simulation System.** IEEE/ACM DS-RT.
[DOI](https://doi.org/10.1109/DISTRA.2018.8601006);
[author manuscript](https://people.cs.gmu.edu/~sean/papers/dsrt18.pdf).

Section V describes local, global, and hierarchical load balancing. Its
hierarchical scheme uses quadtree regions and measured LP runtimes; it permits
two-leaf subdivisions near the bottom to accommodate processor counts beyond
powers of four.

**Use for HTC:** direct spatial-agent application of quadtree-based load
balancing. Its partition-boundary adjustment differs from HTC's intended adaptive
leaf refinement and whole-shard relocation. The manuscript's indexed Section V
was inspected; direct retrieval of the relocated author URL timed out. Do not
confuse Distributed MASON in this paper with a claim about every D-MASON version.

### [A8] Jung, Tan, and Sugita — GENESIS CGDYN (2024)

**GENESIS CGDYN: large-scale coarse-grained MD simulation with dynamic load
balancing for heterogeneous biomolecular systems.** Nature Communications, 15,
article 3370.
[DOI and full article](https://doi.org/10.1038/s41467-024-47654-1).

This spatial particle simulation implements cell-based k-d-tree domain
decomposition and periodically updates cell-to-subdomain assignments to respond
to nonuniform particle distributions.

**Use for HTC:** direct physical-simulation application of k-d-tree-based
balancing, useful alongside the foundational Bentley reference. It is molecular
dynamics with time integration, not traffic simulation or an event-driven PDES
protocol. Its geometric partitioning is also different from HTC's scalar-weight
partitioning despite the current component name.

## Discrete-event simulation and synchronization

### [A9] Bragard, Ventresque, and Murphy — conservative synchronization (2014)

**Synchronisation for dynamic load balancing of decentralised conservative
distributed simulation.** ACM SIGSIM-PADS, 117–126.
[DOI](https://doi.org/10.1145/2601381.2601386);
[author manuscript, UCD](https://researchrepository.ucd.ie/server/api/core/bitstreams/9e7233a6-3920-4667-a0cb-bc0229b6e7da/content).

The paper proposes TaSyn and GenSyn to bring locally synchronized conservative
LPs to a common step so that global operations such as load balancing can occur.
Its evaluation uses PeerSim to simulate the distributed system; this is not itself
an evaluation of the HTC or SUMO migration implementation.

**Use for HTC:** especially relevant methodological prior work for conservative
pause/coordinate/redistribute/resume. Its decentralized coordination differs from
HTC's GTM/LBM singleton arrangement. The subsequent dSUMO paper [A1] provides the
traffic application connection.

### [A10] Carlisle and Merkle — class-informed static placement (2004)

**Automated Load Balancing of a Missile Defense Simulation Using Domain Knowledge.**
The Journal of Defense Modeling and Simulation, 1(1), 59–68.
[DOI and publisher abstract](https://doi.org/10.1177/154851290400100105).

The study applies static load-balancing algorithms in SPEEDES. It uses domain
knowledge and runtime measurements to identify frequently communicating objects
of different classes and place them together.

**Use for HTC:** related work for model/class-informed placement and distributed
execution with static allocation. It groups communicating objects across classes;
HTC TypeAware groups registrations of the same class into capacity-limited buckets.
This is therefore a comparison, not evidence that the identical TypeAware strategy
already exists. This description is limited to the accessible abstract.

### [A11] Deelman and Szymanski — spatial PDES (1998)

**Dynamic Load Balancing in Parallel Discrete Event Simulation for Spatially
Explicit Problems.** Workshop on Parallel and Distributed Simulation, 46–53.
[Author manuscript](https://www.cs.rpi.edu/~szymansk/papers/pads98.pdf).

This method predicts work from scheduled future events, exchanges load information
at GVT calculation, and redistributes spatial data between neighboring LPs with a
ring communication structure.

**Use for HTC:** a direct spatial-PDES reference for prediction and locality-limited
migration. Its GVT-based optimistic context differs from HTC's conservative migration
scope, and event-based prediction differs from extrapolating recent entity counts.
The original manuscript's indexed abstract supports this entry; its PDF text
extraction is corrupted. No detailed migration-correctness equivalence is claimed.

### [A12] Tang et al. — conservative work stealing (2017)

**A work-stealing based dynamic load balancing algorithm for conservative parallel
discrete event simulation.** Winter Simulation Conference, 798–809.
[DOI](https://doi.org/10.1109/WSC.2017.8247833);
[conference paper](https://www.informs-sim.org/wsc17papers/includes/files/059.pdf).

WS-DLB combines estimated LP grouping with runtime work stealing. It measures LP
model-computation time, periodically recalculates groups, and lets idle threads
steal LP work. The study targets synchronous conservative PDES on multicore
shared-memory systems and evaluates a synthetic benchmark.

**Use for HTC:** a different conservative balancing approach, useful for comparing
worker scheduling with cross-JVM relocation. Its lock-free thread scheduling does
not supply HTC's distributed snapshot, ownership-transfer, or shard-restore protocol.

### [A14] Weissman — cost/benefit-aware adaptation (2002)

**Predicting the Cost and Benefit of Adapting Data Parallel Applications in
Clusters.** Journal of Parallel and Distributed Computing, 62(8), 1248–1271.
[DOI and publisher abstract](https://doi.org/10.1006/jpdc.2002.1838).

The paper develops an analytic framework for choosing between adaptation methods,
including dynamic load balancing, migration, and processor addition/removal, using
their predicted cost and benefit. Its applications include numerical solvers and
gene-sequence comparison.

**Use for HTC:** broader parallel-computing prior work that limits claims about a
new decision to migrate only when worthwhile. It is not a mobility simulation or
a conservative PDES migration protocol. The accessible publisher abstract supports
this description; a claim of a distinct HTC profitability model would require a
full-method comparison.

## How to use these references in the thesis

Use two citation layers: foundational structures/methods from [the companion](LOAD_BALANCING.md),
then application studies from this page. For example:

> Geographic decomposition and quadtree-based load balancing have been investigated
> in distributed traffic simulation [A1] and spatial multi-agent simulation [A2].
> Traffic simulators also address communication-aware graph partitioning [A5] and
> traffic-cluster-based distribution [A6]. HTC's geographic and affinity strategies
> can be positioned against these approaches, while separately reporting their
> implementation and validation status.

For execution without within-run rebalancing, [A3] is particularly useful: its
Section 7 adapts the next run's partition from measured costs. For conservative
relocation, pair [A9]'s method with [A1]'s traffic application. For prediction,
compare [A7] and [A11] while stating how their workload signals differ from HTC's.
For TypeAware, keep [A10] as related work and state that an exact bucket-rule match
has not been established.

A suitable evaluation reports initial partitioning separately from runtime
relocation, measured work separately from entity-count proxies, and remote-message
cost separately from load imbalance. It should also distinguish transfers caused
by a modeled vehicle crossing a fixed domain boundary from repartitioning the
domains or relocating a shard. Numerical performance results from these papers
are platform/scenario-specific and are not expected HTC speedups.
