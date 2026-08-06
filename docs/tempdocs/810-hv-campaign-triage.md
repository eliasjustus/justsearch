---
title: "Human-validation campaign triage — routing, four serious-thread charters, owner-decision queue"
status: "open — routing recorded 2026-08-06; correctness + FE lanes delegated; T-A..T-D unstarted; three owner decisions pending"
created: 2026-08-06
updated: 2026-08-06
related: [734, 809, 798, 807, 805, 806, 800, 750, 593, 600, 565, 561]
---

# 810 — Human-validation campaign triage

## What this document is

On 2026-08-05 Sandbox **round 14** finalized clean (tempdoc 734, Round 14 section): coverage
26/26 exit 0, golden parity 10/10 on its blocking assertion, token health clean, no blocking
product defect. Immediately afterwards the owner ran a **manual validation pass** of the same
0.2.0 build and produced **15 findings** — none of them blocking, most of them things no
mechanical gate in this campaign was ever going to see. The findings are preserved verbatim in
**tempdoc 809**; round 14's own four findings (F1–F4) are in tempdoc 734.

This document is the triage record: where each finding goes, what work is big enough to need its
own design pass, what the owner still has to decide, and — the part worth keeping — why a
campaign this heavily instrumented did not surface any of them.

It is a routing document, not a design document. The four charters in §b state problems and
constraints; none of them contains a chosen solution.

---

## a. Routing table — finding → lane

Three lanes. A finding can appear in two lanes when part of it is a bounded fix and the rest is
structural; where that happens the split is stated explicitly rather than left to the reader.

### Lane 1 — correctness (delegated)

Defects where the product states or does something false. Each is bounded, has a named regression
home in its source finding, and does not wait on a design pass.

| Item | Source | What is wrong |
|---|---|---|
| `totalHits` ignores `pathPrefix`; `appliedFilters` never echoed | 809 finding 4 | `results[]` filters correctly but `totalHits` is computed from a different population — one measured case returned **more** hits filtered (187) than unfiltered (186). `appliedFilters` is absent from the response entirely despite `api-contract-map.md:445` specifying it is echoed when filters are active. Two independent misleading signals about whether filtering happened. |
| Locked-state chat dispatch accepted and dropped | 734 round-14 **F4** (as corrected by `qa-addendum.md`) | With chat history encrypted-and-locked, `POST /api/chat/dispatch` returns **200** and the question is discarded — not queued, not deferred. Verified against the persisted transcript: the two locked-state dispatches appear nowhere in it. The backend **already** answers **423 Locked** on the history path, so the honest signal exists and the dispatch path simply does not use it. |
| Enrichment not cancelled on root removal; cycle budget unenforceable | 809 finding 3 | Removing a root does not cancel in-flight enrichment — a full batch ran 63 s of GPU (43 s embed, 15 s NER) after the watcher was unregistered and wrote **zero** documents. Separately the scheduler's 5-second cycle budget is **checked only between batches**, and a batch is ~150 docs ≈ 63 s, so it overshoots by ~12×. **This is a gap in tempdoc 798 D2c's hard budget**, not a new subsystem: the budget exists, it is simply not enforceable at the granularity the UI would need to promise a responsive cancel. |
| Completion message gated on job-queue drain, not coverage | 809 finding 1 | Indexing reports "done" when only extraction + the Lucene write have finished; embedding/SPLADE/NER run afterwards, so semantic and hybrid search do not work during that window. The data to gate on is already on the wire (`embeddingCoveragePercent`, `spladeCoveragePercent`, `pendingNerCount`, `completedNerCount`, `chunkEmbeddingReady`, `queueDepth`). Wiring, not backend. |
| `computeStability` precedence | tempdoc **807 §E.4** residual | The pre-registered 807 residual: a retained verdict *line* can still project a present-tense cause while the liveness signals correctly report stale. Round 14 observed exactly the recorded shape and nothing beyond it, and flagged the `"Available"` row as its most user-misleading instance. |

