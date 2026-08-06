---
title: "Indexing progress & queue UX — end-to-end design (hv campaign thread T-A)"
status: "open — design settled + researched + derisked 2026-08-06; implementation licensed (owner, same day) and in progress; Lane-1 fixes (809 findings 1, 3) still unstarted — dependencies stated in §7"
created: 2026-08-06
updated: 2026-08-06
related: [809, 810, 798, 807, 801, 727, 717, 600, 599, 598, 593, 565]
---

# 813 — Indexing progress & queue UX design

Thread **T-A** of the 810 human-validation triage. Scope per the charter: 809 finding **2**
(the queue/tasks UI end-to-end), finding **1**'s user-facing surface (what completion may
claim), finding **11**'s naming problem ("Run Offline Processing" and the
reindex-vs-enrichment distinction), and finding **3**'s constraint (what cancel the UI can
honestly promise). This is a design document: it decides shape and vocabulary, not
implementation detail. The correctness-lane fixes for findings 1 and 3 are **unstarted** as
of this writing; §7 states the dependencies explicitly.

All code citations are from origin/main @ `3d3ee489` (checkout
`.claude/worktrees/ta-indexing-ux`), verified 2026-08-06 by three independent read-only
audits plus first-hand reads.

---

## 1. Ground truth, including two corrections to the charter's constraints

The 810 charter carries four constraints verbatim from finding 2. Investigation confirms
two and **corrects** two. The corrections strengthen the design rather than weaken it.

### 1a. CORRECTION — the photographed "two queues" screen was one queue, served stale

The "queue 0 / Up to date" Health card does **not** read the index-writer queue. It reads
`worker.core.pendingJobs` — the same SQLite job queue the Tasks panel shows
(`HealthSurface.ts:867`; "Up to date" additionally requires `indexHealthy`, `:858`).
`writerQueueDepth` is a backpressure in-flight counter (~0 whenever sampled outside an
active Lucene write call; `IndexingCoordinator.java:430` + the `finally` decrements) and
`refreshLagMs` is NRT searcher staleness (`CommitOps.java:205-212`). **Neither has any
frontend consumer.** They are instrumentation, not a user-facing backlog, and this design
deliberately keeps them off every user surface.

The "0 running · 1218 queued" vs "queue 0 / up to date" contradiction is therefore a
**same-source contradiction across two transports**: the Tasks panel reads an SSE snapshot
mirrored head-side that updates only when a frame arrives
(`RemoteIndexingJobsBridge.java:90-91`, `:161-176`), while the Health card reads a fresh
10-second poll (`statusPoll.ts:28`). This is the documented 798 B4 / 801 §D0/D6 staleness
class — "derive from the subject you are describing." (Static analysis; the live repro of
which transport was stale is an open verification item, §9.)

### 1b. The two backlogs that ARE genuinely distinct

The charter's principle — picking one number throws away real information — survives with
corrected referents. The two user-meaningful backlogs are:

1. **The job queue** — files awaiting extract + Lucene write (`jobs.db`,
   PENDING+PROCESSING). Drains fast (~1 s/file). Per-root sliceable today
   (`countJobsByPathPrefix`, `SqliteJobQueue.java:1439-1448`, already on the wire as
   `inFlightCount` per root).
2. **The enrichment coverage deficit** — embedding / SPLADE / NER / chunk vectors for
   already-indexed documents. **Has no job rows at all**; driven by the idle backfill
   (`backfillMode`). Slow (a 150-doc batch ≈ 63 s of GPU). Visible today only as
   index-wide coverage counters, with the chunk-level truth living solely in `/api/status`
   (`/api/knowledge/status` promotes only the `chunkEmbeddingReady` boolean —
   `KnowledgeStatusView.java:112` — the finding-9 projection trap).

### 1c. RESOLVED — per-folder enrichment progress is derivable today (the decisive unknown)

The charter said "may not be derivable — check the source." Verdict:
**derivable with a cheap query; no new recording required.** The backfill batch indeed
carries zero root provenance (selection is index-wide status scans,
`CombinedEnrichmentBackfillOps.java:188-232`), but every parent *and* chunk document
stores its normalized absolute path as an indexed keyword field
(`IndexingDocumentOps.java:139-140`, `ChunkDocumentWriter.java:127`), prefix-on-path is an
existing production query shape (`QueryFilterBuilder.java:182-183`, `FolderBrowseEngine`),
and the existing coverage-count queries (`IndexCountOps.java:246ff`) need one added
prefix-filter clause to yield "root R is N% enriched", chunks included.

