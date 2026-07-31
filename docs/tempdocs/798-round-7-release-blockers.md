---
title: "Round 7 release blockers — artifact-truthful readiness at the write boundary"
status: "CLOSED 2026-07-31 — B1-B7 all implemented and merged to main (PRs #339-#346); validated end-to-end by Sandbox round 8, which returned QUALIFIABLE with no blocking defect. Round 8's own 8 findings + 1 host-side finding (F9) are recorded in tempdoc 734, not here."
created: 2026-07-30
updated: 2026-07-31 (round-8 outcome)
related: [734, 749, 750, 772, 760, 717, 711, 712, 597, 565, 553, 560, 516]
---

## Owner decisions (2026-07-30)

1. **PR scope: B1 only.** The livelock ships alone — it is the one change that can silently
   break indexing, and bundling it with CSS fixes would bury it in review.
2. **No data repair. There are no current users**, so phantom statuses in existing indices need
   no migration, repair pass, or release note. Any *dev* index built before this fix should be
   rebuilt (`--clean`); pre-fix indices may carry manufactured COMPLETED statuses.
3. **B2 shapes: exempt honestly now, wire later.** Mark the register rows exempt with the real
   reason (no shipped entry point); file the feature work separately. Not in this PR.
4. **B7 parity: demote overlap@10 to descriptive**, gating on the environment-robust signal
   (golden #1 in top-3, green on all ten queries) — tempdoc 750's pre-designed A4. Not in this PR.

## B1 implementation record (2026-07-30)

Landed on `worktree-798-ingest-livelock` as `e0d76521` + the write-contract commit. Verified:
full suite genuinely re-run (`cleanTest test`, not an up-to-date pass) — **33 modules, 6,884
tests, 0 failures, 0 errors**.

**Part 1 (root cause).** Absent status no longer defaults to `PENDING` — absent means the stage
does not apply (`CombinedEnrichmentBackfillOps.java:351-353`). Blank-content branches escalate
through each stage's retry/`FAILED` seam instead of claiming COMPLETED without an artifact
(`:355-384`, plus `SpladeBackfillOps.java:97-111`, `BgeM3BackfillOps.java:108-136`,
`NerBackfillOps.java:82-95`). `SchemaFields.NER_STATUS_COMPLETED_EMPTY` carries the
legitimately-empty NER result, mirroring the existing `VDU_STATUS_COMPLETED_EMPTY` precedent.

*Deviation from plan, accepted:* the plan said "delete the splade COMPLETED fallback". Deleting
it outright would leave those documents permanently `PENDING` and re-queried every cycle — a
second churn loop. They escalate to `FAILED` instead, which is terminal for the pending queries,
so the population actually drains.

*The reader sweep caught a real silent regression before it shipped:*
`IndexStatusOps.java:640-647` would have stopped counting `COMPLETED_EMPTY` toward
`completedNerCount`, which feeds `/api/status` and the jseval readiness gate — NER would have
appeared never to finish. Two existing tests asserted the OLD wrong behaviour (one whose name
said it pinned "the historical silent data-less COMPLETED behavior byte-identically"); both were
rewritten to assert the correct behaviour rather than quietly deleted.

**Part 2 (the contract).** `StatusArtifactContract` (new, `modules/adapters-lucene`) rejects any
write setting `<status>=COMPLETED` without its witnessing artifact. The map is *derived* by
inverting the existing `rmwPolicy` declarations (`FieldMapper.rmwPolicyStatusTarget()`,
`deriveStatusWitnessFields()`) — **zero schema changes**. Enforced at **both** lanes:
`IndexingCoordinator.validate()` (full-doc) and `WritePathOps.readModifyWrite` after the merged
map is complete (RMW). Pure map check, no index I/O. Production runs FAIL mode
(`ValidationMode.from(null) → FAIL`; no config sets the key), so it genuinely rejects.
The RMW reset lanes are untouched and remain the backstop behind it.

**Part 3 (containment).** The tight loop terminates on `progressed`, not `wroteAnything`
(renamed from `anyWorkDone` so it cannot be reused as a loop condition). Pending ingest is a
third `WorkerSignalBus` yield signal alongside `isUserActive`/`shouldYieldGpuBackfill`, probed
on a 250 ms TTL via the cheap indexed `queueDepth()` before the more expensive precise count.
Hard 5 s per-cycle budget with a WARN naming the diagnosis. The same unbounded-loop twin in
individual mode was fixed too.

**Bite proofs (all performed, not merely claimed).** T1 hangs against the pre-fix loop
condition; T2 fails its latch; T3's two rejection tests fail when the contract is disabled — and
with *only* the coordinator call restored, the RMW-lane test still failed, proving that lane's
check is independently load-bearing and that `validate()` alone would not have been sufficient.

## Adversarial review and the fixes it forced (2026-07-30, same day)

A refute-first review of ten verification claims **refuted two and found a HIGH-severity defect
that left the livelock class partially open.** Eight claims survived, including the one that
mattered most: an exhaustive bypass hunt found **no production write path** that reaches Lucene
without passing a contract call site.

### F1 (HIGH, FIXED) — the contract was a null check, so an empty artifact still wrote a lie

`anyArtifactPresent` accepted any non-null value, but `FieldMapper.addFields` emits a SPLADE
posting **only for `weight > 0.0f`**. An empty or all-non-positive weights map is non-null —
contract satisfied — yet materialises **zero postings**. The writers set it with `COMPLETED`
unguarded. On any later RMW, `applyResetStatus` saw `COMPLETED`, reset to `PENDING` **and zeroed
`splade_retry_count`**, so escalation could never reach `SPLADE_MAX_RETRIES`. **That is the
incident cycle, reopened** — bounded by the budget, but burning a full cycle forever on a
document that never converges.

This tempdoc previously recorded it as a residual "presence, not emptiness" limitation. That
framing was wrong: it was a live defect, not a narrowed scope.

Fixed in two ordered halves. Writers first: `SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY` on the
existing VDU/NER precedent, chosen because it is terminal for the pending queries *and* falls to
`applyResetStatus`'s else-branch, which preserves status and retry counter instead of resetting.
Then the contract: `FieldMapper.wouldMaterialize(fieldId, value)` expresses the same predicate
`addFields` uses, so the semantics live in one place, and the contract delegates to it.

The reader sweep caught a would-be regression: `IndexCountOps`' completed count feeds
`spladeCoveragePercent`, and the jseval readiness gate requires ≥ 99.9% — counting only
`COMPLETED` would have parked coverage below the bar permanently once pending drained, stalling
the very harness used to verify ingest. Same class as the `IndexStatusOps` catch during the NER
change; found twice now by sweeping rather than assuming.

### Also fixed

- **F2** — `progressed()` now counts blank-content escalations, matching its own javadoc.
  Deliberately scoped to the *terminal* step, not every retry bump: counting intermediate bumps
  would make `progressed ⟺ wroteAnything` (a non-empty update map is the only way `written > 0`)
  and collapse the exact distinction this fix introduced.
- **A second defect found while fixing F2:** the embed batch null/size-mismatch branch did
  `embedFailed += embedDocIds.size()` while writing **no update at all** — a false progress
  signal that would spin the tight loop against a systematically failing encoder until the
  budget. Split into a separate counter excluded from progress.
- **F3** — the budget diagnostic now WARNs only on zero cumulative progress. It previously
  asserted "the same documents are rewritten every batch without any stage advancing" while
  firing during genuine enrichment, training operators to discount the one diagnostic this work
  added.
- **F4** — the disambiguation change-detector deliberately **excludes** `COMPLETED_EMPTY` (a doc
  with zero entities contributes nothing to the entity graph). This is the opposite call from
  `IndexCountOps`, which must sum both tokens; the comment names the contrast so the next reader
  does not "fix" one to match the other.
- **F5** — the reported null-throw could not actually occur (`spladeStatusFor(null)` returns
  `COMPLETED_EMPTY`); the narrower real hazard — a null value under the `splade` key making
  `applyRmwPolicies` treat the field as caller-supplied and skip preservation — is guarded.
- **F6** — the witness map is now asserted against the **real** `SSOT/catalogs/fields.v1.json`,
  not a hand-mirrored test catalog, so the contract cannot go partially vacuous unnoticed.
- **TQ1/TQ2** — the tight-loop test's timeout equalled `CYCLE_BUDGET_MS` exactly (a ~0-margin
  race with a false docstring), and the tests could be satisfied by the budget alone — i.e. they
  proved the backstop, not the mechanism. Now three tests each pin a distinct mechanism with
  measured separation (0.017 s progress-drive / 6.173 s budget / 0.009 s ingest-yield).

### Claims corrected (the record, not the code)

- **The live probe was overstated.** The log shows a 12.1 s idle gap, not the "2.1 s" reported,
  and 48 documents not 45. Substantively: it recorded **zero blank-content escalations**, so it
  never exercised the branch this fix rewrote. Honest statement: the two-batch property held
  live on the worktree build at 43-document scale, without exercising the escalation path.
- **T4 had never run.** It was reported green on a worker's word; only `GoldenCorpus` results
  existed. It has now genuinely executed (`--rerun`, after Gradle first served `FROM-CACHE`):
  `IngestStarvationE2ETest` — 1 test, 0 failures, 8.59 s.
- **`callerSuppliedRetry` is not this work's** — it came from `4e9a17fa fix(711)`. The mechanism
  was verified here; its provenance was not.
- Line numbers: the progress drive is `BackfillScheduler.java:191`; `:180` is the ingest break.
  Production validation mode is resolved by `RuntimeSession.java:374-376`, not
  `ValidationMode.from`.

