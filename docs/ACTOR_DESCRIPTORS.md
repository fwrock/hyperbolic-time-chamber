# Actor descriptors

One JSON Schema per actor class in `schemas/actors/<Class>.schema.json` (index: `schemas/actors/index.json`), with `x-htc-*`
annotations. They describe what an actor looks like so tools can generate forms and validate input without hard-coding classes.
Today: Node, Link, Car, Bicycle, Motorcycle, Person, TrafficSignal. Not yet: Bus, BusStop, BusStation, Subway, SubwayStation,
RailLink, RoutingService (add a file and an index entry; classes without a descriptor are simply not validated or form-edited).

## Annotations

| key | where | meaning |
|---|---|---|
| `x-htc-class`, `x-htc-version` | schema | full class name, descriptor version |
| `x-htc-filterable` | schema | the partial-simulation filter may remove actors of this class (replaces the hand-kept `htc.partial.filterable-classes` list as the source of truth) |
| `x-htc-dependencies` | schema | `[{name, class, required, relation}]`; `name: "*"` = keyed by the target id; `relation` is `reference` or `part_of` (composition) |
| `x-htc-label`, `x-htc-unit`, `x-htc-group`, `x-htc-help` | field | form label, unit, group, help text |
| `x-htc-ref` | field | the value is the id of an actor of that class |
| `x-htc-widget` | field | `json` (edit as JSON), `ids` (list of ids), `multiselect` |
| `x-htc-editable: false` | field | runtime state (registrations, queues): shown read-only, editing it has no effect |

## Where they are used

- `simedape/descriptors.py`: loads them, validates an actor (shape, ranges, enums, required dependencies, relation matches the descriptor),
  builds the flat field catalog (`field_catalog`). Test: `tests/test_descriptors.py` validates every actor of
  `simulations/input/native-two-corridors` against them (guards against drift between the Scala state classes and the descriptors).
- `simedape/modifications.py`: edit operations are checked against the descriptors (unknown field, wrong type, out-of-range value,
  `scale`/`add` on a non-numeric field, edits of runtime state); added actors are validated whole.
- API: `GET /api/schemas` (descriptors + catalog), `GET /api/modifications/actor?ref&id` (one actor of a scenario).
- UI: `components/schemas/actor-form.tsx` renders a form from a descriptor (fields grouped, units, selects for enums, JSON editors for
  complex fields, dependencies, read-only runtime state); the modifications editor uses it to edit an actor and turn the change into
  an `edit` operation (only what changed) or clone it into an `add`, and to suggest field paths.

## Keeping them honest

Written by hand from the Scala `*State` classes (units from `docs/SCENARIO_MODELING.md`). The drift test catches a field that real
scenarios use and the descriptor rejects, not a descriptor field the code no longer has. Not done: a generated skeleton from the Scala
case classes, serving the descriptors from the engine REST API, descriptor versioning checks in the editor, descriptors for plug-in models.
