---
title: "ship-then-learn feedback capture: click/open signals + consolidating the already-persisted agent-citation tuples — the unlock for learned ranking and the eventual replacement for constructed benchmarks"
type: tempdocs
status: "HELD (2026-07-22) — implementation blocked on a stale premise. §A's 'user click/open/dwell capture is ABSENT' + the item-2 'build one queryable store unifying user events + agent-citation tuples' are BOTH already shipped and live-wired as tempdoc 580 §17's ResultDisposition consolidation stream (SEARCH_INTERACTION + AGENT_CITATION contributors → LabelProjection, the structural answer to F-021). Building a second store forks 580 §17.3's explicit 'not in two stores' doctrine. Needs an owner re-scope onto extend-580-§17, not new-store. See §D."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: product / learned-ranking / telemetry
related:
  - 580 (F-021 refinement — the harvest-not-build citation signal; why GPL-synthetic labels failed)
---

> Charter. Two halves with different clocks: capture (build now, before
> launch, so day-one usage is not lost) and consumption (gated on real
> users existing).

# 778 — ship-then-learn feedback capture

## §A. The evidence base (F-021 lineage, do not re-derive)

- GPL-synthetic-trained LambdaMART is measured non-viable (F-021); real
  labels are necessary; the V1 2-feature schema is structurally capped below
  fusion regardless of labels (580 §13.7) — any learned layer needs BOTH
  real labels AND richer features.
- The agentic path ALREADY persists a graded real-query signal (retrieved ⊃
  grounding ⊃ cited, with parentDocId/chunkIndex + similarity —
  AgentCitationResolver/AgentInteractionMapper; 580 §16): real-query,
  harvest-not-build, but reorder-only and LLM-judged.
- User click/open/dwell capture is ABSENT (confirmed 580 §12.1) — the
  highest-value signal has no pipeline.

## §B. Work items

1. **Capture**: local-only click/open/dwell events on search results and
   chat citations — loopback-privacy by construction (nothing leaves the
   machine; the product's own privacy story is the constraint AND the
   feature), schema versioned, storage recoverability per StoreCatalog
   discipline.
2. **Consolidation**: one queryable store unifying user events + the
   persisted agent-citation tuples; a jseval-side reader so future ranking
   work consumes one interface.
3. **Deliberate non-goals now**: no learned model training, no
   fusion-weight fitting, no telemetry upload of any kind — consumption
   designs wait for real data volume (the F-021 lesson: labels first,
   models second).
4. **The benchmark bridge** (recorded, not built): once real query
   distributions exist, they seed question sets that replace constructed
   schemas — the endgame for the 766-line of work.

## §C. Acceptance

Capture live behind a default-on local flag with a visible privacy note;
events verified end-to-end on the dev stack; store passes
check-store-recoverability; zero network egress verified (loopback-only
invariant test extended); registers/docs updated.

## §D. Implementation attempt + hard-stop (2026-07-22)

**Status: implementation reverted; HELD for owner re-scope. No code committed
beyond this finding.** A worker began the charter and, per *explore-before-
implementing / projection-not-fork*, discovered the charter's premise is stale:
the consolidation it asks us to *build* already exists, live-wired, from
tempdoc 580 §17.

### The premise conflict (primary-source evidence)

§A states "User click/open/dwell capture is ABSENT (confirmed 580 §12.1)" and
item 2 asks to build "one queryable store unifying user events + the persisted
agent-citation tuples." Both are already shipped:

- **The ONE canonical consolidation stream exists**:
  `ResultDisposition` (`modules/app-services/src/main/java/io/justsearch/app/services/feedback/ResultDisposition.java`)
  — its own Javadoc: *"ONE canonical stream fed by multiple Contributors (the
  §17.3 anti-fork design: the agentic-citation harvest and the search-UI
  interaction both land here, **not in two stores**)."* Kinds
  `SHOWN<OPENED<DWELLED<CITED<ACTED_ON` + `REFINED_WITHOUT_OPENING`;
  contributors `SEARCH_INTERACTION`, `AGENT_CITATION`, `EXPLICIT_RATING`.