### Residuals still open (deliberately)

- **Chunk docs escalating to `splade_status=FAILED` will not self-heal if chunk-SPLADE is later
  enabled** — `FAILED` is never resurrected by design; enabling that evidence-gated flag would
  need a reindex. The deeper fix is birth-status hygiene (chunk docs should not be born carrying
  a parent-lane `splade_status`), which 717 §TH-4 already named.
- **Presence-truthful counting covers only chunk-embedding.** Parent `embedding_status`,
  `splade_status` and the NER count remain status-field `TermQuery` counts, so this bug class
  stays invisible to health reporting on three of four lanes. Separable, but without it a
  regression here is undetectable again.
- **The integration tier runs in no CI lane** (`ci.yml` runs only `:test`; `check` depends on
  `test`). That blind spot is why the whole `IsolatedBackendFixture` tier had rotted to
  `ClassNotFoundException` unnoticed, and why T4 will not guard anything until a lane runs it.

# 798 — Round 7 release blockers

Sandbox validation round 7 (2026-07-30) was the first round against the post-772 payload
and the first ever to collect per-mode golden captures. It produced **DO-NOT-QUALIFY** on a
genuine product defect, plus eleven further findings. This tempdoc records the diagnosis and
the designed fix for each. Nothing here is implemented.

Round evidence: `tmp/sandbox-round7/share/evidence/` (102 files, 117 MB service logs,
40 golden captures). Candidate `JustSearch_0.2.0_x64-setup.exe`
sha256 `6817c917b6b83279b28c4efc3236472e11dded102e3b883b669e77293e45f2ce`, CI run 30544110298,
built from `c491aa61`, unsigned by design.

---

# D — Design (2026-07-30)

## D0. What the findings actually share

Seven of the twelve findings are one shape: **a recorded claim that nothing verifies against
the reality it describes.** `embedding_status=COMPLETED` with no vector (B1). 1,557 chunk docs
marked `ner_status=COMPLETED` though NER never ran (B1-related-2). A UI announcing
"reconnecting…" with no actuator (B4). A register asserting sandbox-tier reachability for
unreachable shapes, and a gate certifying it by matching a static table (B2). An ADR asserting
bundled models never built (B3). `qualityKnown=false` → "needs admin" without ever checking
elevation (B6-9). A parity gate asserting cross-environment comparability its own calibration
block says was never sampled (B7).

The other five (wrong nav constant, CSS floor, toast stacking, SSE dumper, and the layout half
of B6) are ordinary local bugs. Recording that matters: a pattern that swallowed all twelve
would be one to distrust.

**This repo already named this invariant and deliberately deferred building it.**
Tempdoc 717 §TH-6 (`docs/tempdocs/717-intermittent-fresh-build-chunk-death.md:311-326`):

> **Artifact-truthful readiness** — no readiness/completion signal for an enrichment may be
> derived from its `*_status` field alone when the payload is non-stored; it must be
> corroborated by the payload's actual presence, or by a write-time invariant that guarantees
> status⇔payload agreement at every commit.

717 called the shape "receipt vs. goods", noted it "would retroactively cover parent `vector`
and `splade`, not just `chunk_vector`", and left the scope question explicitly open —
*"whether to generalize now or fix chunk first and generalize later is a scope decision for
design — flagged, not settled."* Its §TH-5 menu listed the write-boundary contract as option 3
(*"Strongest, structural; touches the write path"*), and Decision 1 recorded the deferral in
the repo's standard form: *"recognize the principle, defer the structure until a real need
exists."*

**The real need has arrived.** B1 is that deferral materialising as a release blocker. This
design executes 717's option 3. It is not a new principle; it is the structure 717 declined to
build until something forced it.

## D1. Why the livelock is a seam defect, not a component defect

The RMW witness is **working correctly**. `preserve-reread-or-reset:embedding_status`
(`fields.v1.json:180`) detects a COMPLETED-with-no-vector lie and heals it to PENDING, exactly
as 717 designed. The writer manufactures the lie; the witness rejects it; the loop's
continue-condition counts *writes* rather than *stage advances*, so two correct mechanisms
fight at ~64 Hz forever.

This is why the fix must not weaken the reset lanes — they are the only thing preventing
vectors from silently vanishing (F-032 / 711 / 717), and relaxing them to stop the oscillation
is the textbook `fix-root-causes-not-symptoms` failure. The correct move is to **invert the
repair direction**: today the reader repairs the writer's lie after the fact; under this design
the lie cannot be written, and the reset lanes become defense-in-depth backstops rather than
combatants.

## D2. The design — a write-time witness contract

### D2a. Make the status↔artifact relationship first-class

Today that relationship exists **only** as a substring inside the artifact field's policy
string (`preserve-reread-or-reset:embedding_status`) — one-directional (artifact → status),
consumed only at RMW time. Three of six status fields have no artifact linkage at all:
`ner_status`, `vdu_status`, `extraction_status` (`fields.v1.json:229,366,475`).

Each `*_status` field declares in `SSOT/catalogs/fields.v1.json` what witnesses it. Three
witness kinds, deliberately mirroring the shape of `governance/declaration-kinds.v1.json`'s
existing `witnessChannel` taxonomy rather than inventing a parallel vocabulary:

- **`artifact:<field>`** — the stage must produce output; the witness is that field's presence.
  (`embedding_status`→`vector`, `chunk_embedding_status`→`chunk_vector`,
  `splade_status`→`splade`.)
- **`provenance:<field>`** — the stage may legitimately produce nothing, so presence cannot
  witness it. A document with no named entities is validly NER-complete with zero entity
  fields. The witness must therefore be an explicit stamp that the stage *ran* (the processing
  model/version identity, or a run marker). **This is the half 717's formulation did not
  cover** — it addressed only non-stored payload fields — and it is precisely why the 1,557
  phantom NER completions are invisible today: nothing records that NER ran.
- **`none`** — explicitly unwitnessable, with a stated reason in the catalog (mirrors
  `INLINE_ONLY` in `declaration-kinds.v1.json`). An escape hatch that must be *argued*, not
  defaulted into.

This extends the existing catalog rather than forking a second authority for field metadata —
the projection-vs-fork discipline (`execution-surfaces.v1.json`, tempdoc 553) applied to
schema. Startup validation already sets the precedent: `FieldMapper.validateRmwPolicies`
(`FieldMapper.java:195-261`) fail-fasts when a fragile field omits its policy and structurally
verifies the named target exists and is `docValues`. Witness declarations validate the same
way, at the same place.

### D2b. Enforce at `IndexingCoordinator`, not `WritePathOps`

**`rmwPolicy` fires only on the RMW/partial-update lane.** Full-document writes
(`indexSingle`/`indexBatch`, `WritePathOps.java:72-136`) go straight to
`IndexWriter.updateDocument` with no policy dispatch, by design (the caller is authoritative
for every field it supplies). So a full write carrying `status=COMPLETED` without its artifact
is today entirely unguarded — a second, wider hole than the one B1 exercised.

The enforcement point is therefore `IndexingCoordinator.validate()`
(`IndexingCoordinator.java:200-264`), which already validates `id`/`doc_uid` presence and
vector dimensionality, and which is the **sole** Lucene mutation entry point (one
`ReentrantLock dispatchLock`, tempdoc 402). Placing the contract there covers both lanes;
placing it in `WritePathOps` would cover only one.

**The invariant:** a write that sets `<status>=COMPLETED` must, in the same durable commit,
carry its declared witness — or, on the RMW lane, preserve an existing one. A write that
cannot is rejected, and the writer must use the retry/`FAILED` escalation the sibling branch
already uses (`EmbeddingBackfillOps.computeEmbeddingFailureUpdate` and siblings). `FAILED` is
terminal for the pending queries, so the document leaves the backfill population permanently
instead of oscillating in it.

### D2c. Containment — loops terminate on progress, not activity

D2a/D2b remove *this* non-converging input. They do not stop the *next* one from taking down
ingest, because the structural defect is independent and older (516): an unbounded loop whose
continue-condition is activity.

- `anyWorkDone` (`written > 0`) is retired **as a control signal** and demoted to logging. The
  loop condition becomes real progress — a stage advanced or permanently failed.
- **Ingest preemption conforms to the existing seam.** `BackfillScheduler`'s while-guard
  already carries two "yield to something more important" checks —
  `signalBus.isUserActive()` and `signalBus.shouldYieldGpuBackfill()`
  (`BackfillScheduler.java:156-157`). Pending ingest becomes a **third signal on the same
  bus**, not a bespoke supplier threaded through the constructor. Same seam, same shape.
- A hard wall-clock/iteration budget per `runIdleCycle()`, logging WARN when it trips. This
  defect produced zero diagnostics for 20 minutes; a budget makes any future instance
  self-reporting.

The invariant worth stating plainly: **primary indexing always beats background enrichment.**

### D2d. Bite proof is required, not optional

`scripts/sandbox/plant_defects.py` establishes the repo norm that *"every verification
mechanism in this repo already has to prove it catches a known-bad input before it is
trusted."* The witness contract ships with a test that writes `status=COMPLETED` without its
witness and asserts rejection — wiring alone is not evidence. This is the same standard
`check-live-witness.mjs` holds itself to when it distinguishes "the teeth are wired" from "the
invariant is asserted".

## D3. What this design orphans (deletion belongs to this tempdoc, not a later sweep)

1. **`CombinedEnrichmentBackfillOps.java:341-371`** — the blank-content branch that defaults an
   absent status to PENDING and stamps COMPLETED. Deleted, not guarded.
