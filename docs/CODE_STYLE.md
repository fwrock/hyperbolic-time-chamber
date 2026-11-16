# Scala source conventions

These conventions apply to new code and code being refactored. Existing packages migrate in
reviewable steps; formatting the whole repository is a separate change. `AGENTS.md` remains the
source of simulation, transport, persistence and synchronization invariants.

## Packages and responsibilities

Keep production sources under `org.interscity.htc` and mirror package paths below `src/main/scala`.
Keep tests in the corresponding path below `src/test/scala`.

- `core`: runtime and infrastructure independent of a mobility model.
- `core/entity`: configuration, event and state contracts.
- `model/hybrid`: active mobility behavior and its data types.
- `model/hybrid/support/<actor>`: stateless handlers, wired through actor-owned callbacks.
- `model/hybrid/micro`: physics and lane-change strategies.
- `model/mobility`: retained legacy data contracts; do not add actors here.

Choose a domain-specific package before adding another generic utility. Keep orchestration,
calculation, persistence and reporting responsibilities separate. Use composition and existing
handler patterns. Introduce a trait when implementations need a meaningful shared contract;
do not create a trait for every class. Use case classes for value/state records and enums for
closed choices, following existing serialization and state conventions.

## One primary type per file

Name each file after its primary class, case class, trait, enum or object. Keep its companion
in the same file. Put unrelated top-level types in their own files.

Scala requires direct subclasses of a sealed class or trait in the same source file. Preserve
those hierarchies together, including their leaves, and name the file for the root type. Do not
remove `sealed` to satisfy a file-count rule: exhaustiveness and serialization contracts take
priority. Private implementation types can remain nested within their owning type when they
have no independent responsibility. Retain an existing nested public type when extracting it would
change its persisted/JVM identity; document that compatibility exception at the declaration.

Keep fully qualified type names, JSON discriminators and Kryo registration order stable during
file splits. Moving a type to another package is an explicit compatibility change, even if the
compiler can update all imports.

## Formatting and lint

`.scalafmt.conf` is the formatting authority: Scala 3, two-space continuation indentation,
100-column target, sorted imports and the configured syntax rewrites. `.editorconfig` aligns
editor whitespace with these rules.

```bash
sbt fmt                         # format sources and build definitions; inspect the resulting diff
sbt fmtCheck                    # verify sources and build definitions without rewriting them
sbt lint                        # formatting checks and compiler diagnostics for main/test sources
sbt qualityCheck                # lint followed by the full test suite
```

For an incremental refactor, format and check only the affected files and run their regression
suite. Example for the first adopted package:

```bash
sbt 'scalafmtOnly src/main/scala/core/cache/CalculationCache.scala src/main/scala/core/cache/CalculationCodec.scala src/main/scala/core/cache/CalculationCacheFile.scala'
sbt 'set Compile / scalafmt / unmanagedSources := ((baseDirectory.value / "src/main/scala/core/cache") ** "*.scala").get' \
    'Compile / scalafmtCheck' \
    'testOnly org.interscity.htc.core.cache.CalculationCacheSpec'
```

The repository-wide aliases are strict checks, not a declaration that all legacy sources have
already been migrated. Do not suppress existing checks to make a refactor pass. `lint` currently
uses Scalafmt and the configured Scala compiler diagnostics; it does **not** automatically enforce
the size, complexity or documentation targets below. Those require review until a Scala 3-aware
structural analyzer is adopted. Generated Avro/Protobuf output is outside manual refactoring.

## Size and complexity targets

Use these review targets for new and refactored code:

| Measure | Target | Review action when exceeded |
| --- | --- | --- |
| Primary class/trait/object | 300 nonblank, noncomment lines | Extract a coherent responsibility. |
| Method body | 40 nonblank, noncomment lines | Separate policy, calculation, transport or resource handling. |
| Cyclomatic complexity | 10 paths per method | Simplify branching or extract independently meaningful operations. |
| Control-flow nesting | 3 levels | Prefer a match, guard or named helper where it improves the contract. |

