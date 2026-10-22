---
name: htc-protocol-review
description: Review HTC actor message and tick lifecycles for stalls, lost replies, duplicate completion, or wait cycles. Use for protocol changes and synchronization bugs; not for general style review.
---

# HTC protocol review

Read the root `AGENTS.md` synchronization discipline and the relevant entries in `docs/KNOWN_GAPS.md`. Follow each affected event from its sender through every receiving branch and back to the sender, using the current code as the source of truth.

For each exchange, record:

1. Whether the sender needs an answer to continue or can finish immediately.
2. What completes the spontaneous event on every branch, including errors and no-ops.
3. If an answer is required, which branch sends it, how the sender recognizes it, and whether stale or superseded replies can arrive.
4. Whether the receiver's reply depends on work that must first return through the waiting sender.
5. Whether scheduling, `FinishEvent`, and Time Manager `runningEvents` bookkeeping still agree.

For optimistic mode, also check whether rollback and replay can repeat or cancel the exchange correctly. Do not propose a watchdog as the protocol fix. Report the exact broken path with file and line, then verify a fix with the narrowest meaningful spec and a bounded simulator run when ordering across actors matters.