2. **The churn-dodging rationale at `:350-355`.** Its comment explains that it carries a fresh
   splade encode in the same bundled write specifically to "skip that churn cycle". Once
   writers cannot manufacture claims, that rationale is false authority and must be rewritten
   or removed. The *encode* stays; the `else if (spladePending) → COMPLETED` fallback at
   `:366-367` goes.
3. **`anyWorkDone` as a control signal** — renamed/demoted so it cannot be reused as a loop
   condition by the next author.
4. **A data orphan, and the one this design must not defer.** The write contract prevents new
   lies; it does not repair existing ones. Shipped indices carry documents claiming enrichment
   that never happened (1,557 in the round-7 corpus alone, all invisible to the coverage
   denominators, which count non-chunk docs only —`LuceneRuntimeTypes.java:299-326`). The
   options are a one-time repair pass keyed on the new witness declarations, or a generation
   bump through the existing blue/green migration. **Deciding which belongs to this tempdoc**;
   leaving user indices in a knowingly-wrong state and calling the bug closed would be the
   `retire-with-a-sweep` failure repeated.
5. **717 §TH-5's option menu** — options 1/2/4/5 were alternatives to option 3. Once option 3
   ships they are decided, not open; 717 is dated history so it gets a pointer here, not a
   rewrite.

## D4. Scope discipline — what this design deliberately does NOT build

- **No general witness framework spanning runtime, CI, and docs.** The seven claim-shaped
  findings live in four unrelated substrates (Lucene fields, TS UI state, JSON registers,
  markdown ADRs). One mechanism spanning those would be apparatus, not structure.
- **No new governance register.** `fields.v1.json` is already the authority for field metadata;
  a second register for witness declarations would be the fork this repo's own discipline
  forbids.
- **No change to the RMW reset lanes.** They are correct and load-bearing.
- **B2, B3, B5, B6, B7 get local fixes**, not witness declarations. They are instances of the
  principle; only the data plane gets structure, because only the data plane has multiple live
  instances and an existing half-built mechanism to complete.

## D5. Derisking corrections (2026-07-30, pre-implementation)

A confidence pass ran before any implementation. It **refuted one hypothesis and corrected two
design decisions**. The design above is superseded on these three points.

**C1 — The config-divergence hypothesis is dead.** `rag.chunk_splade.enabled` defaults to
`false` (`EnvRegistry.java:1073`, "evidence-gated", tempdoc 712) and is set nowhere — not in
`config/application.yaml`, not in the packaged `headless-config/application.yaml`, not in any
script, env, or MCP config. `chunkSpladeEnabled=false` in **both** dev and the installed
product, so both take the same mark-COMPLETED-without-data branch. The dev↔installed config
divergence is real but irrelevant here.

**C2 — `IndexingCoordinator.validate()` is the WRONG enforcement point.** It is invoked only
from `indexSingle` (`:272`) and `indexBatch` (`:305`) — the full-document lane. The RMW lane
(`updateDocument`/`updateDocumentsBatch` → `readModifyWrite`) **never calls it**. Enforcing
there would have left unchecked precisely the lane where the blocker occurs. The contract
needs **two points**: over the caller's field map in `indexSingle`/`indexBatch`, and over the
merged map in `WritePathOps.readModifyWrite` after `:306` and before `:309` — the only place
where the final status value and the final artifact disposition are both visible.

**C3 — Drop the `provenance:` witness kind; the repo already solved "ran fine, found
nothing".** Two live precedents use a **distinct status value** rather than a new field:
`SchemaFields.VDU_STATUS_COMPLETED_EMPTY` (`GrpcIngestService.java:697`) and
`ExtractionStatus.SUCCESS_EMPTY`, whose comment explicitly cites the VDU precedent for "the
same 'ran fine, found nothing' distinction". NER should follow that pattern. This removes the
new-schema-field requirement, and with it the migration cost that made the provenance design
expensive. `COMPLETED` then strictly means *artifact exists*, which is exactly what makes the
write contract checkable.

**Consequence — the contract is a pure map check, no index I/O.** With `COMPLETED` meaning
artifact-present, both enforcement points only inspect the field map. For `vector`, the
`preserve-reread-or-reset` policy has already placed the preserved value in the merged map.
For `splade`, `reset-status` exists precisely because omitting it destroys the postings, so
there is no legitimate "COMPLETED + splade absent from the map + postings survive" case. The
earlier concern about expensive write-time presence checks does not arise.

**Blast radius is small and bounded: 6 sites in 4 files.** Most writers already conform.
Confirmed violations of the same class, beyond the blocker's own site:
`SpladeBackfillOps.java:97-104`, `BgeM3BackfillOps.java:108-117` (hits two status families),
`NerBackfillOps.java:82-89`, plus `CombinedEnrichmentBackfillOps.java:341-368`.
`EmbeddingBackfillOps` already treats the identical blank-content case as a *failure* — the
inconsistency between siblings is evidence the COMPLETED-stamping was never deliberate.

**The data orphan self-heals, but only on migration.** Blue/green migration is a full
filesystem re-crawl that rebuilds documents through the conforming ingest path
(`KnowledgeServerMigrationOps`, `docs/explanation/11-index-schema-migration.md`), so phantom
statuses die there. They do **not** heal on restart or ordinary backfill, because backfill
queries only touch `PENDING` documents and never re-verify a `COMPLETED` one. So D3 item 4's
decision reduces to: accept-and-document, or let the catalog change trigger a migration.

**Newly surfaced scope question (not yet decided).** Only chunk-embedding has
artifact-truthful counting (`IndexCountOps.queryChunkVectorPresenceCount`, wired into
`IndexStatusOps.buildEnrichment`). Parent `embedding_status`, `splade_status`, and the NER
count are still pure status-field `TermQuery` counts — so this bug class stays invisible to
health reporting on three of four lanes. Extending presence-truthful counting is separable
work, but without it a regression here is undetectable again.

---

# D6 — Design for the remaining work (B2, B3, B5, B6)

Written after B1, B4 and B7 shipped. The remaining eight findings are not eight unrelated
bugs; **five of them are the same defect as B1, one plane over.**

## D6.0 The shape

B1 was the **data plane**: a status field claiming an artifact that did not exist. The
governance registers are the **control plane**: a declaration claiming a consumer. These five
are the **presentation plane** — claims made to a human — and they share a sharper, more
actionable form than "unwitnessed claim":

> **Prose hardcoded next to the data that would have made it true.**

- B5: the consent dialog says *"several GB"* as a string constant while
  `planPreview.totalDownloadBytes` — the exact figure, 10.14 GB — is already in the same
  component's state, and renders on the very next screen. It says *"you must accept the
  upstream model terms"* while every package on the wire already carries `termsUrl` and a
  SPDX `license`.
- B6-9: *"Unknown — needs admin"* is reached because `qualityKnown` is a hardcoded `false`,
  while process elevation is never actually checked — so the remedy named is one the system
  has no basis to recommend.
- B6-5: *"Unlock in Settings"* points one hop short. `SettingsSurface` itself navigates to
  `core.security-surface` for the unlock, so the remedy sends a user to a surface whose own
  answer is "go somewhere else."
- B6-6: *"slow request"* is asserted about a connection that is a long-lived stream by
  design — the label describes a latency event that did not occur.
- B3: an ADR states the installer bundles ONNX models, while no built artifact has ever
  contained them.

When the message is a constant and the truth is a variable, they drift — and the drift is
invisible because **prose does not compile**.

**The witness for a presentation claim: it must be derived from the state that makes it true,
rather than authored beside it.** "Derive, don't author" is the whole design.

## D6.1 Applying it (B5, B6-5, B6-6, B6-9, B3)

Each fix is the same move — replace an authored assertion with a derived one — and the
scope of each follows from how far the derivation reaches:

- **B5** is the largest because the dialog must be *composed from* the manifest and plan
  preview it already holds, not merely have its numbers corrected: real package names, real
  licences, a clickable `termsUrl`, and the true byte total. `ConfirmDialog` renders `message`
  as a single plain-text paragraph, so it needs to accept structured content for a link to be
  a link. The cancel path additionally needs to *state its own consequence*, which is itself a
  derived claim: the bytes about to be discarded are known.
- **B6-9** needs a distinct state for "there is nothing here to encrypt" so that the wording
  can be conditioned on a real distinction instead of a blanket constant. Today PKEY 4
  (*NotApplicable*) and PKEY 3 (*indeterminate*) collapse into one bucket, which is what makes
  an honest message impossible to write.
- **B6-5, B6-6, B3** are each a single derivation corrected at its source.

**Deliberately not built:** a general mechanism binding every user-facing string to its data.
The reason-code registers already do exactly that for *reason vocabularies*
(`readiness-reason-codes`, `search-degradation-reason-codes`), and their gates enforce
producer↔consumer correspondence. But they witness **"is this code worded?"**, not **"does the
remedy this wording names actually work?"** — which is precisely why B6-5 was invisible to a
green gate. Extending them to witness remedy *resolvability* is the natural next step and is
**recorded here, not built**: one instance is not evidence of a class, and a static check
could only prove the target is a registered surface — `core.settings-surface` *is* registered,
so it would not have caught this bug. The honest, proportionate witness for one instance is a
test that pins the remedy to the surface owning the capability.

## D6.2 A different shape: precedence (B6-7, B6-8)

The two layout findings are **not** instances of the above. They share their own shape, and
it is one this tempdoc has already fixed once:

> **Primary content must not be starved by secondary chrome.**

