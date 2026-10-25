---
name: htc-time-warp-review
description: Review or implement HTC Time Warp changes involving rollback, replay, anti-messages, GVT, optimistic Time Managers, or parity with conservative simulation.
---

# HTC Time Warp review

Read root `AGENTS.md`, the relevant current code, and the matching sections of `docs/TIME_WARP_DESIGN.md` and `docs/KNOWN_GAPS.md`. The design document contains dated status claims; verify them against the checkout before using them as current facts.

Trace the changed behavior across `SimulationBaseActor`, rollback history, message identity and anti-messages, optimistic Local and Global Time Managers, and model actors that consume the event. Check that all behavior-affecting state is restored or deterministically rebuilt, replay emits the same decisions, canceled output is not reported as committed, and GVT/termination cannot discard live work.

Start with focused tests for the changed invariant. For behavior that depends on multiple actors, compare a small fixed-seed scenario in conservative and optimistic modes. Compare reports and terminal outcomes, not just whether both runs exit; document any intentional difference and its cause. If the change is a design proposal, provide affected protocols, validation evidence needed, and unresolved costs before implementation.