### Lane 2 — frontend quick wins (delegated)

Bounded, mostly-copy or single-component changes with CI-gateable regression homes. Explicitly
**not** the layout work — that is T-B.

| Item | Source | Scope note |
|---|---|---|
| Health info-tier caveat names no feature | 809 finding 8 | Name the actual degraded feature. The finding's own `[corrected]` note is the evidence: the model is **LambdaMART**, not the cross-encoder reranker, and a careful reader with full API access resolved the vague string to the wrong model. |
| Degradation banner — narrow slice | 809 finding 9 (narrow half) | Transient causes render as a self-dismissing inline line; permanent optional gaps leave the banner. The banner's space-consumption consequence is T-B. |
| Advanced Brain footer labels | 809 finding 10 | `gen: 1` → `engine generation`, qualify *Recent mode transitions*. Nothing here is false; it is unqualified vocabulary colliding with two other in-product meanings of "generation". |
| "Run Offline Processing" label | 809 finding 11 | Name the work, not the mode. The *naming system* question rides with T-A. |
| Run spine suppressed when there is nothing to segment | 809 finding 15 (cheapest fix) | The finding's own "cheapest first" step. The colour/legend/aggregation questions are T-B. |
| Duplicate-fact suppression + telemetry band collapsed by default | 809 finding 12 (cheap wins only) | Render each duplicated fact once; suppress "Over budget" on successful completion; collapse the developer telemetry band. The height-budget question is T-B. |
| Command palette advertises no route to Brain | 734 round-14 **F2** | Discoverability gap; Brain is reachable via the left rail. Also a coverage-brief accuracy problem (session-analysis item 3). |
| Palette "Start AI Install" does not start the install | 734 round-14 **F3** | Label promises an action it does not perform — it navigates. Corroborated by an Activity row recording SUCCESS in 9 ms. |
| New chat / Export absent on the Delegate rung — investigate first | 809 finding 14 | GUI-verified in both directions at identical window dimensions, so it is real and not responsive overflow. **Investigate before fixing:** the finding explicitly does not claim "no route exists" — only the header route was captured. Establish whether the top-right `Agent` button offers an equivalent route before choosing a fix. |

### Lane 3 — docs / harness (this lane)

| Artifact | What landed |
|---|---|
| `docs/tempdocs/809-human-validation-0.2.0-findings.md` | The owner's findings, verbatim, with a provenance header. |
| `docs/tempdocs/810-hv-campaign-triage.md` | This document. |
| `docs/tempdocs/734-0.2.0-sandbox-convergence.md` | Round 14 section appended in the established per-round format. |
| `scripts/sandbox/sandbox-CLAUDE.md` | Retrospective **disposition rule** — every friction item is triaged harness-defect vs product-finding before it becomes a harness note. |
| `docs/how-to/cut-a-release.md` | Qualifying set now names a human gestalt-tier pass; campaign hygiene now includes a do-not-refile-list audit. |

### Not routed to a lane

**809 finding 7** (Timeline / History / Inbox axis) is folded into **T-B** as structural naming and
empty-state work rather than standing alone — its three legibility failures are all
presentation-authority questions on the same surfaces T-B owns.

The `/api/chat/agent/history` empty-batches observation raised in `qa-addendum.md` §1 was
**resolved host-side as correct-by-design** and is not a finding: `FileOperationLog` is the
file-operations undo log only; run accountability lives in `AgentRunStore` and `/api/action-ledger`
(tempdoc 561). It is recorded here so the question is not re-asked next round.

---

## b. Serious-thread charters

Four threads that a bounded fix would damage rather than help. Each section below is written to be
picked up cold by a fresh session: it states the problem, the evidence, the constraints it must
respect, and what "done" would have to answer. **None of them states a solution** — that is the
point of routing them out of the quick-win lane.

### T-A — Indexing progress & queue UX, end-to-end design pass