- B6-7: `.conversation` — the primary reading column — is the **only** grid track given an
  explicit `0` floor, so it absorbs all shortfall while its `fit-content` siblings hold their
  width. The most important element has the weakest claim on space. Correct design: the
  primary column carries the strongest floor, and the content-adaptive siblings yield.
- B6-8: a fixed overlay slot with an unbounded vertical stack versus a surface header with no
  reserved space and no stacking protection. Two defensible repairs exist — armour the header,
  or bound the stack. **Bound the stack** (there is a `capWithOverflow` precedent in the
  codebase): armouring headers fixes the symptom at every future header, whereas bounding the
  producer fixes the cause once. Separately, a toast whose only dismissal is "click the toast"
  needs a visible control; persistence until acknowledged is correct, undiscoverable dismissal
  is not.

This is the same invariant as B1's containment fix, where **primary indexing always beats
background enrichment** — there, a background loop starved the foreground; here, secondary
chrome starves primary content.

## D6.3 B2 — honest exemption, and what it costs

The owner decision is exempt-now, wire-later. The design point is that the exemption must be
**honest rather than silencing**: the register's `reach` field should record the truth — no
shipped entry point — in the same form `core.memory-surface` already uses to record a real
path, rather than leaving `unknown` and an assertion of sandbox-tier coverage that no round
can satisfy.

What must *not* happen, and is recorded so a future reader does not mistake it for an option:
renaming evidence files to satisfy the token match, or stamping a shape-id purely to flip the
flag. Both would make a feature that no user can reach look covered.

The deeper finding stays open and stated: `check-intent-tier-coverage` is **green for two
unreachable features**, because it regex-matches a static table and never asks whether
anything exercises it. That is the same "symbol exists ≠ code path fires" trap as the
`wrong-gate` handle, now with two live instances.

## D6.4 What this design orphans

- The hardcoded consent-dialog strings, once the dialog composes from the manifest.
- `ConfirmDialog`'s plain-text-only `message` contract, if it gains structured content — the
  single-paragraph assumption must be removed, not left beside the new path.
- The conflated `UNKNOWN` disk-encryption bucket, once *NotApplicable* is distinct.
- The false ADR-0024 claim **and its provenance chain**: the still-open observation that
  originated it and tempdoc 657's "drift fix" that promoted it. Correcting the ADR while
  leaving the note that produced it would let the same claim be re-derived.
- B2's exemption rows are themselves an orphan-in-waiting: wiring the entry points later must
  remove them in that same change.

# R — Reach

## R1. The principle, and where it already lives

**A recorded claim must name what witnesses it.** "Witness" is this repo's own established
vocabulary — `governance/live-witness.v1.json`, the `runtime-witness` gate, ADR-0042,
`declaration-kinds.v1.json`'s `witnessChannel` taxonomy — with an existing bidirectional
failure vocabulary in `runtime-witness/enforcer.mjs:9-19`: **OVER-CLAIM** (a declaration the
delivery channel never carries) and **PHANTOM** (something delivered no declaration accounts
for). `embedding_status=COMPLETED` with no vector is an over-claim; the 1,557 NER completions
are phantoms. The naming already fits; nothing new is needed.

The repo has independently rediscovered this idea at least six times in the **control plane**:
`execution-surface` (a representation must derive from one canonical record), `runtime-state`,
`store-recoverability`, the reason-code registers, `observed-happening` (rule 9: a live concept
no surface renders is a build failure), and `live-witness`. The sandbox harness states it in
its own words — *"a filename is a claim; the pixels are the evidence"*
(`sandbox-CLAUDE.md:502`) — backed by measurement: the mechanical filename check credited
planted bad evidence 0 times out of 4, three blind human readers caught it 4/4 each.

**The gap this design fills: every one of those instruments is build-time or CI-time.**
`LiveWitness` is the only one that touches a running process, and only through a JUnit-composed
registry. **Nothing witnesses a data-plane claim** — no mechanism checks that a document's
enrichment status corresponds to the artifact it asserts. B1 is the first data-plane instance
to bite, which is why it surfaced as a release blocker rather than a red build.

## R2. Where else it applies (named, not built)

- **`vdu_status`, `extraction_status`** — same catalog, no artifact linkage today. Latent
  instances of the identical class; they get witness declarations for free under D2a.
- **B4's "reconnecting…"** — a transient UI claim with no witness. Same principle, different
  substrate; the local actuator fix is correct and no structure is warranted.
- **B2's register reachability rows** — a control-plane claim. The nearest existing instrument
  is `observed-happening`'s rule 9 reverse-coverage check; if B2 recurs, conform to that rather
  than build something new.
- **B7's overlap floor** — a claim about measurement validity, witnessed by a sampled
  calibration population that does not exist. The baseline's own calibration block already
  says so, which is the honest form of `none` with a stated reason.
- **`check-intent-tier-coverage`** — a live example of a gate that witnesses the wrong thing:
  it proves a mapping *exists* by regex-matching a static table, never that anything exercises
  it. Same trap as the `wrong-gate` postmortem handle.

## R1b. The presentation-plane form: "derive, don't author"

The remaining work (D6) showed the witness principle has a **third plane**, with a form
specific enough to be actionable on sight:

> A user-facing claim must be **derived from the state that makes it true**, not authored
> beside it. When the message is a constant and the truth is a variable, they drift silently,
> because prose does not compile.

Planes, now all three named: **data** (a status field claiming an artifact — B1), **control**
(a declaration claiming a consumer — the existing registers), **presentation** (a message
claiming a fact or a remedy — B5, B6-5, B6-6, B6-9, B3).

**Where it already exists:** the reason-code registers (`readiness-reason-codes`,
`search-degradation-reason-codes`) are this principle, gated — every producer code must be
worded, every wording must be a real code. They exist because an unworded code once rendered
a raw `Degraded: <code>` string to a user.

**Where existing code violates it:** those same gates witness *wording presence* but not
*remedy resolvability*, which is why B6-5 was invisible to a green gate. Also every hardcoded
size/consequence string sitting next to the state that would compute it — B5 is the measured
instance; the class is not audited.

**Earning its keep:** new user-facing messages cite state rather than restating it; validation
rounds stop finding "the UI says X while the API says Y" (round 7 produced at least four).
**Retire it if:** over the next few rounds the finding class does not recur, or the derivation
discipline starts producing messages that are accurate but unreadable — accuracy that costs
comprehension has missed the point of a user-facing string.

## R1c. Precedence: primary work must not be starved by secondary work

A second, smaller shape, and this tempdoc has now fixed it in two unrelated substrates:

> Primary work must not be starved by secondary work — in scheduling **or** in layout.

B1's containment fix asserts *primary indexing always beats background enrichment*; B6-7 and
B6-8 are the same invariant in CSS, where the primary reading column carries the weakest space
floor and an unbounded overlay stack occludes the header.

**Candidate scope:** any place a secondary producer can consume a shared, bounded resource —
thread time, screen space, GPU, the job queue. **Where existing code violates it:** unknown
beyond the three instances here; not audited.

**Earning its keep:** it predicts a defect before it is reported. **Retire it if:** it is only
ever applied retrospectively to defects already found by other means, which would make it a
description rather than a tool.

## R3. Earning its keep, and when to retire it

**Evidence it is working:** new status-bearing fields ship with a witness declaration by
default rather than by review; the `completedNerCount > docCount` class of inconsistency
disappears from status payloads; validation rounds stop finding status-without-artifact
defects; and the write contract's bite test catches at least one real regression before
merge over the next few candidates.

**Retire it if:** across roughly three validation rounds and normal development the witness
declarations catch nothing new while imposing schema churn or forcing `none:` escape hatches
that are never argued substantively. That pattern would mean the class was exhausted by the
local fixes and the declaration is now ceremony. A principle without a retirement condition
becomes self-justifying apparatus, and this one is deliberately scoped to a plane where two
real instances already exist — per `sandbox-defect-classes.v1.json`'s own rule that a class is
registered only after a real instance produces it, never speculatively.

---

## B1 — HIGH — Ingest livelock: the backfill tight loop starves the ingest poll

**This is the release blocker.** Not a "stall" — a **livelock**. The worker is at 100% of one
core doing work forever, which is why nothing reported unhealthy.

### Mechanism (verified against source, not only the audit)

`IndexingLoop.runLoop()` is the *only* caller of `jobQueue.pollPending(...)`
(`IndexingLoop.java:564`). On an empty poll it calls `backfillScheduler.runIdleCycle()`
(`IndexingLoop.java:596`), which enters an **unbounded** tight loop
(`BackfillScheduler.java:154-164`, re-read and confirmed): its only exits are shutdown,
`isUserActive`, and `shouldYieldGpuBackfill`. There is no iteration bound, no time budget,
no no-progress detector, and **no check for pending ingest jobs**.

The loop's continue-condition is `tightLoopOutcome.anyWorkDone()`, which is
`written > 0` — *"the batch RMW touched ≥1 Lucene doc"*
(`CombinedEnrichmentBackfillOps.java:629-633`, `:687`). **That is not a progress measure.**
Any document that is rewritten but never advances pins it `true` forever.

The non-terminating input is a status ping-pong on chunk documents:

- Chunk docs are written with **no** `embedding_status` and **no** `ner_status`
  (`ChunkDocumentWriter.java:114-180`).
- The blank-content branch defaults an **absent** status to `PENDING`
  (`CombinedEnrichmentBackfillOps.java:334-339`, confirmed by direct read) and then stamps
  `COMPLETED` with no artifact ever produced (`:343-348`, and `:366-367` for splade).
