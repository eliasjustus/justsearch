---
title: "ship-then-learn feedback capture: click/open signals + consolidating the already-persisted agent-citation tuples — the unlock for learned ranking and the eventual replacement for constructed benchmarks"
type: tempdocs
status: "RE-SCOPED + IMPLEMENTED (2026-07-22, owner-approved). The stale 'build a new store' premise was corrected to 'harden + complete tempdoc 580 §17's live ResultDisposition consolidation' (§D records the premise conflict). Landed: jseval one-interface reader; feedback store enrolled in StoreCatalog (AUTHORED, sealed) with the F-021 LabelProjection join proven to survive sealing (regression test); a distinct USER_CITATION_CLICK contributor + FE emission; a default-on local capture flag + visible privacy note; egress guardrails on the capture path. All builds + suites green. Live from-my-dist dev-stack verify BLOCKED by shared-stack contention (sibling 777's 2h lease on main's code); runnable procedure recorded in §D.2. See §D / §D.2."
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

## §D.2 Re-scope executed (2026-07-22, owner-approved)

The orchestrator approved the §D recommendation verbatim. Implemented in the
recommended order, **hardening 580 §17 rather than building anew** — no second
disposition store. What landed:

**1. jseval one-interface reader (non-forking).**
- `scripts/jseval/jseval/feedback_reader.py` — reads the existing
  `feedback/result-dispositions.ndjson` + `feature-snapshots.ndjson`;
  `read_feedback_signals` (unified stream, user-event vs agent-citation +
  polarity), `read_labeled_examples` (the §17.4 disposition⋈snapshot join,
  read-only), `summarize`. Detects sealed (`JSEv1:`) lines and skips-with-warn
  (Python holds no data key). Tests: `tests/test_feedback_reader.py` — 6 pass.

**2. Store hardening — `feedback` enrolled as AUTHORED + sealed, join proven.**
- `StoreCatalog.FEEDBACK("feedback", AUTHORED, APPEND_ONLY_LINES)`
  (`StoreCatalog.java:22`); register row in `governance/store-recoverability.v1.json`;
  `check-store-recoverability` green (6 stores).
- `NdjsonAppendStore` gained an optional `StoreCipher` (default `disabled()` =
  passthrough, so default/eval behaviour is byte-identical); seals each line on
  append, opens on read, empty-until-unlock when locked
  (`NdjsonAppendStore.java`).
- The ONE feedback key threaded to every writer/reader:
  `AgentDispositionWiring.register(..., cipher, settings)`,
  `KnowledgeSearchController.setFeedbackCipher(...)` (both lazy stores),
  `FeedbackLabels.rebuild(dataDir, cipher)`, `LambdaMartTraining.loadOrTrain(...,
  cipher)` via `OrchestrationPhase.Input.feedbackCipher`, all sourced from
  `HeadAssembly.storeCipher(StoreCatalog.FEEDBACK.recoverability())`
  (`HeadAssembly.java` feedback block). Feedback enrolled in the encrypted
  backup/restore path (`StoreDescriptor` + `readFeedbackFile`/
  `restoreFeedbackEntries`, skip-existing).
- **F-021 join survives sealing (the required regression, not just the gate):**
  `FeedbackLabelsSealedJoinTest` seals dispositions+snapshots with an enabled
  key, asserts the on-disk lines carry `JSEv1:`, then rebuilds and asserts the
  explicit-positive + derived-SHOWN-negative contrast triples still project
  (2 triples, 1 contrast group). Green.

**3. Distinct chat-citation USER contributor + FE emission.**
- `ResultDisposition.Contributor.USER_CITATION_CLICK` (distinct from the LLM's
  `AGENT_CITATION` harvest). `KnowledgeSearchController.handleDisposition` takes
  an optional wire `contributor`: `"chat-citation"` → `USER_CITATION_CLICK`,
  else the unchanged `SEARCH_INTERACTION` (`contributorFor`).
- FE: `SourcesPane.onSelect` → `recordCitationClick(parentDocId)` posts to
  `/api/knowledge/disposition` with `contributor:"chat-citation"`, join key =
  active conversation id (best available; an unjoinable click is still a captured
  raw signal — LabelProjection's honest limit). Mirrors
  `searchState.ts recordOpenDisposition`.

**4. Default-on local flag + visible privacy note + egress test.**
- `FeedbackCaptureSettings` (default-on, persisted `<dataDir>/feedback-capture.json`,
  a sibling preference — not inside the sealed data dir) + `PRIVACY_NOTE`.
  Gates the behavioural disposition writes (endpoint + the agent `done`
  contributor); feature-snapshots (engine score-vectors, not user behaviour)
  stay flowing so the join survives a re-enable.
- `FeedbackCaptureController` — `GET/POST /api/feedback/capture` → `{enabled,
  local, privacyNote}` (wired in `ResourceApiModule`; cohort in `RouteCohorts`).
- FE: `SettingsSurface` renders the note + a keyboard-operable toggle in
  Security & Privacy (`renderFeedbackCapture`), wired to the endpoint.
- **Zero egress enforced:** app-services `FeedbackEgressGuardrailsTest` (the
  capture/persistence package makes no `java.net.http`/`Socket`/`URLConnection`
  access) + `UiApiGuardrailsTest.feedbackSurfaceMustNotMakeNetworkEgress`
  (the `Feedback*` surface). Both green.

**Verification.** `./gradlew.bat build -x test` green; full `:modules:app-services:test`
+ `:modules:ui:test` green (incl. the sealed-join regression, both egress
guardrails, the reprojection/idempotence tests unchanged); jseval reader 6/6;
ui-web `npm run typecheck` clean + `test:unit:run` 3783 pass; ui-web gate set
run (presentation-purity, color-tokens, controls-a11y, a11y-closure,
layout-purity, contrast, observed-state-collapse, + kernel ambient-purity/
style-literal/atom-fork/modality/transient/modal — all pass). The one RED,
`check-accent-as-text`, is **pre-existing** in `ActionLedgerView.ts` (matches
the expected-state note; untouched by this work).

**Live verify — BLOCKED by shared-stack contention (honest state).** The one
shared dev stack was held by a **2h active lease running main's code**
(`gitHead cd951e9b`, not this worktree's dist) — the sibling 777 GPU campaign.
Per *never take over*, I did not restart it with `distFrom` my worktree, and a
bounded 45-min wait cannot outlast a 2h lease. The `/api/knowledge/disposition`
POST is also not MCP-allowlisted. So the real click→row→reader chain from **my**
dist was not run live. It is covered statically by: the sealed-join regression,
the reader unit tests over real on-disk ndjson layout, the egress guardrails,
and the green full suites. **Runnable smoke for the owner when the stack frees**
(dev-stack-driven item, per slice-execution.md): start the stack from this
worktree's dist (`justsearch_dev_start distFrom=<worktree>`), issue a search +
click a result (writes a `SEARCH_INTERACTION` disposition) and a chat citation
(writes a `USER_CITATION_CLICK`), then
`python -c "from jseval.feedback_reader import summarize; print(summarize(<dataDir>))"`
— expect the disposition counts by contributor + joined `labeledExamples`; flip
the Settings toggle off and confirm a subsequent click writes no new row.
