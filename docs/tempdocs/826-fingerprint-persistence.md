---
title: "826 — Embedding fingerprint persistence (F1-F3) — blocked on the 819 owner decision"
type: tempdocs
status: "CHARTER (2026-08-14) — BLOCKED on the owner's 819-fingerprint-boot-race call (fix-and-publish by this lane vs its owning agent). Design already exists in 821 §O.1; this tempdoc is the execution charter so the work has a home the moment the decision lands."
created: 2026-08-14
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration)
category: structural / index-lifecycle (821 class C1/C5)
related:
  - 821 §O.1 (investigation: fingerprint chain, 819 partial verdict, F1-F3 design)
  - worktree 819-fingerprint-boot-race (another agent's branch — merge-review brief ready in 821 §O.1)
---

# 826 — Embedding fingerprint persistence (F1–F3)

## Why this exists

821 §O.1 established that the embedding fingerprint **never persists** — the §N
needs-live worst-case finding — and produced a three-part design (F1–F3) plus a
partial verdict on the 819 branch that overlaps it. The owner explicitly held the
819 decision; every F-item is gated behind it because the 819 branch touches the
same boot/fingerprint chain and merging out of order would force a redesign or a
conflict-heavy rebase of whichever lands second.

## Decision gate (owner)

**819-fingerprint-boot-race**: fix-and-publish by this lane vs. its owning agent.
The merge-review brief (what to check, what the partial verdict already cleared)
is in 821 §O.1. Until decided, do not start F1–F3 implementation; do not touch the
819 worktree.

## Chartered work (once unblocked — design in 821 §O.1, not duplicated here)

1. **F1** — persist the fingerprint at the authoritative write point; rebuild
   detection reads it instead of re-deriving.
2. **F2** — boot-order guarantee so the fingerprint read cannot race worker
   readiness (the 819 overlap).
3. **F3** — staleness/mismatch surfacing: reason-coded, visible on
   `/api/debug/state`, consumed by the readiness projection (ties into
   `INDEX_EMBEDDING_REBUILDING` from the 821 readiness lane).

## Acceptance

- Fingerprint survives restart; a model-stack change triggers exactly one rebuild
  with an honest reason code; no rebuild storm on unchanged stacks (the original
  defect class).
- Live-verified across a real restart cycle on the dev stack.
- 821 §O.1's falsification probes re-run green on the final composition
  (819 + F1–F3), whatever the merge order the owner picks.