- The tempdoc-717/711 RMW preservation lanes correctly detect those lies and re-arm them to
  `PENDING` (`WritePathOps.java:339-359` for `vector` via
  `fields.v1.json:180 "preserve-reread-or-reset:embedding_status"`; `:360-367` for `splade`
  via `fields.v1.json:433 "reset-status:splade_status"`).

Two mechanisms each correctly "repairing" the other's write, forever:

| pass | update map written | RMW side effect |
|---|---|---|
| A | `{embedding_status: COMPLETED}` | splade lane resets `splade_status` → PENDING |
| B | `{splade_status: COMPLETED}` | vector lane (null vector) resets `embedding_status` → PENDING |

**The code already knew about this class.** The comment at
`CombinedEnrichmentBackfillOps.java:350-355` explicitly describes the RMW churn cycle and
carries a fresh splade encode in the same bundled write to dodge it — but only for the
splade lane, and only when `chunkSpladeEnabled`. The embedding lane has no equivalent
mitigation. That partial fix is why this survived: the known half was handled.

### Log corroboration (`evidence/logs/worker.log{,.1,.2}`, all `thread_name: indexing-loop`)

Last healthy pass `17:22:19.209`; from `17:22:57.654` onward the identical line repeats
~64×/second: `docs=2 (embed=0,splade=0,chunks=0) ... write=9ms(written=2)`. In the final 45 s
before shutdown: 2,897 `Combined backfill` lines and nothing else from that thread.
`worker.log.1` holds 59,420 such lines (17:29:10→17:42:51), `worker.log` 9,498 more. **Zero
WARN, zero ERROR across the entire window.** The `withNrtSuspended` bracket
(`BackfillScheduler.java:152-168`) is entered once and not left until shutdown, proving a
single uninterrupted execution.

Occurrences 1 and 2 are not two bugs — they are the two moments an ingest happened to land
during one continuous livelock that began at ~17:22 and never ended.

### Residual uncertainty (honest)

Which two documents oscillate is not observable at INFO level. Two cheap settling reads:
run `WritePathOps` at DEBUG (`:315` prints doc IDs — a `chunk-` UUID confirms), or query the
index for `is_chunk=true AND (embedding_status=PENDING OR splade_status=PENDING)` and sample
twice; under the ping-pong exactly one term matches and it alternates. **Do this before
implementing**, per `audit-without-test`.

Refuted alternative: chunk-blank-content retry escalation would terminate at
`EMBEDDING_MAX_RETRIES` and emit `WARN "Chunk embedding permanently FAILED"` — zero WARN
lines exist across 13 minutes and 59,420 iterations.

### Regression window

The second half of the ping-pong shipped `2026-07-12` in `d37578a8` (717), which changed
`vector`'s policy to `preserve-reread-or-reset:embedding_status`. Before it, `embedding_status`
was never re-armed, so no cycle existed. `reset-status:splade_status` came from `4e9a17fa`
(711). The unbounded loop itself predates both (516) — the latent structural defect that
converts one stuck document into a dead ingest pipeline.

### Fix — both parts required

**A. Stop manufacturing `COMPLETED`-without-data** (`CombinedEnrichmentBackfillOps.java:334-371`)
1. `:334-339` — a missing `embedding_status`/`ner_status` on a chunk doc means *this stage
   does not apply*, not *it is pending*. Read the raw value; skip the stage when `null`.
   This alone breaks the cycle.
2. `:343-348`, `:366-368` — never write `COMPLETED` for a stage that produced no artifact.
   Use the retry/`FAILED` escalation the sibling branch already uses; `FAILED` is terminal
   for the pending queries so the doc leaves the backfill population permanently.

**Do NOT relax the `WritePathOps` reset lanes.** They are correct and are the only thing
preventing vectors from silently vanishing (F-032 / 717). Weakening them to stop the
symptom is precisely `fix-root-causes-not-symptoms`.

**B. The tight loop must not be able to starve ingest** (`BackfillScheduler.java:154-164`)
1. Drive `useCombinedRef[0]` from a real **progress** measure — add `progressed` to
   `CombinedOutcome` (`:110-127`, `:686-697`) as
   `embedProcessed + spladeProcessed + nerProcessed + singlePassProcessed + failures > 0`.
   Keep `written` for logging only.
2. **Preempt for ingest**: pass a `LongSupplier pendingReadyIngestJobs` into the constructor
   (`:80-105`; sole production site `IndexingLoop.java:392`, which already holds `jobQueue`)
   and break when `> 0`. Primary indexing must always beat background enrichment.
3. **Hard budget** — wall-clock (~5 s) or iteration cap per `runIdleCycle()`, logging WARN
   when it trips. This defect produced zero diagnostics for 20 minutes.

### Required tests (fix is not complete without these)

- **T1** `BackfillSchedulerTightLoopTest` (worker-services, unit): stub the pathological
  state (same 2 doc IDs forever, `updatedCount=2`, all stage counts 0); assert inside
  `assertTimeoutPreemptively(5s)` that `runIdleCycle()` **returns**. Fails today by hanging.
  Second case: `pendingReadyIngestJobs=2` → returns after ≤1 batch.
- **T2** `IndexingLoopTest` (unit): with backfill in the pathological state, enqueue 2 jobs
  and assert `pollPending` is invoked and jobs claimed within ≤5 s.
- **T3** real-Lucene test next to `RmwFieldPreservationTest`: build a chunk doc exactly as
  `ChunkDocumentWriter.java:114-180` writes one, loop `processCombinedBackfill`, assert
  convergence within ≤3 passes and that no `embedding_status=COMPLETED` exists on a doc with
  no `vector`. Fails today (never converges).
- **T4** live-stack (`modules/system-tests`): ingest batch A, wait for drain **and** the
  enrichment tail to idle, then ingest batch B **in the same worker lifetime**; assert
  `processingJobsCount > 0` and `indexedDocuments` increases. **A single-batch test passes
  against this defect and must not be accepted as coverage.**

### Related defects found in the same path (report-only, not to fix here)

1. **`enqueue` unconditionally resets job state** — `SqliteJobQueue.java:273-276`
   (`INSERT OR REPLACE ... 'PENDING', 0`). The 60 s `syncDirectory` sweep rewrites every
   matching row, stealing in-flight `PROCESSING` claims and resurrecting `FAILED` poison
   pills, defeating tempdoc-700 escalation. Observed firing 11× consecutively.
2. **Phantom NER completions** — `completedNerCount 6748` vs `docCount 5191`
   (`evidence/api-api-knowledge-status.json`): 1,557 chunk docs marked `ner_status=COMPLETED`
   though NER never ran (`:346-348` + `:591-593` skips blank content). Same "status lies" class.
3. **The livelock is unobservable by construction** — `isRunning()` true, loop state
   RUNNING/IDLE, `/api/knowledge/status` `healthy:true` at 100% coverage (chunk docs are
   excluded from both coverage denominators, `LuceneRuntimeTypes.java:299-326`,
   `IndexStatusOps.java:244-259`). A "N consecutive zero-progress backfill batches" WARN
   would have made this a one-minute diagnosis.
4. **Breath-hold skips polling** — `IndexingLoop.java:556-561` `continue`s before
   `pollPending` when `isUserActive`, so the three tight-loop exits visible in `worker.log.1`
   still drained nothing.

---

## B2 — HIGH (product) — Two shipped conversation shapes are unreachable by any user

`core.workflow-run` and `core.free-chat` are declared in `CoreConversationShapeCatalog.java:39-46`,
have real backend implementations (`WorkflowRunShape`, `WorkflowShapeRunner`,
`CoreWorkflowCatalog` ships `researchBrief` + `demoCompose`), and are asserted as sandbox-tier
coverage in `governance/sandbox-coverage.v1.json:54,57`. Neither is reachable.

**`core.workflow-run` — every path closed.** The workflow trigger renders only when
`workflowPending===true` (`UnifiedChatView.ts:3453-3488`), set only when
`shapeId === 'core.workflow-run'` (`:785-788`), stamped only by `jf-chat-shape-mount`
(`ChatShapeMount.ts:122`) — which the unified chat surface **deliberately bypasses** because
it is a multi-shape host (`CorePlugin.ts:107-121`, with its own comment explaining why).
No deep-link substitute is possible either: `buildRequestBody()` has no case for this shape
(`unifiedChatRequest.ts:68-96`), so it omits `workflowId`, and the backend correctly rejects
that with BAD_REQUEST (`WorkflowShapeRunner.java:395-403`).

**Provenance: a `retire-with-a-sweep` failure.** `CorePlugin.ts:103-106` records that tempdoc
565 §15.C retired the standalone workflow surface, declaring the run would become "a MODE of
the one interaction window" and making a second visible workflow surface a build failure. The
old surface was swept; **the replacement was never wired to a live trigger.**

**`core.free-chat` — reachable only via an unsurfaced deep link.** The docstring states the
intent (`UnifiedChatView.ts:8`, "Default (no affordance) → FreeChat"), but `deriveAffordance()`
never returns `'none'` from its own logic (`agencyPosture.ts:95-99`) and the production Ask
trigger hardcodes `route:'ask'` (`UnifiedChatView.ts:2577-2588`) which always yields
`'documents'`. The only production site setting `'none'` is `restoreRecentConversation()`
(`:1891-1899`) — which needs a free-chat conversation to already exist, and nothing creates
one. `justsearch://answer?q=…&shape=core.free-chat` does work (`router/parser.ts:111-122`)
but nothing surfaces it to a human.

**A gate certifies this as covered.** `check-intent-tier-coverage.mjs` is green for both,
because it only regex-matches a static table against the Java `Set.of(...)`. It verifies the
mapping exists; it never asks whether anything exercises it — the same "symbol exists ≠ code
path fires" trap as the `wrong-gate` handle.

