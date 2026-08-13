---
title: "827 — FE handoff bundle: backend-shipped surfaces awaiting frontend consumers"
type: tempdocs
status: "CHARTER (2026-08-14) — handoff inventory for the frontend-rework agent; the backend side of every item is merged and verified. This lane's owner (821 session) does NOT implement these — frontend is explicitly out of its scope."
created: 2026-08-14
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration)
category: frontend / handoff (821 residuals)
related:
  - 821 §M/§P (the lanes that shipped the backend halves)
  - worktree agent-a7925a2dea08c067b (unmerged C1-FE branch, no PR — see item 4)
---

# 827 — FE handoff bundle

Backend surfaces shipped by the 821 publication campaign (2026-08-13, PRs #418–#442)
that have **no frontend consumer yet**. Each item names the wire surface and the
verified backend behavior; the FE agent owns presentation decisions.

1. **Composite staleness** (PR #433): `status.readiness.composites[]` now carries
   `stale` + `maxStalenessMs`. `aiStateStore.ts` reads only `state`/`reasonCodes`
   from composites — the staleness fields go unread. Decide surfacing (badge, tooltip,
   or fold into the existing staleness banner logic).
2. **Enrichment completeness** (PR #432): `/api/status` enrichment block gained
   `completeness[]` (ARTIFACT/STATUS tier semantics, scoped denominator),
   `failedNerCount`, `chunkMinChars`, `vectorReadyPercent` — documented in
   `docs/reference/api-contract-map.md` §"Enrichment completeness (post 821 §3-C3)".
   Consumer rule already documented: non-positive value means "cannot evaluate",
   never "guess". No FE consumer reads any of it yet.
3. **Enrichment-loss condition** (PR #437): the per-stage `enrichment.incomplete`
   condition now fires on real loss (PENDING-gated predicate). Verify the conditions
   feed renders it and that its clear-on-stale-view behavior reads correctly in the UI.
4. **The unmerged C1-FE branch** (`agent-a7925a2dea08c067b`, no PR): carries the
   wave-1 FE truthfulness work (`folderStatus.ts`, `LibrarySurface.ts`,
   `ActionLedgerView` tests, `verdict.ts` et al.). It predates ~20 merges of main
   movement. FE agent decision: rebase-or-reimplement against the reworked frontend;
   its `hasFrame` multiplexed-path test gap (nifty-baking-sunset plan, Fix C) is
   still open either way.
5. **Boot-retry narration** (PR #439): non-final boot attempts no longer flap
   DEGRADED/OFFLINE on `/api/health`; if the FE surfaces RECENT EVENTS anywhere,
   confirm the suppressed-flap semantics read correctly (a boot that succeeds after
   retry should not show failure occurrences).
6. **Known main-red ui-web gates** (conditions store, 2026-08-13 entries):
   `check-controls-a11y` (`UnifiedChatView.ts:2089` title-on-disabled),
   `gen-token-names --check` (stale generated file), `strip-token-fallbacks --check`
   (6 fallbacks in `ActionLedgerView.ts` + `RecentsMenu.ts`) are main-red and NOT in
   `expected-state.v1.json` — every ui-web branch re-proves them pre-existing. Fix
   or register them early in the rework.

## Acceptance

Each item either gains a consumer/fix in the FE rework or is explicitly declined by
the FE agent with the reason recorded here; no item silently dropped.