- **User search click/open/dwell IS captured**: FE posts to
  `POST /api/knowledge/disposition` (`KnowledgeRoutes.java:37` →
  `KnowledgeSearchController.handleDisposition`,
  `KnowledgeSearchController.java:131-159`), driven from
  `modules/ui-web/src/shell-v0/state/searchState.ts:163-178` (`recordOpenDisposition`
  → `opened`) + the dwell path (`searchState.ts:203-219` → `dwelled`, 3s
  threshold). Persisted to `<dataDir>/feedback/result-dispositions.ndjson`.
- **Agent-citation tuples ARE harvested into the same stream**:
  `AgentDispositionWiring.register(...)` is live-wired at
  `HeadAssembly.java:587`, projecting each `done` event's grounding sources +
  citations to `CITED/SHOWN` dispositions keyed by `sessionId`.
- **The F-021 label unlock is already built**: `LabelProjection`
  (`.../feedback/LabelProjection.java`) joins dispositions → `FeatureSnapshot`
  (by `interactionId`) → real-label training triples — its Javadoc calls itself
  *"the structural answer to F-021."*

The charter (authored from 580 **§16**'s knowledge) did not account for **§17**,
which shipped the consolidation §16 only pointed at. This is the
*tempdocs-are-dated-history / verify-against-main* trap: the charter's core
deliverable duplicates a live anti-fork subsystem.

### What a from-scratch build would have been

A worker-built parallel `feedback-events` StoreCatalog store +
`FeedbackEventStore` + `FeedbackSignalReader` + `FeedbackController`
(`/api/feedback/*`) — implemented and unit-green, then **reverted** because it is
a textbook fork of the ResultDisposition stream (two disposition stores; exactly
what §17.3 forbids). Not committed.

### The genuine gaps 580 §17 does NOT cover (the real 778 surface)

These are real and worth doing — but as an **extension of 580 §17**, not a new
store:

1. **Recoverability discipline**: `feedback/result-dispositions.ndjson` +
   feature snapshots are a raw `NdjsonAppendStore` (0 `StoreCipher` refs), **not
   in `StoreCatalog`** (verified: no `feedback` entry on HEAD). So it is
   plaintext, unclassified, and outside encrypted backup/export. Bringing it
   under `StoreCatalog` (classify + seal) is the charter's real
   `check-store-recoverability` deliverable — but sealing changes the on-disk
   format that `LabelProjection`/`FeatureSnapshots` read, so it touches the
   F-021 label-join and needs care.
2. **Chat-citation USER-click capture**: `AGENT_CITATION` is the LLM's harvest,
   not a user clicking a citation in chat. A user-click contributor on the chat
   citation surface is genuinely missing.
3. **Default-on flag + visible privacy note**: absent — no capture toggle, no
   loopback-privacy note in settings/status.
4. **jseval-side reader**: absent — no `scripts/jseval` reader over
   `feedback/result-dispositions.ndjson`. This is the lowest-risk, non-forking,
   owner-decision-free first increment (reads the existing consolidated stream).

### Recommended re-scope (for the orchestrator/owner)

Redirect 778 from "build capture + consolidation" to **"harden + complete
580 §17's ResultDisposition subsystem"**:
(a) jseval reader over the existing stream (do first — additive, non-forking);
(b) classify the `feedback` store in `StoreCatalog` and seal it via `StoreCipher`,
updating `LabelProjection`/`FeatureSnapshots` readers (the recoverability item —
verify the F-021 label join survives);
(c) add a chat-citation user-click contributor + FE wiring;
(d) default-on flag + privacy note surface;
(e) extend the loopback invariant test to the disposition path.
Non-goals (§B.3) still hold. Live dev-stack verification (shared, sibling 777
holds it) was NOT run — the work stopped before there was correct code to verify.