**Fix.** workflow-run: add a real discoverable entry point (composer-bar affordance or
command-palette entry that arms `workflowPending`); until it ships, mark the register row
`exempt` with the honest reason. free-chat: either fix `escalateAsk()` so an unscoped Ask
(no docs pinned, no schema) resolves to `'none'` as documented, or populate the register's
`reach` field with the deep link (as `core.memory-surface` already does) instead of `unknown`.
**Explicitly rejected**: renaming evidence files to satisfy the token match, or stamping a
shape-id purely to flip the flag — both hide a feature that is genuinely unusable.

---

## B3 — MED — ADR-0024's ONNX-bundling claim was never true of any built installer

`docs/decisions/0024-app-packaging-nsis-per-user-download.md:14-25` (2026-07-02 update) states
the NSIS bundle "now includes the ONNX search-runtime models (≈3.5 GB) … a fresh install does
full hybrid neural retrieval offline with no post-install download." Round 7 measured zero
`.onnx` bytes anywhere and all seven packages listed as download-on-demand (10.14 GB total).

**Verdict: documentation defect. The product is correct.** CI has set
`ORG_GRADLE_PROJECT_skipOnnxModels: "true"` unconditionally since before that ADR update was
written — present verbatim at `29579e51` (v0.1.0, 2026-06-25) — and
`modules/ui/build.gradle.kts:411` makes that force `includeOnnxModels=false`, so a CI-built
installer *cannot* contain ONNX models by construction. Bundling them would crash the packager
regardless: ~3.8-4.3 GB exceeds the ~2 GB 32-bit NSIS limit **the same ADR cites in its own
Context section** (`:31`, `:79-80`), a defect already documented in tempdoc 374 (G21,
2026-04-24).

**Provenance:** `docs/observations.md:413` (2026-07-01, still open) read the Gradle property
default in isolation and concluded the ADR was stale, without checking the CI override or 374.
Tempdoc 657 promoted that unverified note to a "Drift fix" the next day; it was copied into the
canonical ADR and survived 772's entire measurement campaign, whose own byte inventories
disproved it every time.

**Fix (docs only, no build change).** Correct/retract the ADR-0024 update block, stating
explicitly that the property default describes an *unbuildable local configuration* so the
misreading is not repeated; close the observation at `docs/observations.md:413`; flag 657's
claim as superseded. `docs/explanation/12-desktop-installer-and-sandbox-setup.md` and the
installer skill are already correct and need no change.

---

## B4 — MED — Tasks panel announces a reconnect that never happens

Highest-impact of the UX findings, and the standing `mustWatch:ui-api-truthfulness-under-load`
item. Measured simultaneously at 17:21:09: API `lifecycle=READY indexState=IDLE
pendingJobsCount=0 statusStale=false`; Tasks panel "5184 QUEUED" + "Live updates paused —
reconnecting…"; status bar in the same window "queue: 0". Persisted ~25 minutes.

**Mechanism.** The per-channel stall detector works — `isFeedStalled()`
(`indexingJobsBridge.ts:186-201`) fires after 45 s of channel silence and
`setFeedStalled()` (`:203-213`) flips a boolean rendered by `TaskList.ts:304-308`. **But there
is no actuator**: no code path calls back into `MultiplexedStream`/`EnvelopeStream` reconnect
logic when it flips. The message claims an action the codebase never performs. The physical
connection's own watchdog and backoff (`EnvelopeStream.ts:283-294`, `:318-332`, `:347-363`) are
correct but operate at *connection* level — and the connection is multiplexed, so frames for
other channels keep resetting it while `indexing-jobs` alone stays wedged. Only a full restart
recovers. The status bar disagrees because it polls REST independently.

**Fix.** Give the detector a real actuator. `MultiplexedStream` already has precedent for a
targeted reconnect (`scheduleLateSubscribeReconnect()`, `:167-176`) and per-channel resume
tokens exist, so add a debounced `nudgeReconnect()` and wire it to the tick at
`indexingJobsBridge.ts:393-398`.

**Test.** Extend `indexingJobsBridge.feedstall.test.ts` and `MultiplexedStream.test.ts`: feed
frames for a *different* streamId (keeping the physical connection alive) while withholding
`indexing-jobs`, and assert **the reconnect actuator fires** — not merely that the flag flips.
Asserting the cosmetic boolean is what let this ship. Plus a live-stack test driving 2+ ingest
batches and asserting Tasks-panel-vs-`/api/knowledge/status` convergence.

**Interaction with B1:** when the queue genuinely is stuck, the user cannot distinguish a real
stall from this stale panel — both render as a large frozen QUEUED number. B1 and B4 together
are why round 7 needed 25 minutes to state the ingest defect precisely.

---

## B5 — MED — Install AI consent dialog: undisclosed terms, wrong size, destructive cancel

`BrainSurface.ts:771-792`. The dialog is a hardcoded string saying "several GB" and "You must
accept the upstream model terms" — showing neither. Both are already available: every package
in `/api/ai/install/manifest` carries `termsUrl` and SPDX `license`
(`ModelPackage.java:42-54`), and the exact total is already in `this.planPreview.totalDownloadBytes`
before the dialog opens (`refreshAll()`, `:587-610`) — the same source that renders
"6.3 MB / 10.14 GB" on the very next screen. `startInstall()` simply never reads it.

**Cancel is worse than the round reported.** The round observed "cancelled at 458 MB, restarted
from 7 MB" and read it as a broken resume. **There is no resume at all**: every `startInstall()`
unconditionally deletes any pre-existing `.partial` (`AiInstallService.java:487`), and `cancel()`
calls `Remove-BitsTransfer` (`DownloadExecutor.java:208-222`), whose semantics delete the
destination. The "7 MB" was a fresh download already in progress.

**Fix.** (1) Presentational, no API change: build the dialog from the fetched manifest +
`planPreview` — real package names, licenses, clickable `termsUrl`, and the true byte total.
`ConfirmDialog.ts` renders `message` as one plain-text `<p>`, so it needs extending (or a
bespoke consent dialog) for real links. (2) Add a confirm step to `cancelInstall()` stating the
downloaded bytes will be discarded. (3) Separately scope true resume (stop deleting `.partial`;
`Suspend-BitsTransfer` or HTTP Range) — larger, touches `DownloadExecutor`'s core loop, **ask
before undertaking**.

Also noticed: the FE's `AiInstallManifest` types (`api/domains/packs.ts:28-45`) model a stale v1
`assets` shape without `license` — pre-existing drift.

---

## B6 — the small ones

| # | Sev | Defect | Fix | Test tier |
|---|-----|--------|-----|-----------|
| 5 | LOW | "Unlock in Settings" lands on a surface with no unlock control | one literal: `readinessNotice.ts:262` `core.settings-surface` → `core.security-surface` (the unlock lives in `SecuritySurface.ts:237-247`) | unit + ui-shot |
| 7 | MED | RAG answer column crushed to ~1 word/line | `unifiedChatRequest.ts:131-138`: `.conversation` is the **only** grid track with an explicit `0` floor, so it absorbs all shortfall while `fit-content` siblings hold. `minmax(0,50rem)` → `minmax(24rem,50rem)` | ui-shot + `.measure.json` width assertion |
| 8 | MED | Toasts persist 20+ min and occlude the header control row | `OverlayHost.ts:44-53` fixed slot vs `unifiedChatStyles.ts:148-156` `.header` with no z-index and no reserved space; unbounded vertical stack. Add a visible dismiss control to `AdvisoryToastHost`, and either make surface headers sticky/z-indexed or bound the overlay stack (`capWithOverflow` precedent in `TaskList.ts`) | ui-shot rect-overlap assertion |
| 6 | LOW | Long-lived SSE misreported as 60 s "slow requests" | `ApiSecurityFilters.java:316-356` measures exchange lifetime with no streaming exemption; skip when content-type is `text/event-stream` (all 16 SSE routes end `/stream`) | new host unit test (needs a small seam — `SlowRequestDumper.captureDump` is static final) |
| 9 | LOW | "Unknown — needs admin" is the wrong cause for disk encryption | `StatusLifecycleHandler.java:731` hardcodes `qualityKnown=false` unconditionally; `DiskEncryptionProbe.java:112-117` collapses PKEY 4 (*NotApplicable* — no encryptable volume) into the same `UNKNOWN` bucket as PKEY 3. Add a distinct state; condition the wording | host unit test via the existing `readMechanism` seam |

---

## B7 — Finding 5 (golden parity): now attributed, and it is not a product defect

Round 7's per-mode captures (the first ever collected) attribute all three failures to the
**dense leg** — q06 and q08 collapse to 4/10 on dense alone — while **SPLADE is 10/10 on every
failing query** and text is 9/10. This refutes the cross-encoder hypothesis; divergence enters
upstream in the embedding path.

Host-side measurement the same day: dev-vs-dev on one stack is **bit-identical**
(dense delta 0.000e+00, identical order, two runs), while dev-vs-sandbox diverges 1.7e-2–6.8e-2
on all ten queries against a 2.0e-4 envelope. All three failures are `kind: semantic`; all six
`kind: keyword` pass. **"Golden #1 in top-3" passes on all ten**, including the three failures.

The baseline's own calibration block states the overlap floors "were sampled ONLY on the
same-machine population — applying them cross-environment is exactly what this block exists to
make visible." The instrument documents its own limitation and finding 5 is that limitation
firing.

**Caveat against over-reading the SPLADE control:** sparsification quantizes away small
floating-point differences by construction, so SPLADE's stability does not by itself prove the
two runtimes are numerically identical.