Caveats that shape the design: `PrefixQuery` cost at large corpus scale is unmeasured
(the repo already bounds path scans elsewhere — `FolderBrowseEngine.java:58`), so per-root
coverage refresh must be modest-cadence and bounded. **Per-add-operation scope ("this
scan's N docs") is the one that would need new recording** — nothing correlates a scan's
documents after the walk — so progress scopes are: per-scan (walk phase only, via the
existing `scanId` SSE), per-root, and index-wide.

### 1d. There is no completion message — "done" is four independent state flips

No "indexing complete" toast or notification exists anywhere. What claims done today:

| Surface | Claim | Gate | Honesty |
|---|---|---|---|
| Health Queue card | "Up to date" | job drain + `indexHealthy` (`HealthSurface.ts:847-859`) | false during enrichment |
| Health Now strip | "System idle" | `visibleIndexQueueCount` suppresses enrichment counters as "stale residue" (`SystemSelfView.ts:38-63`, 727 F-2) | the exact mechanism of finding 1 |
| Library folder ✓ | "N files · indexed" | job drain per root; **keyword-only by documented design** (`folderStatus.ts:19`, per 599 §10 / 598) | truthful but silent about the semantic tier |
| Tasks panel | disappears | zero tasks (`TaskList.ts:89-91`) | absence-as-claim |

So finding 1's fix is not "gate the message" — there is no message. The design must decide
what **each surface** claims during the enrichment window (§4).

### 1e. Cancel and budget reality (finding 3's constraint, confirmed)

Root removal signals nothing to the backfill loop (no `WorkerSignalBus` reference anywhere
in the removal chain); an in-flight batch runs its full ~63 s and its single terminal RMW
write then updates zero documents. Interruption granularity today: between batches, plus
one mid-batch check *before* the encode phase (`CombinedEnrichmentBackfillOps.java:440-443`,
checks `running`/`isUserActive` only). The 5 s cycle budget is consulted before dispatching
a batch, never during (798 D2c as shipped) — minimum cycle latency is one full batch.
Ingest preemption works at batch boundaries (`hasPendingIngest`), so **enrichment
throughput is legitimately unstable while ingest runs** — relevant to estimate honesty.

### 1f. Estimation inputs (the 6–7× constraint, confirmed)

The job queue has **no byte-size column** (`SqliteSchema.java:48-57`) — byte-weighted
remaining-work estimation is not derivable from the queue today. What exists:
`ingestion_ledger.source_size_bytes` (post-hoc), the per-scan SSE channel
(`GET /api/scans/{scanId}/progress`) already streaming `bytesWalked/filesAdmitted`, and
30-minute `recentDocsPerSec` trends. The shell already has the discipline this design
needs: *no fabricated number on the unknown arm* (`availability.ts:193-194`) and *no bar
without a faithful denominator* (`HealthSurface.renderRebuildProgress`, `:803-817`).

---

## 2. The design in one paragraph

One **phase model** — *Scanning → Indexing → Enriching → Ready* — with two capability
tiers (*keyword-searchable* at job drain, *fully searchable* at coverage), projected from
**one derivation authority** that every surface reads, worded by **extending the existing
593/600 reason-code vocabulary** rather than inventing a parallel one, with estimates that
are rate-based and indicative or absent, a cancel affordance that appears only once it can
actually cancel, and the Tasks panel converted from a per-file feed to a compact
phase-progress card that cannot occlude the rail.

## 3. The phase model and the one authority

### 3a. Phases

- **Scanning** — the walk is enumerating a newly added root. Denominator unknown ⇒
  indeterminate affordance, counts from the scan SSE (`filesWalked/filesAdmitted`).
- **Indexing** — job queue draining. Denominator = admitted files (walk complete) ⇒ a real
  progress fraction exists per root and in aggregate.
- **Enriching** — job queue empty (or per-root drained) but coverage < complete.
  Denominator = coverage counters (doc- and chunk-truthful) ⇒ a real fraction exists.
- **Ready** — coverage complete for the scope in view.

Failure states (per-file failures, walk errors) ride the existing per-root
`failedCount`/`walkError` fields; they are not a fifth phase.

### 3b. One derivation authority (projection, not fork)

A single head-side **indexing-progress projection** assembles, per scope (index-wide and
per-root): job-queue counts, enrichment coverage (including chunk-level — closing the
finding-9 projection trap at the same stroke), the current phase, and the estimate inputs.
Every surface — Tasks panel, Health Queue card, Health Now strip, Library rows, status-bar
chip, and the `IndexingOverlay` — renders from this projection. Numbers may only differ
between surfaces by *scope*, never by *derivation*. Staleness is a first-class rendered
state (the `statusStale` / 807 A.3 "last known" precedent), not silently divergent
numbers.

This conforms to the projection-vs-fork discipline (CLAUDE.md, execution-surfaces
precedent) and to 801's "derive from the subject you are describing" — the panel today
describes the jobs table through a stale mirror; the projection derives from the worker
status that *is* the subject. Transport (extend the existing multiplexed SSE envelope
vs. the 10 s poll) is an implementation choice; the design constraint is single
derivation + surfaced staleness.

## 4. What each surface says (completion truthfulness)

The vocabulary extends `CAUSE_ROWS` / the 593/600 system — closed code→wording set,
severity tied to impact, gate-enforced closure (`check-readiness-reason-codes` pattern).
New phase nouns are part of that one vocabulary: **Indexing** (files → keyword-searchable)
and **Enriching** (semantic layers catching up). "Offline" is retired from all
progress-related copy (§6).

- **Tasks panel (redesigned, §5):** phase + fraction + scope. During enrichment:
  "Enriching — 64% · semantic search catching up". At true completion the panel shows a
  brief terminal state then dismisses; disappearance stops being the only completion
  signal.
- **Health Queue card:** stops claiming "Up to date" on job drain alone. Job-drain state
  reads "Indexed — enriching N%"; "Up to date" is reserved for coverage-complete.
- **Health Now strip:** the 727 F-2 "stale residue" suppression is **narrowed**: when
  `backfillMode` is active the strip shows "Enriching (N%)" instead of "System idle";
  suppression remains only for genuinely idle residue. (F-2's fix was right that stale
  counters shouldn't fake activity; it overcorrected by also hiding real activity.)
- **Library folder rows:** gain the second tier via per-root coverage (§1c):
  "✓ 312 files · keyword-ready · enriching 40%" → "✓ 312 files · fully searchable".
  This supersedes the keyword-only `ready` semantics documented at `folderStatus.ts:19`.
- **Degradation banner:** unchanged in role — it already words the enrichment window
  truthfully ("Passage embeddings are still being computed"); its transient-cause
  self-dismissal is Lane-2's slice of finding 9. The banner and the progress surfaces now
  share phase nouns by construction (one vocabulary).

The backend half of finding 1 (whatever machine-readable "done" signal exists must be
coverage-gated) belongs to the correctness lane; this section defines the words and the
per-surface claims that lane's regression test should assert.

## 5. The Tasks panel

- **Default view = one compact aggregate card:** phase label, one progress bar (faithful
  denominator per §3a, indeterminate during Scanning), counts ("412 of 1,630 files"), and
  the estimate line when available (§5b). The per-file list becomes **opt-in disclosure**
  ("Show files") — the owner's call that per-file display at ~1 s/file is noise. The
  existing `queued`-collapse (the 550 flood cure) is retained inside the disclosure.
- **5b. Estimates:** rate-based and indicative — observed recent throughput over the
  remaining backlog, rendered with "~" and the existing humanizers
  (`startupEstimate.ts`), suppressed entirely (no placeholder) when the rate is unknown,
  the walk is incomplete, or ingest/enrichment are interleaving (per §1e instability).
  Doc-count-only ETAs are prohibited by design on mixed corpora (the 6–7× constraint);
  the durable upgrade — recording size at enqueue so remaining work can be
  byte-weighted — is proposed as an owner decision (§8.1), not assumed.
- **5c. Placement:** the panel keeps its bottom-left home but the slot gains a **reserved
  band** mirroring the existing top-right reservation (`OverlayHost.ts:44-67`) so it
  structurally cannot cover the rail's bottom controls (Settings, Help), asserted the same
  way (`ui-proportion-baseline.v1.json` `mustNotOverlapSelector`). Deeper chrome
  relocation (docking into status bar / T-B's height budget) is explicitly left to T-B;
  this design only removes the occlusion defect and stays out of T-B's allocation
  questions.

## 6. Naming: retiring "Run Offline Processing"

The operation stays; the label and its conceptual home change. The action is renamed to
name the work in the new vocabulary — *"Finish enrichment now (N pending)"* (final copy at
implementation; the rule is: the button uses the same phase noun the progress surfaces
use, with the pending count inline). One registry string covers both render sites (Library
header, `LibrarySurface.ts:830-834`, and the banner remedy via `readinessNotice.ts:78-87`)
since the label lives in `registry-operation.en.properties:79`.

The vocabulary also fixes the distinction finding 1 turns on, as user-facing nouns:
**Reindex** = re-read source files and rebuild; **Enrich** = complete semantic layers for
already-indexed documents. "Offline" is reserved for nothing in progress/indexing copy
(the copy-lint from finding 11's regression home enforces single-sense usage).

## 7. Cancel honesty and Lane-1 dependencies

Per the 600 remedy-honesty rule (a remedy is not offered until it will actually work):

- **Until Lane-1 lands** a cancellation seam, no "Cancel" affordance ships. Root removal
  states its truth: enrichment already in flight stops at the next batch boundary
  (~a minute); copy says so plainly.
- **When Lane-1 lands** (expected shape: a removal/cancel signal on the existing
  `WorkerSignalBus` seam, checked at least at the pre-encode point and ideally between
  encoder sub-batches), the UI upgrades to an explicit intermediate state —
  "Stopping — finishing current batch" — which is honest at either granularity.
- The design does not prescribe Lane-1's mechanism; it prescribes the contract the UI
  needs: a bounded, stated worst-case latency between "user cancels/removes" and "GPU
  work for that scope stops", and a queryable "stopping" state.

Same dependency shape for finding 1: the completion-gating fix defines when "Ready" may be
asserted; this design defines what every surface says on each side of that gate.

## 8. Owner decisions requested

1. **Size-at-enqueue column** (jobs table migration) for byte-weighted remaining-work
   estimates — durable fix for the 6–7× problem vs. staying rate-based-only. Rate-based
   ships either way; the column upgrades estimate quality on cold starts and mixed
   corpora.
2. **Per-root coverage cost gate:** accept the PrefixQuery approach contingent on a
   measured cost check at realistic corpus scale (§9), with a bounded/cached refresh
   cadence; fallback is index-wide-only coverage plus per-root job state (status quo
   scope).
3. **Terminal notification:** should true completion ("fully searchable") additionally
   emit a one-shot toast/notification, or remain state-only? (Today absence-of-panel is
   the only signal; §4 makes states truthful either way.)

## 9. Open verification items (before/during implementation)

- Live-reproduce the stale-transport mechanism behind the 1218-vs-0 screen (§1a is
  static analysis; the fix in §3b removes the class either way, but the repro belongs in
  the regression test).
- Measure `PrefixQuery`-on-path count cost at scifact scale and above (decision 8.2).
- Verify every chunk-creation path seeds `chunk_embedding_status` (affects the per-root
  denominator; flagged by the backfill audit as unverified).

## 10. What this design orphans (deleted/reworded in the same implementation, not later)

1. `folderStatus.ts` keyword-only `ready` semantics **and its rationale comment**
   (`:18-19`, citing 599 §10 / 598) — superseded by the two-tier per-root state.
2. The Tasks panel's per-file-rows-by-default rendering (`TaskList.ts:277-308`) —
   demoted to opt-in disclosure. The count-chip summary row is absorbed into the
   aggregate card.
3. `SystemSelfView.visibleIndexQueueCount`'s blanket suppression of enrichment counters
   (727 F-2) — narrowed to genuinely-idle residue; its comment block must be rewritten.
4. The Health Queue card's job-drain-gated "Up to date" wording
   (`HealthSurface.ts:847-859`).
5. The label string `ops.trigger-offline-processing.label=Run Offline Processing` (and
   any docs/help text using "offline processing" for this action).
6. `IndexingOverlay`'s independent two-row queue readout (`IndexingOverlay.ts:224-246`) —
   re-derived from the §3b projection (the overlay itself and its interrupt CTA remain;
   only its private derivation is orphaned).
7. ~~Dead field~~ **CORRECTED (derisk pass, 2026-08-06):** worker-side
   `CoreStatus.pendingEmbeddingCount` (`IndexStatusOps.java:350`) is NOT dead — it is
   read Head-side via `VduOps.java:38` → `RemoteKnowledgeClient.countPendingEmbeddings()`
   and gates `OfflineCoordinator`'s embedding pass (tested in `OfflineCoordinatorTest`).
   The narrower truth: it is not mapped onto `/api/status`'s `CoreIndexView` wire
   projection. Nothing to delete; no action in this design.

Nothing in the 593/600 vocabulary machinery, the scan SSE channel, the per-root substrate
endpoint, or the backfill scheduler is displaced — the design extends all four.

## 11. Reach judgment

- **Conforms to (instances of existing principles, not new ones):** projection-vs-fork
  (the §3b authority); 801's *derive-from-the-subject* (today's status bar vs Tasks panel
  split is the violation this fixes; `IndexingOverlay`'s private readout is a second,
  latent violation); *faithful denominator or no bar* and *no fabricated number on the
  unknown arm* (already practiced at `renderRebuildProgress` and `availability.ts`);
  600's remedy-honesty (applied here to cancel); the closed-vocabulary gate pattern
  (`check-readiness-reason-codes`) extended with the phase nouns.
- **New principle worth naming: "a completion claim is a capability claim."** A surface
  may assert a terminal state only in terms of the capability that state actually
  delivers ("keyword-ready" vs "fully searchable"), never as an unqualified "done" while
  a capability tier is still building. Candidate scope beyond T-A: the AI-install flow
  already models this correctly (`installedFully` vs top-level `state: completed` —
  api-contract-map warns about exactly this split), and future staged pipelines (e.g.
  VDU transcription) will meet it again. Existing violations: the four surfaces in §1d.
  **Earning its keep:** future validation rounds produce no new
  "UI-says-done-while-capability-X-is-still-building" findings. **Retirement:** if
  enrichment ever becomes synchronous with indexing (single-phase pipeline), the
  two-tier vocabulary — and this principle's application here — should be deleted with
  it.
- **Generalization deliberately not built:** a reserved band is added only for
  bottom-left (the observed defect). The plausible invariant — *every fixed overlay slot
  declares a reserved band against the chrome it can occlude* — is real (top-right
  already does it; toast occlusion B6-8 remains unfixed and is Lane-2/T-B's), but
  building slot-generic reservation machinery now would be structure for cases this
  problem doesn't include. Record: if a third slot exhibits occlusion, generalize then.

---

## 12. Research pass record (2026-08-06)

A bounded external pass ran on two questions (two-tier readiness vocabulary; ETA
honesty). Findings that calibrate — none that reshape — the design:

- **ETA suppression without a faithful denominator is the prevailing shipped pattern**,
  not a novel restriction: Elasticsearch and Meilisearch expose progress numbers only
  from real finished/total counts; Spotlight, Dropbox Dash, Typesense, Notion expose no
  number at all. Meilisearch 1.12's `/batches` progress (named steps + finished/total +
  an explicit display-only caveat) is the closest shipped precedent for §3a's phase
  vocabulary.
- **No mainstream product exposes a two-tier "keyword-ready vs fully-searchable"
  readiness state.** Nearest analogs: Windows Copilot+ labels *results* "semantic +
  lexical" (a quality label, not readiness); Obsidian Smart Connections shows an
  embedding-coverage % (a metric, not a named phase). The two-tier vocabulary is filling
  a gap, not re-treading — and a user-facing rate-derived ETA for enrichment would be
  ahead of the field, so it ships coarse and suppressible, never precise.
- **Calibrations adopted:** any time estimate uses coarse phrasing ("about N minutes" —
  NN/g guidance); per-item progress inside a list is avoided in favor of one aggregate
  indicator (Fluent guidance — independently matches §5's card-over-feed shape);
  sub-10-second phases (typical Scanning) get an activity indicator, not a progress card
  fraction (NN/g 10-second pivot).

External sources are advisory; no external text was copied into product copy or docs.

## 13. Derisk record (2026-08-06)

Four probes ran (backend feasibility, FE blast radius, PrefixQuery microbench, external
research). Verdicts, all with file:line evidence in the probe reports:

- **Per-root coverage cost: CHEAP** (measured). Real FSDirectory index, 200k docs,
  5 roots, the exact numerator/denominator query shapes: warm medians < 0.3 ms, worst
  realistic cold ~5 ms. A 10–30 s cadence for ~10 roots is negligible. Dev-machine
  measurement; the reader-version cache pattern (`IndexCountOps.java:194-203`) is
  adopted as insurance anyway since the Library live tick refreshes at 4 s.
- **Per-root coverage plumbing: SMALL.** No new RPC — extend `CountJobsByPathPrefix`
  (`indexing.proto`; handler `GrpcIngestService.java:1476-1491`, which already calls
  `indexCountOps` in-process at `:1176`), add fields through `RemoteKnowledgeClient` →
  `IndexedRootView` → the substrate map in `IndexingController.java:768-792`, regen
  schemas. Chunk prefix-match is valid (chunk PATH = parent path); a **factually wrong
  comment** at `QueryFilterBuilder.java:288-289` claims otherwise and is corrected in
  the same slice. The SQLite and Lucene prefix-normalizers are two implementations —
  a parity test pins their agreement.
- **Chunk denominator: SOUND.** One production chunk writer, unconditional status seed
  (`ChunkDocumentWriter.java:175`). Ratio rules: parent-stage denominators exclude
  `is_chunk` docs; chunk coverage is over *chunked* docs (never "N of M files");
  completion gates on terminal states (COMPLETED + COMPLETED_EMPTY + FAILED), not 100%
  COMPLETED.
- **Size-at-enqueue: CHEAP.** The two bulk enqueue paths already hold
  `BasicFileAttributes` (size is free); nullable column via V7→V8 migration (the design's
  earlier "V5" reference was stale). Trap designed around: the queue's
  `INSERT OR REPLACE` resets unlisted columns, so size rides the enqueue *signature*
  (entry object) at all 6 call sites, not a side channel.
- **Transport (closes §3b's open choice): poll-derived selector.** Every number the
  design needs is already on `/api/status` (verified against the generated contract);
  the FE reads none of the enrichment/chunk numbers today. The projection is a FE
  selector module over the one poll snapshot — internally consistent by construction.
  The SSE `core.indexing-jobs` stream keeps exactly one job: the opt-in per-file row
  list (with its existing stall honesty). A new SSE resource was evaluated and
  rejected: four governance registers of cost, un-capturable in ui-shot fixtures, and
  it would be a third representation of the jobs table — the §1a class again.
  Staleness reuses the FE-side precedent `AiState.snapshotLive` /
  `lastSettledIndex` (`aiStateStore.ts:509`, `:249`) — NOT `statusStale`, which lives
  on `/api/knowledge/status` and has zero FE consumers; and
  `chunkCoverage.observedAtMs` is a Head serialization timestamp, not a freshness
  signal — neither may be used as one.
- **Lane-1 status re-checked: not landed** (all post-design commits on main are
  docs-only). §7 dependencies unchanged.
- **Blast radius mapped** (FE probe): the copy-pinning tests that will be deliberately
  updated (`HealthSurface.render.test.ts`, `folderStatus.test.ts`,
  `readinessNotice.test.ts` untouched-unless-rows-change, `TaskList.test.ts`,
  `SystemSelfView.test.ts` — its `:113` case is the 727 F-2 pin and is re-expressed,
  not deleted), the `AiIndex` literal fan-out (new fields optional-with-defaults), and
  the ui-shot needs: a new deterministic `_status_body` enrichment fixture variant, a
  new occlusion step whose `mustNotOverlapSelector` row carries a **min-size companion
  row** (a hidden element yields a 0-rect and passes vacuously — verified in
  `ui_proportion_gate.py:64-90`; the pre-existing `.toast` row shares this latent
  vacuity, noted for Lane-2/T-B).
- **Pre-existing governance gap now in scope:** `check-folder-status-derivation.mjs`
  and its register exist but are wired into neither `ci.yml` nor the `ui-web-gates`
  recipe (tempdoc 599:585 claims otherwise). Since this work modifies exactly the seam
  that gate governs, wiring it into the recipe joins the scope.

**Confidence: 9/10** for the scoped implementation (all feasibility verdicts
CHEAP/SMALL/SOUND; residual risk is mechanical test fan-out, which the blast-radius
table bounds). Recommended worker tier: opus for all slices.

## 14. §8 decisions — resolved (autonomous, under the owner's 2026-08-06 delegation)

1. **Size-at-enqueue: YES** (Slice B). CHEAP verdict; durable estimate quality;
   signature-carried to defeat the `INSERT OR REPLACE` reset trap.
2. **Per-root coverage: ACCEPTED** — measured CHEAP; ships with bounded cadence + the
   reader-version cache; index-wide-only fallback not needed.
3. **Terminal notification: STATE-ONLY for this tempdoc.** A completion toast would
   ride on the known-defective toast stack (798 B6-8, still unfixed); states are
   truthful either way; revisit after T-B/Lane-2 fixes overlays.

## 15. Implementation plan (waves; each slice is a bounded delegation)

**Wave 1 (parallel):**
- **Slice A — backend per-root coverage.** New per-prefix count ops (numerator/
  denominator per stage + chunk tier, terminal-state semantics per §13), reader-version
  cache, extend `CountJobsByPathPrefix` RPC + proto, `RemoteKnowledgeClient` →
  `IndexingService.JobCounts` → `IndexingController` substrate map →
  `IndexedRootView` fields, `updateSchemas` regen, `--gate wire`. Fix the
  `QueryFilterBuilder.java:288-289` comment; add the prefix-normalizer parity test.
  Acceptance: worker-services + adapters-lucene tests green; substrate emits coverage
  fields; wire gate green.
- **Slice C — FE progress projection + index-wide consumers.** New selector module
  (phase + numbers + staleness from the poll snapshot; no local response-shape
  interfaces; explicit `operation-surfaces` declaration alongside `count-projection`),
  consumed by: StatusDeck chip, SystemSelfView (narrow the F-2 suppression —
  `backfillMode` active ⇒ "Enriching (N%)", idle residue still suppressed; collapse its
  in-file dual derivation), HealthSurface `queueSubLabel` two-tier wording (both render
  paths), IndexingOverlay re-derivation (preserving `snapshotLive` withdrawal).
  Blast-radius test updates per §13. Acceptance: `npm run typecheck` +
  `test:unit:run` green; projection unit suite covers phase/denominator/staleness arms.

**Wave 2 (parallel, after their dependencies):**
- **Slice B — size-at-enqueue** (backend; after A to avoid same-file conflicts).
  V7→V8 nullable `size_bytes`, enqueue-entry signature at all 6 sites (default overload
  keeps test impls compiling), aggregate pending-bytes exposed on the status wire.
  Acceptance: indexer-worker tests green incl. a REPLACE-preserves-size regression.
- **Slice D — TaskList aggregate card + placement** (after C). Aggregate card (phase,
  faithful-denominator bar, coarse "~" ETA only in Indexing phase with walk complete and
  stable rate — suppressed during Enriching per §1e), opt-in "Show files" disclosure
  (natively operable control), terminal-state-then-dismiss; OverlayHost bottom-left
  reserved band; proportion-baseline row **plus min-size companion**; new deterministic
  ui-shot step + `_status_body` enrichment fixture variant + a11y baseline row.
  Acceptance: FE tests green; new ui-shot step passes; occlusion row non-vacuous.

**Wave 3 — Slice E: per-root two-tier + naming + docs** (after A + C). `folderStatus`
two-tier states ("keyword-ready · enriching N%" → "fully searchable") consuming the new
`IndexedRootView` fields; add the projection/consumers to the `folder-status-derivation`
register's `allowed` (or route through the seam) and **wire that gate into the
`ui-web-gates` recipe**; rename `ops.trigger-offline-processing.label` (one registry
string covers both render sites), sweep "offline processing" from
`docs/explanation/10-ui-ux-design.md` / `02-process-coordination.md`; add the
single-sense "offline" copy-lint (finding 11's regression home); satisfy
`maintain-doc-hint` (kernel doc note for the projection seam).

**Wave 4 — Slice F: verification + review.** Full gradle build + full unit suite, full
ui-web gate set, ui-shot steps, critical-analysis pass, independent refute-first review
(reviewer ≠ implementer), live-stack spot verification if the shared dev stack is free,
813 updated with evidence.

## 16. Implementation record (2026-08-06/07)

All five slices landed on `worktree-ta-indexing-ux` (A: per-root coverage; C: the
projection + index-wide consumers; B: size-at-enqueue V8 + `pendingBytes`; D: Tasks
aggregate card + reserved band + `tasks-occlusion` step; E: two-tier folders, rename,
docs sweep, gate wiring, offline copy-lint), each verified green before commit.

**Independent refute-first review** (reviewer ≠ implementers) found no blocker; the
prefix arithmetic, migration, cache keying, and reserved band survived adversarial
probing. It found **3 HIGHs — all in the claim layer** §11's principle governs, which is
evidence for the principle and against the first implementation of it: (1+2, one root
cause) the chunk tier lacked the applicability gate the parent stage had — false
*incompletion* ("Ready" unreachable) with embeddings disabled, false *completion*
("fully searchable") on the unknown-applicability sentinel production actually delivers;
(3) the renamed operation's description asserted an AI-engine precondition the catalog
deliberately removed. All three fixed in the remediation commit, with a
production-sentinel-shape test (not a hand-made null) pinning the sentinel fix; ten MEDs
fixed in the same pass (ERROR-arm dual derivation collapsed; legacy-index denominators
now field-presence-based — widening the wire to 8 per-stage coverage fields; cache
javadoc corrected + no caching of failures; the folder-status gate's predicate extended
to the new fields; ETA additionally suppressed while the backlog is growing, read from
the queue-depth trend; watcher + gRPC size producers test-pinned; `pendingBytes` gained
an error-distinguishable `UNAVAILABLE` marker and a real consumer — the Tasks card's
"N files remaining · N GB" line; occlusion floor 160→140 with rationale).

**Accepted deviations from the letter of this design:** §6's "(N pending)" inline count
is not wired — the label is one static registry string read by both render sites; a
dynamic label would be new substrate for marginal value (the banner remedy and folder
rows already carry pending context). VDU is not a stage in the coverage percent
(`pendingVduCount` stays separate); with the dual-derivation collapse, VDU-only work no
longer renders a Now-strip row — consistent, and recorded here as a behavior change.
`computeRootCoverage`'s no-cache-on-IOException behavior is inspection-verified only
(the only injection point is a final production class; a seam change for one test was
not warranted).

**Verification evidence:** full `./gradlew.bat test` BUILD SUCCESSFUL (bare, twice:
post-Wave-2 and post-remediation); FE 385 files / 4091 tests green + typecheck clean;
ui-web gate set green except the two recorded pre-existing reds (RecentsMenu
theme-token ghosts, ActionLedgerView accent-as-text — expected-state.v1.json); wire /
operation-surface / register-guard-resolution / folder-status-derivation /
offline-single-sense all green (wire verified non-vacuous after a `buf-cli-missing`
vacuous pass was caught and logged); `tasks-occlusion` ui-shot captured live in
fixtures mode — panel genuinely rendered (159×94 rect, "Indexing progress" in the a11y
tree, coarse "~1m 43s" ETA line visible), no overlap with Settings/Help, axe 0 new.
Live-stack verification against a running backend was not performed (shared dev stack
not leased during this arc); the fixtures-mode capture plus unit/gate tiers are the
evidence base, and the §9 stale-transport live repro remains open for a future
dev-stack session.

**Register note:** `/search-quality` and `/inference-runtime` registers not updated —
this work changes no retrieval behavior and no inference runtime; it projects existing
counters. (Rule satisfied by stating why.)

## 17. Merge reconciliation with the parallel lanes (2026-08-07)

While this branch was built, the campaign's other lanes landed on main: **both Lane-1
fixes** (#374 backfill yield/abandon — finding 3's cancel/budget; #375 witnessed
"indexing done" — finding 1's gating, including its own `enrichmentCoverage.ts` module
and a global `enriching` folder state), **T-B's chrome pass** (#376 / tempdoc 814),
and Lane-2's quick wins (#370, which independently renamed the operation, and #371's
chunk-pathPrefix fix). The merge was resolved by the orchestrator; the decisions:

- **Two authorities reconciled as two CONCEPTS, not one winner.** #375's
  `enrichmentCoverage.ts` (positive-evidence boolean + tier names — a CLAIM gate) and
  this branch's `selectIndexingProgress` (phase/percent/staleness — the NUMBERS
  authority) stay separate, cross-referenced; folderStatus consumes both. This is a
  recorded deviation from §16's earlier "projection of the projection" intent: merging
  them mid-merge would have churned #375's landed tests for no truthfulness gain. If
  the two ever disagree in the field, that is the trigger to unify them.
- **Folder tier**: state name `enriching` (theirs) with this branch's per-root
  percent mechanics; four honest arms — per-root percent when derivable, their global
  boolean as the caveat-without-percent fallback, per-root complete outranking the
  global boolean (a strict improvement on both sides), pre-813 wording only when
  nothing is known. Their `pending` glyph for the enriching tier was adopted over this
  branch's ✓ — a checkmark while semantic search is still building is the soft form of
  the original defect.
- **Vocabulary**: their shared constants win everywhere
  (`ENRICHMENT_CATCHING_UP_CAVEAT`, `ENRICHMENT_IN_PROGRESS_LABEL` = "Building
  semantic search" — deliberately the Brain surface's existing phrase); this branch
  appends the percent when faithful. Operation label: their **"Process pending
  enrichment"** (closest to the owner's own suggested copy in finding 11) with this
  branch's corrected description — their comment's repeated `InferenceOnline`
  precondition claim was false (813 review F2) and was fixed in the merged comment.
- **Cancel/budget dependency (§7) partially discharged**: #374 landed batch-boundary
  yield + abandonment of bulk-deleted work. The UI still offers no Cancel affordance;
  upgrading copy to name #374's actual latency bound is follow-up work, not blocked.
- `QueryFilterBuilder`: #371's fix (chunk branch now RESPECTS pathPrefix) supersedes
  this branch's comment-only correction; the merged javadoc records the history.
- Additive unions everywhere else (814's new gate constraint kinds + steps alongside
  `tasks-occlusion`; both baselines; both fixture variants).

## 18. Post-merge review record (2026-08-06, /review-changes)

A second refute-first pass covered the post-remediation delta (the three merge
reconciliations, merge-completion rework, positive-evidence phase gate) plus
**live-stack verification** of the shipped build — the outside-anchored evidence the
first review could not supply:

- **Live, held:** `/api/status` carried `pendingBytes` tracking a real 390-doc ingest
  (4,953,432 → 1,729,474 → 0 bytes, zero unknown-size jobs); the phase window played out
  as designed (INDEXING → backfill active with counters falling → settled); finding 9's
  trap was observed live (doc-level pending 0 while 3,850 chunk embeddings pended) and
  the chunk-inclusive percent guards it. Per-root: a root added via
  `POST /api/indexing/roots` served all 8 coverage fields with exact denominators
  (30/30/30 parents — the folder's true file count — and 335 chunks) and settled counts
  climbing during enrichment. The reviewer verified the samples could only come from the
  merged build (`pendingUnknownSizeJobs` exists in exactly one commit).
- **Fixed from the review:** the status-bar chip's count fallback rendered the
  multi-stage `rawPending` sum under an "embed" label (~10× the embedding number on a
  chunked corpus) — now renders `embeddingPending`, with a discriminating test.
- **Found upstream, filed as an observation (not a 813 defect):** live-witnessed
  SPLADE/NER **stage starvation** — six consecutive backfill cycles selected 100 SPLADE
  docs and executed zero SPLADE/NER work because the embed stage consumed the whole 5 s
  budget first (`CombinedEnrichmentBackfillOps` abort branch). The coverage counting and
  folder states report this truthfully as "enriching", but the terminal states are
  unreachable while embedding work keeps arriving. Pre-existing 798 D2c budget design;
  needs its own worker-side budget-fairness slice.
- **Honest residuals recorded:** field-presence denominators mean a legacy index whose
  documents predate a stage's field can read "fully searchable" while lacking that layer
  (the deliberate inverse trade of the false-incompletion fix); stage-enabled flags
  default applicable during a brief boot window until model init wires them; the
  proportion-gate captures were re-taken on current `main` because the original capture
  evidence did not survive worktree teardown.

## 19. Follow-up plan (2026-08-06, /plan): design-exploration candidates + recorded residuals

Inputs: a generative design exploration (owner-directed, Claude Design; project
"JustSearch indexing progress card") produced three candidates the owner routed into
implementation, plus §17's cancel-copy residual. An investigation pass mapped every seam
first; decisions below cite it.

**W1 — Capability-first enriching copy.** The Tasks card's enriching headline becomes
"Search is ready — still improving" (capability first, matching the caveat constant's
own order; "improving" over the exploration's "learning" to avoid anthropomorphism).
Seam: `TaskList.headline()` is a private literal with one render site — change it there;
do NOT add a third shared constant (the two in `enrichmentCoverage.ts` have documented,
deliberately distinct subjects). While there, fix a real fork the investigation found:
the literal `'semantic search catching up'` is duplicated in TaskList and SystemSelfView
outside the shared-vocabulary module — promote it to a body-tier constant in
`enrichmentCoverage.ts` and consume it at both sites. No governance register carries
these phrases; no gate constrains the change.

**W2 — High-water-mark determinate indexing bar.** During the indexing phase the bar
may honestly be determinate against the backlog's observed maximum this drain episode:
`episodeMaxPendingJobs` lives in `aiStateStore` (mirroring `lastSettledIndex`'s
imperative-stamp pattern exactly: signal, doctrine comment, stamp in `onStatusUpdate`,
snapshot exposure, test reset; reset when `pendingJobs` drains to 0) and is passed to
`selectIndexingProgress` as a REQUIRED third parameter — optional-with-default would
let six surfaces silently derive a different percent from the seventh, the exact
two-derivation drift §3b forbids. The bar falls back to the indeterminate arm whenever
`episodeMax <= jobsPending` (no drain observed yet — genuinely no denominator; also
what a static fixture shows, so `tasks-occlusion` stays byte-stable). Honest cost,
stated: the determinate indexing arm has no deterministic ui-shot capture (a static
fixture cannot exhibit cross-poll memory); its coverage is the unit suite, recorded
here rather than silently omitted.

**W3 — Cancel copy: fact without a fabricated bound.** The root-removal confirm gains
the enrichment fact: in-flight enrichment for the folder is stopped and its work
discarded. The plan deliberately names NO numeric latency: #374's bound is
mode-dependent (combined mode checkpoints at 1-8-document granularity with a
deliberate SPLADE stage-boundary hole; individual mode retains whole-batch atomicity
per #374's own logged residual), so "within seconds" would overclaim exactly where the
old copy underclaimed. No test pins the current confirm string; the registry keys stay.

**W4 — Compact fact row.** Counts and ETA merge to one line
("412 files remaining · 8.00 GB · ~1m 43s left"); the indicative qualifier "at the
current rate" survives in the element's accessible label/title rather than the visible
line. The eta-absence intent (no placeholder, segment simply absent) is re-expressed at
segment level. Width risk is real (the merged run lengthens toward the 24rem cap near
the Settings/Help overlap assertions) — `tasks-occlusion` re-capture + proportion gate
are the acceptance check.

**Explicitly NOT in scope, with reasons:** SPLADE/NER budget-fairness (its own
worker-side slice; observation filed with live evidence); the §16 stale-transport live
repro (superseded by structure — every rendered number now derives from the one poll
projection, so the two-transport disagreement class has no code path left to
reproduce); byte-weighted ETA (needs a processed-bytes rate the wire does not carry;
`pendingBytes` substrate is in place, consumer shipped).

**Validation:** FE typecheck + full unit suite; re-capture `tasks-occlusion` +
`library-enriching` + proportion gate; copy lints (`check-offline-single-sense`,
folder-status gate untouched); real-UI check via the fixtures-served browser captures.
Work in worktree `813-followups`; no PR until the owner licenses it.

## 20. Owner live-validation follow-up (2026-08-06): honest percent + a detail tier

Two findings from the owner running the shipped card against a real index, and the design
they settle. Both are about the same thing: the card had exactly ONE altitude, and it was
being asked to be both a capability claim and a machine report.

**Finding A — the fake 100%.** The card rendered "100% · semantic search catching up": a
full bar next to a caveat saying the work was unfinished. Cause: `Math.round` promotes a
sub-half-percent tail (e.g. 2 pending of 600) to 100. This is §1d's false terminal wearing
a number — the same defect the whole redesign exists to remove, re-entering through the
formatter rather than through the phase gate.

*The floor rule.* The enriching percent is capped at **99 while any counted work is
pending**; a true 100 is reachable only when `pending === 0`
(`indexingProgress.ts` — the `Math.min(pending > 0 ? 99 : 100, …)` clause). Symmetric with
the existing no-fake-0% rule: a denominator-less blend still yields `null`, unchanged. The
phase gate is untouched and still decides `ready` on its own evidence, which includes the
denominator-less stages the percent cannot see.

**Finding B — no way in.** The card offered no route to per-stage detail. The disclosure
listed per-file rows only, and during pure enrichment there are no task rows at all — so
the button was not merely uninformative, it was *absent* exactly when the user most wants
to know which stage is still running.

### 20a. The two-layer principle

> **Capability tiers on the surface, machine stages in the disclosure.**

The surface says what the user can *do* and how far the whole blend has come ("Search is
ready — still improving · 62% · semantic search catching up"). The disclosure lists the
machine stages that add up to that number. The layers are an altitude split, not two
subjects — and the mechanical guarantee is that **numbers may only differ by SCOPE, never
by DERIVATION** (§3b, applied one level down).

### 20b. `enrichingStages` — a projection, not a fork

`IndexingProgress.enrichingStages: readonly EnrichingStageRow[]` carries the blend's own
inputs: `{ id, total, pending }` per stage, built from the very `stage()` results the
percent sums. There is no second read of the wire and no second applicability decision —
the four stage constructions moved into one `readEnrichmentWork(status)` whose result feeds
the percent, the phase gate and the settled-sum authority alike. Consequences that fall out
for free rather than needing their own rules: a disabled stage has no row *because* it has
no blend contribution; the chunk row rides embedding applicability *because* the blend's
chunk stage does; a stage with nothing to enrich has no row *because* `stage()` already
refuses a zero denominator.

Two supporting exports, both for the store and both for the same reason (a store-side
re-derivation is a fork by construction): `enrichSettledSum(status)` — the ONE settled-sum
authority — and `selectIndexingPhase(status)`, so the store's episode-clear test asks the
same phase question the selector answers.

### 20c. `enrichingEtaSeconds` — its own rate, or nothing

The indexing arm's ETA gauge (`core.recentDocsPerSec`) measures the **indexing** pipeline;
reusing it here would answer a question about enrichment with a measurement of something
else. The wire carries no enrichment-throughput gauge, so the rate is built from cross-poll
memory following §19 W2's `episodeMaxPendingJobs` pattern exactly — signal, doctrine
comment, imperative stamp in `onStatusUpdate`, snapshot exposure, test reset:

- `AiState.enrichSettleSamples` — up to `ENRICH_SETTLE_SAMPLE_CAP` (6) `{ t, settled }`
  samples, stamped through `enrichSettledSum` so the trail measures the quantity the bar
  renders, and **cleared whenever the derived phase is not `enriching`** (a fresh episode
  measures itself; intervals spanning a phase change compare two regimes).
- Passed to `selectIndexingProgress` as a **REQUIRED** fourth parameter, for W2's reason:
  optional-with-a-default lets six surfaces silently derive a seventh answer.
- Suppressions (render nothing, never a placeholder): fewer than 3 measured intervals; any
  interval whose settled sum did not strictly advance (a paused or preempted backfill is
  not a slow one, and a backwards sum means ingest moved the denominator); a non-live
  snapshot; any phase but `enriching`; a result past `ETA_MAX_SECONDS` (3600). Median
  interval rate, not mean — one fast poll cannot halve the estimate.

### 20d. What the card renders

- The counts line gains a `· ~Nm Ns left` segment during enrichment, appended never
  substituted, with the INDICATIVE qualifier in `title`/`aria-label` (§19 W4's form, reused).
- The disclosure is now **"Details" / "Hide details"**, and is present whenever there is
  anything to show: task rows, stage rows, **or** a non-zero extraction backlog. It opens
  during pure enrichment with zero task rows — the whole point of Finding B.
- Inside, above the unchanged files section: one plain-text row per stage,
  `{label}  {settled} / {total}` with a trailing ✓ at zero pending; plus a denominator-less
  `Content extraction — N remaining` row when `vduPending > 0` (no fraction, no bar — no
  denominator exists, and inventing one is the defect this whole tempdoc is about).
- Labels are USER words held as local literals in `TaskList.ts` (§19 W1: no shared constant
  without a second consumer): Semantic vectors / Keyword expansion / Entity recognition /
  Passage vectors. A sweep of Health and Brain found the only existing user-facing names for
  these to be the bare acronyms "SPLADE"/"NER" in `display/facts.ts` capability chips and
  Health's realized-engine rows — a different subject ("is this engine present?", not "how
  far along is it?") and wire words besides, so they were not adopted.

### 20e. What this orphans, and what stays parked

**Orphaned:** the "Show files" / "Hide files" disclosure label (renamed at its one render
site and its two test assertions; §5 and §16's prose above are dated history and keep their
original wording). Nothing else — no constant, registry key, gate or capture depends on it.

**Parked, with reasons:** a segmented bar (one band per stage) — the stage rows carry the
same information without competing with the single capability-tier bar, and a segmented bar
would need a shared per-stage colour vocabulary this surface is the only consumer of;
per-file enrichment names ("which file is being embedded right now") — the wire carries no
per-file enrichment attribution, so it needs worker support first. Both remain design
candidates, neither is blocked on anything in this section.

### 20f. Addendum (2026-08-07): the stale gauge — "Ready" was unreachable

A second owner live-validation pass, diagnosed from the API on a running stack, found the
mirror image of §20's Finding A. Every enrichment stage was fully settled — embedding 21/21
pending 0, SPLADE 21/21 pending 0, NER 0 pending, chunk 84/84 pending 0 — and the card still
read "Search is ready — still improving · semantic search catching up", indefinitely. The
wire showed `backfillMode: "individual"`.

**The doctrine line this establishes:**

> **A last-known gauge is never phase evidence.** Phase is derived from pending COUNTS.

`enrichment.backfillMode` is an operator gauge, not an activity signal: it is written once
per `BackfillScheduler.runIdleCycle()` and *held between cycles*, and its own getter
documents `"idle"` as "no backfill work was available/eligible **last cycle**"
(`OperationalMetrics.java` — tempdoc 710 Move 2 item 4). It answers "which pass ran?", never
"is work outstanding?". §20's Finding A was a percent claiming completion the phase denied;
this is a phase denying completion the counts had already reached. Both are the §1d false
terminal — one fabricating an ending, one refusing to admit a real one — and the positive
-evidence doctrine (§17) covers both directions: the counts are the evidence, in and out.

**Fault 1 — FE (load-bearing).** `derivePhase` consulted `backfillActive` and is now
counts-only: `jobsPending > 0 ? 'indexing' : rawPending > 0 ? 'enriching' : 'ready'`. The
`backfillActive` member is gone from `EnrichmentWork` — it had exactly one consumer, the
phase gate, so leaving it would be residue. The wire field itself is untouched and remains
available on the snapshot for any display/ops consumer (ui-web has none today; the sweep
found only this gate). A welcome consequence for §20c: the `enrichSettleSamples` trail
clears on any non-`enriching` phase, so a stuck gauge can no longer hold an enrichment-rate
episode open on a settled index.

**Fault 2 — worker hygiene.** `BackfillScheduler.runIdleCycle()` stamps `"individual"`
*before* running the pass. The pre-stamp is kept — mid-pass observability is the gauge's
purpose — but the pass now re-stamps `"idle"` when it advanced nothing, which is exactly
what the getter's contract already calls idle.

**Implementation note (deviation from the brief, empirically forced).** The re-stamp is
keyed on ACTIVITY (`StageOutcome.docsProcessed() > 0` across the stages), not on
`runIndividualBackfills`' boolean return. That return is a PACING flag — only the chunk
tight-loop and a SPLADE pass over a non-empty backlog set it, so a parent-embedding batch
leaves it `false` while genuinely embedding documents. Keying the re-stamp on it would stamp
`"idle"` over a working cycle: the same lie, other direction. This is tempdoc 798's own
distinction, already stated in this file for the combined branch ("Mode selection reads
ACTIVITY... the tight loop reads PROGRESS... conflating them is what livelocked ingest"), so
`runIndividualBackfills` now returns `IndividualOutcome(didWork, anyActivity)` — pacing
semantics unchanged, gauge served by its own signal. The regression test
`individualMode_withWorkDone_keepsIndividual` pins this: it asserts `didWork == false` AND
that a document was embedded AND that the mode stays `"individual"`, so it fails if anyone
re-keys the re-stamp on the pacing flag.

**Coverage.** FE: `jobs drained and NO pending counters ⇒ "ready", whatever the backfill
gauge last said` (all four gauge values), `REGRESSION: a fully SETTLED index reaches "ready"
while the gauge still says "individual"` (the owner's exact snapshot, with a right-reason
guard that all four stage rows are present and the percent is a true 100), plus both
evidence-wins-anyway directions. Worker: the two `§20a` cases above, in the existing
`BackfillSchedulerModeRecordingTest`. The pre-§20a FE case that asserted "backfill running
with no pending counters ⇒ enriching" was INVERTED rather than deleted — it encoded the
defect, and its snapshot is now the regression fixture.
