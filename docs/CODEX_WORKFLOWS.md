# Codex workflows for HTC

The root [AGENTS.md](../AGENTS.md) is the shared project contract. Project agents live in [`.codex/agents/`](../.codex/agents/) and repository skills in [`.agents/skills/`](../.agents/skills/). An agent is a focused collaborator for a bounded assignment; a skill is a repeatable workflow that the current agent can use. OpenAI's [subagent](https://learn.chatgpt.com/docs/agent-configuration/subagents) and [skill](https://learn.chatgpt.com/docs/build-skills) guides describe these repository locations.

| Work | Agent | Skill when relevant |
|---|---|---|
| Design a cross-cutting actor or Time Manager change | `htc-architect` | `htc-time-warp-review` for optimistic mode |
| Implement an actor, handler, state, or event | `htc-actor-dev` | `htc-protocol-review` for message contracts |
| Diagnose a stall or livelock | `htc-sync-debug` | `htc-protocol-review` |
| Review correctness or performance of a diff | `htc-perf-review` | `htc-protocol-review` or `htc-time-warp-review` |
| Write focused ScalaTest coverage | `htc-test-writer` | Relevant review skill for the invariant |
| Validate generated mobility scenarios | `htc-scenario-qa` | `htc-scenario-validation` |
| Update technical docs after a behavior change | `htc-docs-writer` | Relevant review skill if the contract changed |

Use one role for a small task. For work spanning roles, give each collaborator a bounded responsibility and clear file ownership. Read current code and the relevant entries in `docs/KNOWN_GAPS.md` before relying on a dated design note. Preserve unrelated working-tree changes.

## Typical handoff

1. State the concrete behavior or failure and the affected simulation mode.
2. Have the relevant role trace the current path and identify the invariant or design decision.
3. Implement the smallest change that satisfies that decision.
4. Use focused tests for the changed logic and a bounded simulator scenario when message timing or end-to-end outcomes matter.
5. Update the relevant model or architecture document with verified behavior and remaining gaps.

Skills can be invoked explicitly by name, for example `$htc-protocol-review`, or selected when their descriptions match the task. Agent roles are available to tasks that support subagents; their presence does not require delegation for ordinary work.