**Owner decision required — do not "fix" by lowering the floor.** Either (a) calibrate the
envelope and overlap floors on a properly sampled cross-environment population (needs 2-3
rounds to build it), or (b) demote overlap@10 to descriptive and gate on the environment-robust
signal that is already green, as 750's A4 pre-designed. Quietly relaxing `MIN_OVERLAP` without
a sampled population is `fix-root-causes-not-symptoms`.

**Unconditional housekeeping:** commit a v2 baseline. Without one the checker silently degrades
to overlap-only and loses leg attribution — the v1 file in `scripts/sandbox/` is what made
rounds 5 and 6 unattributable. Round 7 used `tmp/finding5/golden-parity-v2-dev.json`.

---

## What round 7 proved GREEN (worth recording)

- Fresh install, first launch, WebView2 online bootstrap, indexing, search, chat/RAG, MCP
  (incl. the TYPED_CONFIRM ceremony for the mutating tool), restart cycles.
- **Uninstall, warm reinstall over existing data, and silent install `/S` all PASS** — the
  first empirical `/S` verification the project has (exit 0 in 23.3 s, no wizard, install dir
  and registry restored, user data preserved per ADR-0024).
- Post-restart search executes the full ladder — dense-retrieval, fusion, cross-encoder all
  `executed`, no silent BM25 collapse. The 734 A.1 recovery path is healthy.
- Presentation preference survived a cold restart *and* a full uninstall→reinstall.

## Round 8 outcome — this tempdoc's verdict (2026-07-31)

Sandbox round 8 ran against a candidate cut from merged `main` (`JustSearch_0.2.0_x64-setup.exe`,
259,859,565 bytes, hash-verified against its `SHA256SUMS`). It returned **QUALIFIABLE — no
blocking defect**. Full round record lives in tempdoc 734; only what this tempdoc's items were
judged on is recorded here.

**All eight PRs merged** (#339–#346), full suite genuinely re-run on merged `main` (33 modules,
6,940 tests, 0 failures — `cleanTest test`, not an UP-TO-DATE pass).

### Per-item verdict