**Scope:** 809 finding **2** (the queue UI itself), finding **1**'s user-facing surface (what the
completion message should say once it is coverage-gated), finding **11**'s naming problem (whatever
this pass lands on must also name the "Run Offline Processing" action, and the
reindex-vs-enrichment distinction it encodes is the same distinction finding 1 turns on), and
finding **3**'s constraint (the UI cannot honestly offer a responsive cancel while the smallest
interruption granularity is ~a minute).

**The problem, in the owner's words:** the tasks queue is "way too uninformative" — first a small
box with the amount queued, then during active indexing a list of actively-indexed files, both
sitting next to each other. Per-file display is useless when indexing a file takes about a second;
files should be opt-in secondary. Missing: an estimate and a progress bar. The box also blocks
sidebar elements like settings.

**No design decisions have been made.** This deserves a full end-to-end design investigation.

**Constraints the pass has to work within** — carried over verbatim from finding 2:

> - **There are genuinely two queues.** `worker.core.pendingJobs` is the job queue; the
>   "queue / up to date" card reflects the index-writer queue (`writerQueueDepth`,
>   `refreshLagMs`). Seen disagreeing on one screen — "indexing — 0 running · 1218 queued"
>   directly above "queue 0 / up to date" — both true about different things. The obvious fix
>   of picking one number quietly throws away real information.
> - **Throughput varies 6–7×** with document length. SciFact ran ~16 docs/sec; a tempdocs
>   batch did 150 docs in 63s ≈ 2.4 docs/sec, because 65 of 116 embedded docs needed windowed
>   passes. A doc-count estimate will be badly wrong on mixed corpora and wrong in the
>   direction people hate. Needs byte or token weighting.
> - **Coverage is index-wide, the notification is per-folder.** The backfill batches documents
>   without recording which root they came from, so "this folder is 40% enriched" may not be
>   derivable today. Check the source before designing anything per-folder — it decides whether
>   progress is scoped to the add or to the index.
> - **We already have an honest vocabulary elsewhere**: the degraded banner ("1 cause —
>   semantic search degraded" with Open Health) and Health's "what you can do right now" table.
>   Staying consistent with that beats inventing a second way of saying the same thing.

**What "done" must answer:** what a user is told is happening, at what scope (this add vs the
index), with what estimate weighting, in what vocabulary, and where the box lives so it stops
occluding the sidebar. Artifacts named by the finding: the sandbox coverage brief's
`core.library-surface` entry (which already lists "toast occlusion" alongside progress visibility),
`evidence/25-health-surface.png`, `evidence/24-library-root-added.png`.

### T-B — Chat-surface layout & chrome allocation, design pass

**Scope:** 809 findings **12** (vertical space has no owner), **13** (nested scroll regions),
**15**'s structural half (spine density, colour collisions, aggregation rule), and **7**'s
structural half (Timeline / History / Inbox naming and empty states).

**Measured problem:** on a ~790 px-tall window — an ordinary laptop height — chrome takes roughly
60%: surface header ~42 px, degradation banner ~100 px, run-telemetry band ~90 px,
Watch/Assist/Auto + Abilities + composer + rung row ~160 px, status bar ~35 px. ~290 px remain for
the answer *and* its ~310 px Sources column, so the answer opens mid-sentence and scrolls inside a
nested region while the page does not. Finding 13 is causally downstream: each nested scrollbar
marks a place the layout ran out of room and solved it locally, and the scrollbar idiom is then
reused for two non-scroll meanings (the 565 run spine; the telemetry meters).

**Root cause is accretion, and the duplications are its fingerprint.** Three facts render six
times: *Reduced capability* in both the top banner and the status-bar chip ~660 px apart and
simultaneously visible; *Over budget +1207 tokens* twice within ~40 px; the source count three
times within ~250 px. **Each band was independently justified by its own workstream** — the
593/600 truthfulness banner, the 565 agent telemetry band and run spine, the escalation-ladder
rung row — **and none of them owned the sum.** Nothing was ever removed. That is why this cannot be
fixed band-by-band by the workstream that added each band: every individual band is defensible.

**Regression direction** (so the pass produces something gateable, not a taste argument):

- a **height budget** asserted at a pinned **1366×768** viewport — the answer region holds a stated
  minimum share of window height;
- **one scroll region per surface**, page-level;
- a copy-lint that no status fact renders in two persistent surfaces simultaneously.

**Carry the owner's credit forward:** *"Based on your documents — per-sentence grounding not
verified · 45.7 s · Qwen_Qwen3.5-9B"* is an honest disclaimer worth preserving, and the rung buttons
are self-describing. The pass is a budget, not a strip-down.

**Unverified, flagged as such:** the flex structure and the absence of height media queries were
inferred from observed behaviour, not read from source (no source in the sandbox). The
discriminating test is a vertical resize sweep asserting which bands hold height while the content
region absorbs all compression.

### T-C — Corpus scoping & internal-documents policy

**Scope:** 809 finding **5**, plus finding **4**'s "`collection` is not a search filter" note.

**Owner decision required first — this thread cannot start without it.** See §c.

**What was observed:** with `docs\tempdocs` as the *only* registered root, unfiltered search
persistently returned five SSOT help docs from the **install directory**
(`…\resources\headless\SSOT\docs\help\`), `…\Desktop\mcp-ingest-target-round14.txt` ingested via
MCP outside any root, and — at least transiently, per finding 4's unreproduced observation —
`agent-history\*.md`, JustSearch's own agent transcripts.

**Why it compounds:** the surface reports "Searching 567 documents", a number that includes
documents the user never added and cannot enumerate. There is no marker distinguishing them, no
entry in Library › Folders, and no way to scope them out — because `collection` **is accepted and
defaulted on `POST /api/indexing/roots` and echoed by the GET, but is not a member of the search
filter set** (`mime`, `mimeBase`, `fileKind`, `language`, `pathPrefix`, `includeChunks`,
`modifiedAt`, entity/metadata fields). A corpus can be labelled but not queried by its label, so
`pathPrefix` / `doc_ids` are the only scoping routes and `pathPrefix` requires knowing the path.
And `totalHits` does not respect scoping either (Lane 1), so every count on the search surface
currently describes a corpus the user has no way to see.

**Sharpest edge, to be settled separately from the scoping question:** transcripts of AI runs
entering the searchable corpus and surfacing in ordinary queries deserves a deliberate decision
rather than emergent behaviour.

**What "done" must answer:** which internal document classes are allowed in the user corpus; how
each is labelled in results; whether `collection` becomes a first-class search filter or scoping
stays path-based; and what the document count on the search surface counts.

**Regression home named by the finding:** an API test asserting the indexed set is a subset of
registered roots plus an **explicitly declared internal allowlist**, and a UI assertion that
out-of-root documents are labelled as such in results.

### T-D — Action-ledger audit retention & projection design

**Scope:** 809 finding **6**.

**Problem:** `/api/action-ledger` is capped at 500 entries and projects **one row per indexed
document**. Live composition measured: `index` 391 (78%), `effect` 74 (15%), `operation` 24 (4.8%),
`grant` **11 (2.2%)**. `grant` is the TYPED_CONFIRM authorization record — the most audit-relevant
entry a private-retrieval product keeps, and the first thing a per-document projection evicts. A
full-corpus ingest (scifact = 5,190 docs) emits ~5,190 `index` events and **flushes every
`operation` and `grant` entry out of the retrievable window entirely**. That does not bury the
audit trail, it destroys it — inverting the priority of an AUDIT surface.

Aggravating: the rows carry no actionable information (`Indexed · default (f7e852)` — a constant
collection name plus a truncated hash, no filename or path), and the `effect` entries captured by
default are **navigations**, the least consequential user event available, while TYPED_CONFIRM
approvals, ingest authorizations, encryption unlock, memory write/forget and root add/remove are
the ~7% combined.

**Hard requirement:** grants and operations must survive a full-corpus ingest inside the default
retrieval window. Everything else in this thread is negotiable; that is not.

**Suggested projection, from the finding** (a starting point, not a decision): keep per-doc entries
in the ledger for audit integrity, but project **one collapsible row per scan/batch**
(`Indexed 562 documents in default`) expandable to per-doc detail, with an in-surface filter for
"was this file indexed?"; default the user tier to consequential operations and grants, and move
navigation behind a show-all toggle. **No new capture is required** — `originator` and `kind` are
already on every entry and already rendered as chips.

**Still to be decided:** windowing and API semantics. A 500-entry cap with a batch projection is a
different contract from a 500-entry cap over raw entries, and the endpoint's pagination/filter
surface has to say which it offers.

**Regression home named by the finding:** a ui-shot asserting an N-document ingest yields **one
batch row, not N rows**, plus an API test that `operation`- and `grant`-kind entries survive a
full-corpus ingest in the default retrieval window.

**Record correction carried forward:** finding 6 closes F9's earlier caveat that ledger entries
were "all `kind: index`, so a narrower intended class is possible" — four kinds are present, so
F9's empty-state defect was not a narrow-class artifact.

---

## c. Owner-decision queue

Three decisions that agent work should not make unilaterally. Nothing below is urgent in the
release-blocking sense — round 14 found no blocking product defect — but T-C cannot start and the
0.2.0 call cannot be made without them.

1. **F1 — installer sidebar artwork scope.** Round 14 F1 (LOW): text branding on the MUI
   Welcome/Finish pages is present and correct, and interior pages carry a JustSearch header icon,
   but the Welcome/Finish **sidebar bitmap is the stock NSIS `win.bmp`**. The round could not
   determine whether 807 E.1's fix was scoped to text only or was meant to include artwork,
   because the charter said "branding … was fixed" without stating what a healthy Welcome page
   looks like. **Decision needed:** is shipping the stock bitmap acceptable for 0.2.0, or is
   artwork in scope? (The under-specification itself is recorded as round-14 harness finding H1
   and is separately addressed by the charter healthy-signal rule already in `cut-a-release.md`.)

2. **T-C — internal-documents policy.** Are install-directory help docs, MCP out-of-root ingests,
   and agent-history transcripts **allowed** in the user's searchable corpus? If yes, how are they
   labelled and how does a user scope them out? The agent-history question should be settled
   separately from the general scoping question. T-C is blocked on this; the labelling and filter
   design follow from the answer, not the other way round.

3. **0.2.0 qualification call.** Round 14 is a clean mechanical qualification. The question is
   whether 0.2.0 ships now **with known issues** — which `cut-a-release.md`'s known-issues policy
   supports, requiring a dated owner decision, a tracking tempdoc per finding, a Known Issues
   section in the Release notes, and in-place reclassification in the convergence tempdoc — or
   whether it ships **after the correctness lane lands plus one confirmation round**. Two facts
   belong in that decision: (a) this candidate's **uninstaller has never been run by any round**
   despite 807 touching the NSIS templates, and `warm-reinstall-over-existing-data` plus
   `round-10-f11-mid-upgrade-uninstaller-window` both stand `unobservable`; (b) the release's
   qualifying set independently already requires at least one `upgrade-from-release` round, and
   round 14's own recommendation is that a single such round — running the uninstall cycle
   *during* the model download — closes all three gaps together.

---

## d. Why these were missed

Fifteen findings, none blocking, none caught by a campaign that runs a fail-closed coverage gate, a
golden-parity gate, a token-health gate, a reader pass over every credit-eligible screenshot, a
must-watch verdict per registered watch, and an independent host-side re-run. Five mechanisms, and
they are not the same mechanism restated.

1. **The truthfulness lens passes design debt.** The campaign's standing question is *does the UI
   disagree with reality*. Findings 10, 12, 13 and 15 contain **nothing false** — finding 10 says so
   in as many words. A vertical stack where every band is individually justified and truthful, a
   spine that accurately marks node boundaries that happen not to exist here, a `gen: 1` that is
   the correct generation number: each passes the lens and each is still a defect. A lens that
   only detects lies cannot see chrome that is honest and unusable.

2. **Agents verify against ground truth, and compensate for the product.** An agent has the API,
   the tempdocs and the source conventions; when a surface is unclear it reads
   `/api/ai/runtime/status` and resolves the ambiguity — which is exactly what it should do to
   avoid a false finding, and exactly what a user cannot do. Round 14's retrospective and
   session-analysis contain **product findings in embryo, filed as friction**: chat is off by
   default after a successful Install AI and nothing says so (~20 minutes, a near-miss false HIGH —
   *a discoverability defect*); the extraction result rendered below the transcript fold and looked
   like a silent no-op (*a layout defect, and finding 12's mechanism*); the health surface's
   "1218 QUEUED" beside "Queue 0 / Up to date" was resolved by paired sampling into two honest
   counters (*and is finding 2's first constraint*). Each was correctly diagnosed as not-a-defect
   *for the agent*, and each was a real user-facing question that then had nowhere to go. The
   disposition rule now added to `sandbox-CLAUDE.md` is the fix for this specific mechanism.

3. **Coverage samples states, not processes.** The coverage manifest asks whether a surface was
   reached and evidenced. Findings 1, 2, 3 and 6 are all defects **in time or at scale**: the
   completion message is wrong only during the enrichment window; the throughput estimate is wrong
   only on a mixed corpus; the cancel gap is visible only across a 63-second batch; the ledger
   destroys its audit trail only after ~500 entries — i.e. only after a full-corpus ingest that the
   round performs but never re-reads the ledger after. A screenshot of a surface in a good state
   cannot fail on any of these, and a per-surface manifest has no vocabulary for "and then what
   happens over the next four minutes".

4. **The harness institutionalized product behaviours as preconditions.** This one is the sharpest,
   because the harness actively hides finding 5: `gen_golden_parity`'s `check_help_docs_indexed`
   **requires** the install-directory help docs to be indexed — the round's baseline generation
   depends on the very behaviour finding 5 reports as a defect. The do-not-refile list does the
   analogous thing by design: it is correct and necessary (it is what stopped round 14 filing a
   false HIGH on the two model-degraded warnings), but every entry on it converts an observation
   into a non-observation permanently, with no expiry and no periodic re-read. A behaviour that
   starts as "known residual" drifts into "accepted behaviour" without anyone deciding it should.
   Hence the campaign-hygiene line now in `cut-a-release.md`.

5. **Existence gates, with no aggregate owner.** Every gate this campaign runs answers a per-item
   existence question: was this surface touched, is this artifact present, did this watch get a
   verdict, is this screenshot what it claims. Nothing asks a whole-screen question. The rule that
   would have — the **`ux-audit-closure`** requirement for an independent, *measured*, live-verified
   whole-screen UX audit on presentation-authority work — was gate-enforced only briefly (tempdoc
   559 §6-7), **retired in tempdoc 563**, and has been honor-system prose ever since; in practice
   it has not run in months. The owner's manual pass **was its first de-facto execution**, and it
   returned findings 12, 13 and 15 — precisely the whole-screen class — on the first try. That is
   the strongest available argument that the retired gate was measuring something real, and it is
   also the reason the recommended standing fix is a **human tier in the qualifying set** rather
   than another agent-run check: the defect class is defined by what a person experiences, and the
   agent's compensating competence (mechanism 2) is exactly what disqualifies it as the observer.

---

## Status of this document

- Lane 1 and Lane 2 are delegated; their completion is tracked by their own work, not here.
- T-A, T-B, T-C, T-D are **unstarted**. T-C is blocked on owner decision 2.
- Three owner decisions are open (§c). This document is dated history from 2026-08-06 onwards —
  check the round record in tempdoc 734 and `main` before treating any claim here as current.
