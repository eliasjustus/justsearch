---
title: "Indexing progress & queue UX — end-to-end design (hv campaign thread T-A)"
status: "open — design settled 2026-08-06; implementation not yet licensed; depends on Lane-1 fixes (809 findings 1, 3) which are unstarted"
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
7. Dead field noted in passing: worker-side `CoreStatus.pendingEmbeddingCount` is set
   (`IndexStatusOps.java:350`) but never mapped by the Head — delete or map it while in
   the file (small, in-scope cleanup; it is precisely a second authority nobody reads).

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