| Item | Round-8 result |
|---|---|
| **B1** ingest livelock (HIGH) | **FIXED, verified — not inferred.** Three sequential ingests in a **single worker lifetime** (pid 4508 unchanged, checked before and after each), with folder A drained *and* its enrichment tail driven to idle (100 %/100 %/0-pending) before B was added. docCount 5 → 5189 → 5192 → 5193 → 5194; every marker searchable with the correct top hit. This is the test the charter demanded; a single-folder run would have passed against the old defect. |
| **B2** exempt shapes | Held. `core.free-chat` / `core.workflow-run` classified `exempt`, not blocking; no entry point found incidentally; no evidence file renamed to fake coverage. `check_coverage.py` exit 0. |
| **B3** ADR-0024 claim | No contradiction surfaced; per-user install at `%LOCALAPPDATA%` behaved as ADR-0024 states. |
| **B4** Tasks panel reconnect | **FIXED** — live `64 RUNNING / 1920 QUEUED` tracking real jobs, matching the status bar's `⚡64`. A **new** residual was found (734 F3: the panel's "QUEUED" and the status bar's "queue" share a label but not a definition). |
| **B5** consent dialog | **FIXED for the fresh-install case** — 7 real package names, real licences (Apache-2.0, AFL-3.0, LicenseRef-NVIDIA-CUDA-EULA), working Terms links, true byte total (10.14 GB stated = 10.14 GB shown). **Not fixed for the resume case** — see below. |
| **B6-7** RAG answer column | **FIXED** — normal multi-word wrapping with the preview open, no one-word-per-line collapse. A *different* layout defect was found in the same area (734 F5: clearing results with the preview open clips the composer below the viewport). |
| **B6-8** toast occlusion | **NOT fixed in practice.** 734 F4 reproduces it three times, including a toast hiding the **Add Folder** button while the empty state instructs the user to click it. Toasts also do not auto-dismiss — the same two were still covering the header ~6 minutes and several navigations later. |
| **B6-5** "Unlock in Settings" | Not exercised — no locked state arose. Carry forward. |
| **B6-9** disk-encryption wording | **NOT fixed.** 734 F1: the card still reads "Unknown — needs admin" on a machine with nothing encryptable, in a session that already held admin. |
| **B7** parity policy | **Worked as designed.** 10/10 on the blocking assertion (golden #1 at rank 1 in every case, not merely within top-3); overlap reported in full as descriptive. The demotion did not hide anything — it moved the number from deciding to describing. |
| Cancel → resume (PR C) | **FIXED and live-validated for the first time.** Pause retained 1199.7 MB; resume skipped completed files, recomputed 10.14 GB → 9.08 GB, and said so explicitly. The *backend* mechanism works; its disclosure does not — see F9. |

### B7 closed: finding 5 is attributed

Round 8 is the first round whose per-leg captures could be compared against a v2 baseline, and
the answer is unambiguous. Divergence is **entirely in the dense leg** — q06 dense 5/10 against
splade 10/10 and text 10/10; q08 dense 4/10 against splade 10/10 and text 10/10 — and the
dense-score identity check flags **all ten** queries as systematically out of envelope: deltas of
1.7e-2–6.8e-2 against a sandbox↔sandbox envelope of 1.8e-4, roughly 200–400× larger, with
byte-identical weights (fingerprint `f1d0f4ec…cc38e`).

Same weights, different numbers ⇒ the **embedding inference path** differs between the dev stack
and the Sandbox. This refutes both hypotheses previously on the table (HNSW/approximate tail
churn, and FP16-vs-FP32), each of which had already been independently refuted by a calibration
population. Finding 5 is an environment-level measurement artefact, not a ranking regression —
which is what §B7 argued on weaker evidence and what round 8 now demonstrates.

One correction to the round's self-report: it predicted q04/q06/q08 below floor. The host re-run
puts **q04 at exactly 7/10, which passes**. Two descriptive findings, not three.

### F9 — found host-side, and it is B5's other half

Reviewing the round's own screenshots surfaced a finding the round did not file, in the same
defect family as its F2:

1. `15-brain-install-cancel-confirm.png` — the confirm dialog promises "Everything already
   downloaded stays on disk and the next install resumes from where it stopped instead of
   starting over."
2. `16-brain-install-paused.png`, taken immediately after with **~1.2 GB retained on disk** — the
   Brain surface reads "**Not Installed** — Install AI models to get started" above a bare
   "Install AI" button. Nothing acknowledges the retained bytes or the paused state.
3. `17-brain-install-consent-resume.png` — the consent dialog re-states the **full 10.14 GB**
   (this is the round's F2).
4. `18-brain-install-progress-after-resume.png` — the truth finally appears: 9.08 GB, "Resumed
   from your earlier download." **After** the user has already consented.

F2 and F9 are one defect with one fix: **the pre-download surfaces do not read the resume state
the backend already holds.** F9 is arguably the worse half — a user who pauses and returns the
next day has no signal their 1.2 GB survived, and the surface actively implies otherwise. This is
the same "prose authored next to the data that would have made it true" shape §D6 names, landing
in the one place §D6's own fix did not reach.

### What this says about the design

§D6's thesis — five findings were one defect, *prose hardcoded next to the data that would have
made it true* — held up, with a qualification worth recording. The **backend** halves converged
cleanly (resume works, the manifest composes, the contract rejects unwitnessed claims). The
**presentation** halves converged only where a fix was written: F1, F4 and F9 are all the same
defect in surfaces the design named but the implementation did not reach, and F4's toast bounding
is a fix that shipped and still does not hold in a real window. That is evidence for the
principle and against assuming a principle's articulation propagates to every instance — each
site needs its own fix and its own measured assertion.

---

# T — Theorization after round 8 (2026-07-31)

Written after the verdict, not before it, so this section is reflection on evidence rather than
prediction. Nothing here is a settled design or licensed work; the forward vehicle is tempdoc 801.

## T1. The one contrast worth keeping: mechanism-scoped vs instance-scoped fixes

This tempdoc shipped both kinds of fix, in the same PR series, validated by the same round. The
results diverge cleanly enough to be worth stating as a claim rather than an impression.

**B1 was fixed at the mechanism.** The livelock was observed at one call site, but the fix went to
the write lanes — a contract that rejects a status claim no artifact backs, at *both* lanes,
regardless of which caller made the claim. Round 8 exercised paths that call site never sees (UI
add-folder, `POST /api/knowledge/ingest`, MCP `justsearch_ingest` behind a TYPED_CONFIRM
approval), and all of them held.

**B6-8 was fixed at the instance.** The toast occlusion was observed on the chat surface; the dock
was adjusted to clear the chat surface's header band, and the measured assertion was registered
against the `chat-occlusion` ui-shot step. Round 8 reproduced the same defect three times on
surfaces that step does not capture (Library, and after an MCP approval), including a toast
covering the **Add Folder** button while the empty state instructed the user to click it.

The assertion is not wrong and it is not weak — `.toast` `mustNotOverlapSelector: ".header"` is a
real geometric check on real captured geometry. It is *scoped to the observation*. The overlay it
guards is docked globally and floats above every surface, so the check certifies one surface out
of fourteen and is silent about the rest. The baseline file's own `occlusionNote` says so
honestly ("not solved at the slot") — the disclosure was correct, and round 8 is that disclosure
firing.

**Candidate principle: an assertion inherits the scope of the mechanism it guards, not the scope
of the observation that motivated it.** Where the two differ, the gap is exactly the set of
untested instances, and it is silent by construction — every existing test passes.

This is not the same claim as "test more surfaces." It is a claim about *where a check is
anchored*: to a rendering step (an instance) or to the docked overlay's own contract (the
mechanism). Anchoring to the mechanism here would mean asserting a property of the overlay host —
that its rect never intersects any surface's header band — evaluated wherever the host mounts,
rather than at one named capture. The same restatement applies to F1 and F9 below.

Where else this shape may already exist, named but not investigated: any ui-shot assertion on a
globally-mounted component (rail, status bar, command palette, plugin-error overlay); any check
registered per-step for something that is not per-step. Worth a survey before treating it as
general — one contrast is a hypothesis, not a pattern.

## T2. "Derive, don't author" is insufficient as stated — round 8 supplies two counter-examples

§R1b claims presentation defects come from prose authored next to the data that would have made it
true. Round 8 confirms the shape and finds two defects the principle **as written would pass**:

- **F7 (skin swatches)** derives. It just derives from the wrong subject: every card's swatch
  reads the *active* skin's accent rather than the accent of the skin that card represents, so
  applying Violet turns all nine swatches violet. Nothing is hardcoded; the gallery still cannot
  preview anything.
- **F3 (Tasks panel)** was read here as a *label collision* — two components naming different
  quantities with the same word. **That reading is withdrawn; tempdoc 801 §D0/§D6 refutes it on
  evidence.** Both figures describe the same worker `jobs` table: the status bar via a live
  `COUNT(*)` over `PENDING`+`PROCESSING`, the panel via a per-row SSE projection through a
  head-side in-memory mirror that is repaired only when a frame arrives. The words agree; the two
  derivations drift. The enrichment-backlog hypothesis in round 8's own write-up is refuted too —
  nothing in the tasks path reads `status.embedding`.

So the principle needs one clause, not two: **derive from the subject you are describing.** F7
reads global applied-theme state instead of its card's own skin; F3 reads a stale mirror instead of
the table. Tempdoc 801 §D0 sharpens this further and supersedes §R1b outright — the invariant is
that *a claim must be computed from the thing it claims about*, of which authoring prose is merely
the degenerate case.

The label-collision idea is recorded here only as a hypothesis that did not survive contact with
the mechanism. It cost nothing to hold and it is worth noting why it was attractive: two numbers
under one word is what the *screenshot* shows, and the vocabulary reading explains that surface
appearance perfectly while being wrong about the cause. A defect's appearance and its mechanism can
support entirely different stories.

## T3. The highest-yield defect channel this round was re-reading evidence, not capturing it

Of nine findings, **three came from reading captures that already existed**: F2 and F3 during the
mandatory evidence review, and F9 during the host-side review afterwards. Marginal capture cost:
zero. This is the second round in a row where the review gate found something live testing missed,
and it is direct evidence against streamlining it away.

**F9 sharpens the point: it is invisible in every individual frame.** The confirm dialog promising
that downloaded bytes are kept is honest. The "Not Installed" surface is technically honest. The
consent dialog's 10.14 GB is a true total. The contradiction exists only in the *sequence*
15 → 16 → 17 → 18. The review gate asks readers to examine images one at a time and judge whether
each one's pixels support its filename — a per-frame question, which cannot surface a per-sequence
defect. Nobody was asked to read the evidence as a narrative.

That suggests a cheap new instrument: a reading pass whose brief is *"read these captures in
timestamp order and name every place two consecutive frames contradict each other"* — a different
lens over the same bytes, not more bytes. It is a natural fit for parallel readers with distinct
lenses (per-frame honesty; sequence consistency; cross-surface vocabulary), which is also the
shape that would surface T2's label collisions.

The tension to respect: the retrospective measures this channel as the round's dominant token
cost (~70 full-resolution reads, ~70–80 k tokens), and the review gate *mandates* opening every
credit-eligible capture, which pulls directly against the crop-first advice. Adding lenses
multiplies that. The plausible resolutions — downscaled thumbnails for the sweep with
full-resolution only where a claim is in doubt, and sharding lenses across parallel readers rather
than stacking them on one — are cheap enough to try before concluding the channel is unaffordable.

## T4. A pre-registered expectation is an instrument, and can inject a false finding

The round-8 charter (written in this session) told the round that
`Combined backfill: docs=N (embed=0,splade=0,chunks=0)` repeating at high frequency *is* the
livelock and must not appear. It appeared 143 times, six of them inside 142 ms — and was not the
livelock: the lines carried `written=100`, the run terminated on its own, enrichment completed,
and a 60-second idle window produced zero new lines. A round following the charter literally would
have filed a false HIGH against a working build.

The charter encoded a **symptom signature** where it needed a **discriminator**. The signature is
emitted by healthy backfill too; what distinguishes the defect is *non-termination* — the
signature still firing while every coverage counter is static and ingest jobs are starved.

This is the mirror image of a lesson already recorded for measurement probes (a probe without
pre-registered validity rules can report its own leak as a win). The same discipline applies to
pre-registration itself: a charter watch-item needs a stated answer to *"what would this look like
if the build were healthy?"* before it is handed to a round. Cheap to add — one field per watch
item — and it prevents the expensive failure, which is not a missed defect but a fabricated one.

## T5. Staleness that only the next reader can detect will not be detected

Tempdoc 734 is the convergence document; `sandbox-CLAUDE.md` requires each round to read it to
learn which prior findings the round exists to re-confirm. It silently lost round 7 entirely.
Round 8 survived only because the charter happened to duplicate the content, and paid for it
anyway: the round-6 skins-swatch MEDIUM was rediscovered from scratch and still reproduces (F7).

The mechanism is worth naming beyond this instance. Updating the document is a host-side step
after a round ends; the party positioned to notice it was skipped is the *next* round — a
different agent, with no baseline for what should be there, reading the document precisely because
it does not already know its contents. **A document whose staleness is detectable only by a reader
who cannot detect it is structurally unmaintainable, however clear the instruction to maintain
it.** The remedy class is not diligence but a mechanical liveness check at the point of use — here,
refusing to stage a convergence tempdoc whose latest recorded round is older than the charter's
round number, which would have failed loudly at staging time and cost nothing.

Same family as retirement residue (tempdoc 742) and the prose-tier register's own meta-loop: a
document that claims authority needs something that fails when the claim goes stale.

## T6. What actually stands between "qualifiable" and "shipped" — and one gap the verdict does not cover

Round 8's verdict answers a narrower question than it appears to. It says *no blocking defect on a
clean machine*. It does not say the qualifying set is complete, and it is not.

**The harness's own round-mode policy requires at least one `upgrade-from-release` round in a
release's qualifying set** — install the previous public release, seed data, install the candidate
over it — on the recorded grounds that the strongest defect reproduction this harness ever produced
came from a non-fresh arrival state. For 0.2.0 that round has never run. Rounds 7 and 8 were both
`fresh-install`, and `v0.1.0` exists and is installable, so the round is possible, not vacuous.

The obvious objection is that there are no current users (owner decision 2 above), so nobody
actually arrives by upgrade. That objection does not reach the argument: the non-fresh round finds
defects because of *state migration over a pre-existing index, config and data directory*, not
because of who is holding the machine. A first release is also the last moment when the migration
path is cheap to get wrong invisibly.

Two further items belong on the same list, both currently unowned by any tempdoc:

- **Signing.** The certificate is in identity validation; the candidate is unsigned by design and
  round 8 was told to treat SmartScreen prompts as expected. A signed build is materially a
  different artifact and needs at least a reachability round, not an assumption of equivalence.
- **There is no auto-updater.** `tauri.conf.json` declares no updater configuration. This is worth
  deciding *before* the first real release rather than after: once an un-updatable build is in the
  field, every user on it must be reached by some other channel forever. The decision is cheapest
  now and irreversible in one direction only, which is the signature of a decision that should not
  default by omission.

None of these is a defect. They are the difference between "this build is sound" and "this release
is ready", and the round is not the instrument that answers the second.

## T7. Retirement conditions for the principles this tempdoc named

§R3 asked what would show the principles earning their keep. Round 8 supplies a first reading, and
the honest one is mixed:

- **The write-time witness contract (§D2) earned it.** It held across four ingest paths, three of
  which its author never exercised. If a future round finds a status claim unbacked by an artifact
  at a lane the contract covers, the principle is wrong and the contract should be replaced, not
  extended.
- **"Derive, don't author" (§R1b) is under-specified rather than earning or failing.** T2 gives it
  two clauses it lacks. Retire it if, after those clauses are added, presentation findings keep
  arriving that none of the three clauses would have caught — that would mean the frame is wrong,
  not incomplete.
- **Precedence (§R1c) has not been tested.** F5 (composer clipped below the viewport when results
  are cleared with the preview open) is a precedence failure in a surface the principle names, and
  it shipped after the principle was written. One instance is not a verdict, but the principle
  should be considered unsupported until a fix built on it survives a round.

## Suggested sequencing (not licensed)

1. **B1** alone — it is the blocker, it is deep, and it wants focused review + T4 live-stack.
   Settle the residual doc-identity question first (DEBUG read or index query).
2. **B4** alone — highest-stakes UX fix, wants live-stack verification.
3. **B6 cheap FE cluster** (5, 7, 8) — pure Lit/CSS, natural shared ui-shot additions.
4. **B6 backend cluster** (6, 9) — Java status/diagnostic accuracy, shared host tests.
5. **B5** standalone — the resume question may spawn its own charter.
6. **B3** docs-only, rides along with any of the above.
7. **B2** needs an owner call on scope: wire the entry points, or exempt the register rows
   honestly and schedule the feature work.
8. **B7** needs an owner decision before round 8 can be judged.
