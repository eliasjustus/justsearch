---
title: "Round 7 release blockers — artifact-truthful readiness at the write boundary"
status: "B1 IMPLEMENTED 2026-07-30 (branch worktree-798-ingest-livelock, not merged); B2-B7 designed, not implemented"
created: 2026-07-30
updated: 2026-07-30
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

### Residuals recorded, deliberately not fixed here

- **Chunk docs escalating to `splade_status=FAILED` will not self-heal if chunk-SPLADE is later
  enabled** — `FAILED` is never resurrected by design. Turning that evidence-gated flag on would
  need a reindex. The deeper fix is birth-status hygiene (chunk docs should not be born carrying
  a parent-lane `splade_status`), which 717 §TH-4 already named.
- **The contract checks artifact *presence*, not non-emptiness.** `splade_status=COMPLETED` with
  an empty weights map would pass. Tightening it requires the writers to guard emptiness first
  (`SpladeBackfillOps.java:198` and `CombinedEnrichmentBackfillOps.java:565` index into the
  result unguarded), or a legitimately-empty encoder result would throw and abort a whole batch
  of good writes — a worse failure than the one it would catch.
- **`progressed` under-reports blank-content escalations** (they advance documents without
  incrementing stage counters). Benign and in the safe direction: the tight loop exits, and
  `IndexingLoop` sleeps 100 ms then re-polls ingest every cycle, so nothing spins and the
  population still drains at roughly a batch per 100 ms.
- **Presence-truthful counting covers only chunk-embedding.** Parent `embedding_status`,
  `splade_status` and the NER count remain status-field `TermQuery` counts, so this bug class
  stays invisible to health reporting on three of four lanes. Separable, but without it a
  regression here is undetectable again.

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
