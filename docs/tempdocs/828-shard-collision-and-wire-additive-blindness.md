---
title: "828 — Infra pair: session-shard identity collision + wire-gate additive-field blindness"
type: tempdocs
status: "CHARTER (2026-08-14) — not started. Two small infrastructure defects with outsized friction, both measured during the 821 publication campaign."
created: 2026-08-14
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration)
category: infra / agent-tooling (821 class C8-adjacent)
related:
  - 821 §P (publication log — every catch-up conflict this session was a shard add/add)
  - scripts/agent-analytics/note-observation.mjs (Seam C, tempdoc 618)
---

# 828 — Infra pair

## A. Session-shard identity collision

**Defect:** subagent workers inherit the orchestrator's session id, so every worker
that logs an observation writes to the *same* shard file
(`docs/observations.d/<orchestrator-session>.md`). During the 821 publication
campaign this produced an add/add merge conflict on **every single catch-up merge**
(~10 manual union-resolutions in one day; 821 §P records them), plus one
modify/delete against the fold. Workers began *declining* to log observations to
avoid dirtying their PRs — the mechanism designed to prevent knowledge loss started
causing it.

**Chartered fix (design open, options in preference order):**
1. `note-observation.mjs` derives a per-writer suffix (e.g. session id + agent id /
   worktree name) so concurrent writers never share a file; the fold already
   globs the directory and needs no change.
2. Or: shard append goes through a merge-driver (`.gitattributes` union driver for
   `docs/observations.d/*.md`) so add/add auto-resolves — smaller change, but keeps
   one contended file and union drivers have caveats on deletions (the fold).
Also update the worker-brief guidance (CLAUDE.md delegation paragraph or the
subagent-guide hook) so workers log freely again once the collision is gone.

**Acceptance:** two concurrent worktree branches each logging observations merge to
main with zero manual conflict resolution; fold still consumes all shards.

## B. Wire-gate additive-field blindness

**Defect:** `--gate wire` verified the 821 waves' proto changes as
schema-compatible, but nothing gates on an additive field being *consumed*: a field
can be added, emitted, documented, and never read (821 shipped at least two such —
composite staleness, completeness projections — knowingly, as FE handoff; the gap is
that nothing *tracks* it). This is `wire-emitter-elision`'s mirror image:
emitter-without-consumer instead of consumer-without-emitter.

**Chartered fix:** extend the wire gate (or add a companion check) with a
consumer-manifest: each additive wire field either names a consumer
(`file:symbol`) or carries an explicit `handoff:<tempdoc>` marker with the owning
tempdoc. The check fails on unmarked orphan fields; markers age out when the named
tempdoc closes (re-fail then, so handoffs cannot rot silently — the 742
residue-outlives-its-reason class).

**Acceptance:** the gate fails on a synthetic orphan field; the two known handoff
fields carry `handoff:827` markers and pass; closing 827 without consuming them
turns the gate red.