Sealed hierarchy files can exceed the class target in total; assess each primary type separately.
Generated code is excluded. Record a reason in the change description when a target cannot be
met without weakening correctness. A small actor method that coordinates an existing protocol
may be clearer than an artificial split. Do not add inheritance, mutable state, reply exchanges
or shared asynchronous work merely to reduce a metric.

## Scaladoc contracts

Use Scaladoc (`/** ... */`), Scala's equivalent of Javadoc, on primary types and declared methods
in refactored production code. Explain purpose, guarantees and constraints rather than repeating
the declaration. For implementation methods, explain the boundary or invariant that justifies
the extraction. An override may refer to the inherited contract when unchanged.

Document meaningful units, optionality, ordering, ownership, side effects, failure behavior and
persistence/replay requirements. Add `@param`, `@return` or `@throws` when they clarify a contract;
do not add boilerplate for self-explanatory accessors. Keep ordinary comments for non-obvious
reasons and invariants, following `AGENTS.md`.

## Refactoring and validation

Preserve observable behavior before changing algorithms. Keep type/package moves, formatting
and behavioral changes separately reviewable. Start with pure logic and bounded infrastructure;
changes to actor protocols must follow the synchronization and migration rules in `AGENTS.md`.

Run relevant existing tests after each coherent extraction. Add tests for uncovered contracts
that can break during the split, especially failure paths, resource cleanup and serialization.
Actor, Time Warp and traffic changes require their applicable protocol reviews and scenario
validation; pure cache extraction does not change those protocols.

The first adopted package is `core/cache`: `CalculationCache` selects and orchestrates entries,
`CalculationCodec` defines the payload contract, and package-private `CalculationCacheFile` owns
validated gzip I/O, replacement and temporary-file cleanup. Their existing public names and cache
format remain stable.

The next adopted files cover JSON actor streaming, tick indexing, cache configuration and identifier
helpers. `JsonStreamingUtil` owns mapper setup and the public factory; package-private
`ActorSourceIterator` owns incremental array validation. `TickIndexUtil` separates file/parser
lifetime from count reduction. Its public nested `LightTickIndex` keeps its existing JVM identity;
sentinel handling and inclusive chunk boundaries retain their established behavior.

SQLite source handling follows the same split: `SqliteActorSimulationUtil` retains the public
opening/decoding API, `SqliteActorRowDecoder` preserves complete-record Jackson binding, and
`SqliteActorSourceIterator` owns cursor advancement. Setup failures close the connection; successful
opening transfers its lifetime to the caller. The query remains ordered by `seq`, and payload decoding
remains lazy. Shared SQLite test fixtures keep the source schema consistent across decoder and
iterator regression tests.


State/property and identity helpers keep their existing public records and conversion signatures.
Their Scaladoc distinguishes schema metadata from runtime validation and records the legacy erased
generic lookup contract. `PartialClosure` delegates graph indexing and iterative cycle detection to
package-private `PartialCompositionGraph`; its two retention passes share a bounded queue traversal.
The nested public reasons and result retain their JVM identities, and upward retention still never
pulls in an owner's other parts.

`PartialFilter` delegates transformed-source scanning to `PartialSourceScanner` and startup report
aggregation/writing to `PartialFilterReport`. `PartialActorIndex` retains metadata without payloads.
The public nested settings/summary types remain stable. Modifications precede load filtering, added
actors remain selected, and a failed preparation leaves the existing published filter intact until
explicit clearing. Diagnostic caps and report keys retain their previous values.

`ActorLoadTransform` has its own file with its existing fully qualified name. `ActorLoadFilterConfig`
resolves complete scenario overrides and Config-based constructor loading without publishing partial
settings. `ActorLoadFilter` retains the nested settings type and validates identity after every
transformation, then checks final dependencies. Excluded records bypass transformations; duplicate
transform entries and their order remain significant.
