---
title: "Headless-eval product contract: the product's interactive/energy heuristics and headless measurement automation currently fight each other — define a sanctioned eval-mode contract instead of per-incident workarounds"
type: tempdocs
status: "open — STUB, no design or implementation. Trigger: the next eval workflow that needs to work around an interactive-product heuristic (three distinct workarounds already exist from one session, 2026-07-03). Spun out of tempdoc 624's certified-run + scan-battlefield session."
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 certified-run session), filed by agent — STUB
category: agent-eval / product-contract / vdu / settings / dev-experience
related:
  - 624-agentic-retrieval-eval-rebuild        # origin — every incident below happened during its certified-run session
  - 672-vdu-offline-coordinator-bootstrap-wiring  # owns the VDU wiring; the pacing-policy interactions below sit ON TOP of its fix, not inside it
  - 674-cross-family-grader-local-model-infrastructure  # its local-serial swap is one of the consumers blocked by the current boundary
principle: "an automated measurement process is not a user — product heuristics that infer user presence, energy state, or settings trust from ambient signals will misread automation, and automation will misread them back. The boundary needs an explicit contract, not per-incident evasion."
---

> Noncanonical working tempdoc. STUB: goals and context only — no design decisions, no implementation
> specifics.

# 676 — Headless-eval product contract

## Goal

Define one sanctioned contract for how headless measurement automation (jseval runs, calibration,
corpus certification, grader panels, future extraction evals) interacts with product behaviors that
were designed around a human user — so that eval workflows stop discovering these interactions as
live incidents and stop resolving them with one-off evasions that bypass deliberate product design.

## Context — three incidents from one session (2026-07-03, all documented in 624's twenty-second pass)

1. **Idle/energy pacing vs. eval probing.** VDU offline processing interrupts whenever "user activity"
   is fresher than its idle threshold — and eval search probes signal user activity. The eval's own
   monitoring starved extraction for hours (every triggered batch aborted at its first checkpoint,
   0 docs processed) while progress *appeared* to advance because the queue metric tracks marking, not
   extraction. Resolution was behavioral (total search-silence so the idle sampler could run) — fragile
   and undiscoverable for the next agent. Related sub-issues with the same owner-boundary: batch
   processing does not chain on a loaded queue without manual re-triggers, and the queue metric's
   marking/extraction ambiguity misled diagnosis twice.
2. **Eval-mode read-only settings vs. tools that must write settings.** The eval backend's settings
   store is read-only *by design* (cohort stability); 674's local-serial grader swap needs settings
   writes. The env-var override route failed for the documented Windows/Gradle reason, and the working
   resolution (host the panel on the dev stack instead) is an undocumented convention, not a contract.
3. **Interactive-product state accretion vs. measurement isolation.** Watched roots and index content
   accrete across backend restarts unless explicitly cleaned; the runners assert scope, but other
   measuring entry points (corpus fidelity, ad-hoc probes) do not — one unclean restart silently
   measured a mixed two-corpus index. The product behaves correctly for a user; a measurement needs a
   stronger precondition than the product enforces.

## What the contract should cover (goals, not designs)

- A way for sanctioned automation to run offline processing **now**, at full duty cycle, without
  impersonating idleness or being throttled by user-presence/energy inference — while leaving those
  heuristics fully intact for real users.
- A defined story for which backend hosts which eval capability (what runs against the eval backend,
  what requires the dev stack, and why), so capability/host mismatches fail with a pointer instead of a
  bare 409.
- Measurement-grade preconditions (index content matches the declared corpus; watched-root scope) as an
  assertable contract available to *every* measuring entry point, not just the certified-run runners.
- Observability that distinguishes queued/processing/completed for background work, so automation can
  reason about actual progress.

## Explicit non-goals

Not a weakening of the product's user-facing heuristics or of eval-mode settings immutability — both
are correct for their audiences; the gap is the absence of a defined third audience (sanctioned
automation). Not a general scheduling or configuration redesign.
