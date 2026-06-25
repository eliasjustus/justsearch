---
title: "599 — A watched folder never reports its own indexing status: no per-folder progress, no terminal 'done', no add-time validation. The 593 walkthrough's indexing-journey cluster (§3/§4/§5) — a first-time user adds a folder and cannot answer the two questions that matter from the folder itself: 'is it making progress on MY folder?' and 'is it done and searchable?'. The folder ROW stays a static '587 files · pending clock' throughout, never showing per-folder progress and never flipping to a stated '✓ indexed · last updated <time>'; completion is INFERRED from drifting GLOBAL counters (doc count vs queue vs the Tasks widget); and Add Folder takes a typed path with no type-time validation/preview. Unowned by the lifecycle-RECORD tempdocs (550/575), which model the data, not the folder-facing journey."
type: tempdocs
status: "IMPLEMENTED & MERGED to main (2026-06-17). BOTH the §9 first round AND the §16/§18 backlog (B1 + A1) are shipped and merged. §16/§18 (§19 as-built): B1 — actionable per-folder failures (ListFailedJobsByPathPrefix gRPC chain + folder-scoped endpoint; the <jf-failed-jobs-drawer> right-drawer opened from a clickable 'N failed' chip; retry/cancel item-ops bound on the failed-jobs Resource so the global operator view is actionable too; drawer reuses <jf-row-actions>). A1 — truthful 'Folder unavailable' (a non-blocking background availability cache surfaced as a synthetic path-missing walkError — honors U4, NO per-poll request-thread probe) PLUS the fix for the pre-existing unmount-deletes-the-index data-loss bug (sync-prune + watcher-delete guards). All live-verified in the real browser UI (offline folder → 'Folder not found · last known N files'; remount clears; docs stay searchable; chip→drawer→retry covered by deterministic component tests as a real FAILED job was unmanufacturable). A post-merge design-alignment review found and fixed 3 deviations (the U4 probe → background cache; B1 Move 1 item-op binding + row-actions reuse; the last-known count). Also fixed the contract-projection CI gate (folderStatus.ts declared as an IndexedRootView consumer). D1 (batch count) and B3 (completion toast) remain DEFERRED by design per §18. — The §9 design shipped end-to-end (per-folder job count-by-prefix RPC, walkCompleted signal, the folderStatus single-derivation seam + gate, truthful live folder rows, add-time preview) and was live-verified in the browser (Scanning→Indexing·N→✓ indexed; empty folder → 'No indexable files'; add-preview ✓/✗/⚠). A post-merge critical review found and fixed 3 substantive issues (§14 as-built). Prior status — open — DISCOVERY + LONG-TERM DESIGN COMPLETE (2026-06-17, source-verified against `main`). §7 answers the §6 questions with verbatim citations; §8 REFRAMES the core defect (a terminal state already exists on `main` but is semantically wrong — walk-done shown as searchable — AND invisible, never re-fetched); §9 is the theorized long-term design: 599 is the established 594/595/596/597 'single factual-authority seam' pattern applied at FOLDER granularity, consuming 575's liveness authority + 595's live-state machinery, with ONE narrow new backend signal (per-folder job count-by-prefix, ADR-0028-safe) — no new record tier, no new poller, no 550 record change; §10 fixes the scope boundaries (per-folder vector tier excluded pending 598; count-down not percentage); §11 records the pre-implementation confidence-building verification (8 assumption checks — 3 corrected the design: the per-folder count is a NEW gRPC method not a free projection (U3); folder liveness is backend-inherited not an FE fold (U4); the live tick must not re-walk the filesystem via ?counts=true (U6)) and a critical confidence rating of 7.5/10 for the remaining implementation. §12 is the user-facing/frontend design (inspected the live Library UI + committed renders + render source): the folder card's status glyph + meta line ALREADY exist, so 599 is not a card redesign — it makes the glyph truthful (✓ on drain, not walk), the meta line state-aware (a live 'Indexing · N remaining' count-down → '✓ indexed just now'), and the row live; plus an add-time preview line by the path input. Live-confirmed the heavy first paint (U6) — making fileCount lazy/cached is an incidental existing-UX win. §13 records cross-tempdoc coordination (594–598 cluster actively worked; 596 has a live worktree; 598 actively implemented by the stack-holding session): one favorable dependency (595 — land first), one same-file merge-order coordination (596 — the Library add-row), one semantic boundary to re-check after it resolves (598 — whether 'searchable' must include the vector tier); no blocker, no design-shape change. Implementation not started; one ~10-min add-folder live probe (U8, deferred — stack owned by another session) should precede it."
created: 2026-06-17
author: agent
category: frontend / ux / indexing / job-lifecycle / progress / completion-feedback / first-run
related:
  - tempdoc 593 (the UX walkthrough — §3 Add Folder inline path / no type-time validation, §4 indexing feedback / folder row stays 'pending', §6 completion state never stated on the row, §5 the Tasks panel + the cross-signal drift). THE SOURCE. CLOSED.
  - tempdoc 550 (the operation & action lifecycle — ONE canonical lifecycle record + governed projections + liveness). ADJACENT, NOT THIS: 550 owns the lifecycle DATA/record tier (what happened to a job); 599 is the FOLDER-FACING journey projection — the user can't read their folder's status, which is a missing/weak *projection of* that record, not the record. SHIPPED-line.
  - tempdoc 575 (the observed-happening register — indexing-job in-flight liveness). ADJACENT: 575 governs the in-flight RUNNING-badge liveness authority; 599 is about per-FOLDER progress + a terminal DONE state on the folder, which is a different granularity (folder, not job) and a different question (completion, not liveness).
  - tempdoc 595 (observed-state authority during transitions — the frozen Tasks panel §1.3 + the rebuild-as-data-loss). BOUNDARY: 595 owns the snapshot-not-subscribed FREEZE bug and the REBUILD transition; 599's cross-signal cadence drift (§4) is the related-but-distinct 'the progress numbers you read disagree' — flagged here, boundary to draw with 595, not assumed.
  - tempdoc 559 (Authority III — the single system-message model). The stacking 'Navigated to X' toasts (593 §1) are a MESSAGING-CHANNEL concern (559's domain), explicitly OUT of 599 — a different root.
  - CLAUDE.md `verify-dont-guess` / `audit-driven-fixes-need-test` (593 is point-in-time; re-confirm against `main` before trusting — §6 Q1).
---

# 599 — A watched folder never reports its own indexing status

> **What this document is.** A *problem statement* — the 593 walkthrough's indexing-journey cluster,
> the largest coherent group of its findings that no follow-up owns. **It deliberately proposes nothing
> yet** (per request): it frames the problem, assembles 593's evidence, draws the boundaries against the
> lifecycle-record/transition/messaging tempdocs, and lists what must be verified before any design.

## 1. The problem, in one sentence

> A first-time user adds a folder and **cannot answer the two questions that matter from the folder
> itself** — *"is it making progress on MY folder?"* and *"is it done and searchable?"* — because the
> folder row never shows per-folder progress and never reaches a stated terminal "done" state;
> completion is inferred from indirect, drifting **global** counters; and the add step gives no
> type-time validation.

## 2. The observed reality (593)

The persona's task was literally "index `F:\JustSearch\docs`". What they saw:

- **Add (§3):** Add Folder reveals an inline path field (`C:\path\to\folder`), typed manually (browser
  mode, no native picker). **No live validation/preview at type time** — no "path exists / N files
  found" before submitting. On Add, a row appears: 📁 `F:\JustSearch\docs` — "default · **587 files**",
  a **clock/pending glyph**, a Remove button.
- **During (§4):** progress lives in **global** signals — a status-bar doc counter climbing
  (`8→439→552…`) and a "⚡ queue: N" counting down (`171→75…`), plus a bottom-left Tasks widget of
  per-job hex hashes. **The folder row itself kept the static "587 files" + clock the whole time** — no
  per-folder %, no "423/587".
- **Done (§6):** counts settled (~595 docs, queue draining to 0), but **the folder row stayed on the
  clock + static "587 files" — it did not flip to "✓ done / last indexed <time>"**. Done-ness is
  *inferred* from the queue counter hitting ~0 and the Browse tree showing the files — **never stated
  on the folder the user added**.
- **The signals you DO have disagree (§4/§5):** the doc counter, the "queue:" counter, and the Tasks
  widget label updated at **different cadences** and drifted (the collapsed widget read "482 QUEUED"
  while the status bar showed queue 75). (The *frozen* expanded panel is 595's; the **cross-signal
  cadence drift** is the folder-journey concern here.)

## 3. Why this matters

- **It is the core loop of the product's first task.** "Add a folder, know it indexed" is step one for
  every user; 593's persona completed it only by *inferring* success from peripheral signals.
- **The folder — the one object the user created and cares about — is status-inert.** It reports a
  static file count and a permanent "pending" glyph; the thing that would close the loop (a per-folder
  progress + a terminal "indexed · last updated" on that row) does not exist. The user is left to read
  global counters that aren't about *their* folder and that disagree with each other.
- **It is a distinct gap from what the siblings own.** 550/575 model the lifecycle *record* and job
  liveness (the data); 595 fixes how the *rebuild transition* is displayed. None of them give the
  **folder** a self-reported status. A user could have a perfectly-honest health verdict (595) and a
  governed job record (550/575) and still not know, from the folder, that it finished.

## 4. The findings, named (observations — not diagnoses-for-fixing)

1. **No add-time validation/preview** (§3): the path is accepted blind — no "exists / N files / already
   watched" feedback before the index job is created.
2. **No per-folder progress** (§4): the folder row shows a static declared file count + a pending clock
   throughout; progress is only visible in global status-bar counters, not attributed to the folder.
3. **No terminal completion state on the folder** (§6): the row never flips to a stated "done / last
   indexed <time>"; completion is inferred, never asserted on the folder.
4. **Cross-signal progress drift** (§4/§5): the doc counter, the queue counter, and the Tasks-widget
   label update at different cadences and disagree — the user can't tell which to believe.

## 5. Scope boundary (what this is — and is NOT)

**IN (one root — the folder-facing indexing-journey status):** add-time validation (#4.1), per-folder
progress (#4.2), a terminal completion state ON the folder (#4.3), and the coherence of the progress
signals the user reads for that folder (#4.4).

**OUT (other roots — do NOT bundle):**
- **The lifecycle RECORD + observability data tier** (what a job is, its governed projections, in-flight
  liveness) — **550 / 575**. 599 consumes that record to *project a folder-facing status*; it does not
  re-model the record.
- **The REBUILD transition-as-data-loss / "Service degraded" verdict / the *frozen* Tasks panel** —
  **595** (Observed-state). 599 is the *first-run, healthy* add-folder journey, not the rebuild trauma.
- **The stacking "Navigated to X" navigation toasts** (593 §1) — a **messaging-channel** concern
  (**559** Authority III single-message model), a different root.
- **Semantic-search reachability / result legibility** — **598 / 577 / 570**.

## 6. Open questions — what must be verified BEFORE any design (no proposals here)

1. **Is it still true on `main`?** 593 is dated; re-confirm: does the folder row still lack per-folder
   progress + a terminal "done" state? does Add still skip type-time validation? (`verify-dont-guess`.)
2. **Is per-folder progress even available?** Does the wire carry a **job→folder** attribution (so
   "423/587 for THIS folder" is derivable), or are indexing jobs only globally counted today? This
   gates whether per-folder progress is a projection or needs a new backend signal.
3. **Is "done" a derivable terminal state per folder?** What backend signal would say "folder X is fully
   indexed (incl. its embedding tier) as of time T" — and how does it interact with the 598 embedding
   lifecycle (a folder can be keyword-done but vector-pending)?
4. **Boundary with 595 on the cadence drift:** is #4.4 a genuine incoherence to own here, or a symptom
   of independent pollers already in 595's observed-state liveness scope?

> **No fixes proposed (by request).** The next step, when the user chooses, is the §6 discovery;
> design comes only after the problem is confirmed and scoped.

---

## 7. Discovery findings — §6 answered, source-verified against `main` (2026-06-17)

Investigation method: read-only source trace across `modules/ui-web/src/shell-v0`,
`modules/ui`, `modules/app-services`, `modules/app-api`, `modules/worker-core`,
`modules/indexer-worker`. All citations are verbatim from `main` at the time of writing.

### Q1 — Is it still true on `main`? (PARTLY — and the truth is *worse* than 593 framed it)

593 said "the folder row never flips to ✓ done." On `main` that is **not literally true at the
data layer, but is effectively true in the UI** — for two compounding reasons:

- **A terminal `status` field DOES exist.** `/api/indexing-roots/substrate` derives
  `status ∈ {indexed, pending, error}` per root and the row renders a status glyph
  (`check-circle-2` / `clock` / `alert-circle`) plus a relative `lastIndexedIsoTime`.
  - Derivation: `IndexingController.handleListRootsSubstrate` — `walkError` non-empty → `error`;
    else `lastIndexed` present → `indexed`; else `pending`
    (`modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` ~L504-579).
  - Render: `LibrarySurface.renderCard()` (`modules/ui-web/src/shell-v0/views/LibrarySurface.ts`
    ~L518-551) and the declared `FolderCardRenderer` (`renderers/controls/FolderCardRenderer.ts`).
- **But the flip is invisible, because the folder list never polls.** `LibrarySurface` fetches the
  list at exactly three moments: `connectedCallback` (L319), after any operation completes
  (`invoke()` → `refresh()`, L450), and after `core.reindex` succeeds (L565). There is **no
  `setInterval`, no SSE, no live-store re-fetch.** Its only store subscriptions are
  `subscribePresentation` (re-render only, L321) and `subscribeAiState` (sets the `provisional`
  flag only, L324-326) — neither re-fetches `/api/indexing-roots/substrate`.
- **The single post-add refresh races *ahead* of the walk and latches `pending`.**
  `RootLifecycleOps.addWatchedRoot` marks the root `NEVER_INDEXED`, persists, then runs the walk
  on a background executor and returns immediately (`…/app-services/.../worker/RootLifecycleOps.java`
  L128-129, L205, L213). So the operation returns *before* the walk finishes; `invoke()`'s
  follow-up `refresh()` therefore reads `status="pending"`, and — with no polling — the row stays
  on the clock until the user re-navigates. **This is the mechanism behind 593's observation.**

Net: 593's *symptom* (folder row stuck on clock + static count) is real and reproduced by the
code; its *diagnosis* ("no terminal state exists") is incomplete — a terminal state exists but is
(a) never live-refreshed into view and (b) semantically wrong (Q3 below).

### Q2 — Is per-folder progress available? (NO — and the wire makes client-side derivation impossible)

- Indexing jobs are keyed by **per-file path**, with **no folder/root grouping**. The job record is
  `JobRow(pathHash, state, attempts, lastUpdatedMs, errorMessage, retryAfterMs, collection)`
  (`modules/worker-core/.../queue/IndexingJobChangeFeed.java`; wire twin
  `modules/app-api/.../indexing/IndexingJobView.java`). `collection` is a tag, **not** the watched
  root.
- All progress counters are **global**: `CoreIndexView(indexedDocuments, pendingJobs,
  recentJobQueueDepth[], recentDocsPerSec[], …)` (`modules/app-api/.../status/CoreIndexView.java`).
- **The wire hashes paths (ADR-0028).** Both the folder row (`pathHash`) and the job rows
  (`pathHash`) carry only SHA-256 hashes; raw paths never cross the wire. A client therefore
  *cannot* prefix-match file-hashes to a folder-hash. **Per-folder progress cannot be a pure FE
  projection — it requires a backend-computed signal.**
- Feasibility note (informs §9): the worker's SQLite jobs table holds the **raw** `path` and already
  supports prefix queries — `deleteByPathPrefix` runs `… WHERE path LIKE ? || '%'`
  (`modules/indexer-worker/.../queue/SqliteJobQueue.java`). A symmetric *count*-by-prefix is cheap to
  add server-side; the obstacle is exposure/attribution, not a data-model rewrite.

### Q3 — Is "done" a derivable per-folder terminal state? (NO — and the existing `indexed` is a false positive)

- `lastIndexed` is **walk-driven, set before any job is processed.** The only set-sites are
  `WatchedRootsState.markIndexed/markNeverIndexed/markWalkFailed`
  (`…/worker/WatchedRootsState.java` L41-53), all called from `RootLifecycleOps.walkAndSubmit`
  the moment the *filesystem scan* finishes and files are *enqueued* (`markIndexed` at
  `RootLifecycleOps.java:164`; also the reindex/sync path at L363). **There is no feedback loop from
  job completion back to `lastIndexed`** — `RemoteIndexingJobsBridge` observes job-state transitions
  but never touches the watched-root timestamp.
- Therefore `status="indexed"` means **"walked + enqueued,"** not **"searchable."** A folder can read
  "✓ indexed · 2 minutes ago" while its documents are still PENDING/EXTRACTING/embedding — or while
  jobs are permanently failing. **This is a count-truthfulness defect of the same family as 597 §16.2
  ("M+" truncation): the UI asserts a terminal fact it has not established.**
- The vector/embedding tier is **global-only** (`EnrichmentProgressView.embeddingCompletedCount /
  embeddingPendingCount / spladeCompletedCount …`, all global; no per-root breakdown). So the
  598 concern — "keyword-done but vector-pending" — is **not derivable per folder** today either.

### Q4 — Boundary with 595 on the cadence drift

- The drift is real and is a **multiple-independent-pollers** artifact: the status bar's
  `aiStateStore` polls `/api/status` every **10 s** and `/api/inference/status` every **5 s**
  (`shell-v0/utils/statusPoll.ts` `INTERVAL_MS=10000`; `inferencePoll.ts` `INTERVAL_MS=5000`),
  the Tasks widget reads the indexing-jobs stream on its own cadence, and the folder list **does not
  poll at all**. Different sources sampled at different rates over a draining queue *will* disagree
  transiently.
- **Boundary call:** the *mechanism* (independent pollers over a live counter) is squarely in 595's
  observed-state authority. What is **599-specific** is narrower: the folder row is not a participant
  in any live signal, so it is not "drifting" — it is *absent* from the live picture entirely. 599
  should own "give the folder a live, attributed, truthful status"; 595 should own "the global live
  signals agree with each other." #4.4 is therefore **mostly 595's**, with 599 owning only the part
  where the folder must *join* a coherent live signal rather than sit outside it.

---

## 8. Critical reassessment (the user invited questioning assumptions)

1. **The headline finding should be re-pointed.** 593/§4 framed the gap as "*no* terminal state."
   The verified reality is sharper and more damning: **a terminal state exists, is shown to users
   under the right conditions, and is wrong** (walk-done masquerading as searchable, Q3) **and stale**
   (never re-fetched, Q1). "Add a truthful terminal state" is a *correctness* fix, not just a missing
   feature. This raises priority: a false "✓ indexed" is worse than an honest "pending," because the
   user stops waiting and then gets empty results.

2. **The cheapest real win is decoupled from the hardest one.** Two of the four findings need *no*
   new backend signal:
   - **#4.1 add-time validation** is purely a Head/FE concern (validate path exists / count files /
     "already watched" before creating the job) — the controller already does a synchronous
     `Files.walk` for counts, so a `POST /…/preview` is a small lift.
   - **Live refresh of the existing row** (the Q1 invisibility half) is a one-line-ish FE change:
     have `LibrarySurface` re-`refresh()` on a timer or piggyback the existing `aiStateStore` tick
     while the surface is open.
   These are separable from #4.2 (per-folder progress) and the truthful-#4.3, which **do** need a new
   backend attribution signal. The tempdoc should not bundle them into one monolith.

3. **Question the "per-folder progress bar" assumption itself.** "423/587 for THIS folder" is
   appealing but has a denominator problem: `587` is a *live filesystem walk count* computed at
   fetch (`?counts=true`), while the numerator would be *jobs done under this prefix*. The two are
   sampled at different times and from different subsystems — re-introducing a per-folder version of
   the very cross-signal drift (#4.4) we're trying to kill. A **truthful three-state badge**
   (`indexing… / ✓ searchable · <time> / ⚠ N failed`) derived from a *single* per-folder
   queue-count signal may be both cheaper and less drift-prone than a percentage. Recommend the user
   weigh "honest state" vs "precise percentage" explicitly (§9 Decision B).

4. **One assumption to retire:** that this is unowned because it is "just UI." The root cause of the
   worst symptom (#4.3) is a **backend semantics choice** — `lastIndexed` means "walked," not
   "searchable." Any honest fix touches the lifecycle record that 550/575 own. 599 stays the
   *folder-facing projection* tempdoc, but it must *consume a new or corrected backend fact*, so the
   boundary with 550/575 is a collaboration point, not a clean wall (Q2/Q3 both end at a backend
   signal). Flag for the user: does the truthful-completion signal get authored here, or as a 550/575
   extension that 599 projects?

---

## 9. The long-term design (theorized; general, not implementation-level)

> The user authorized a *deep, long-term* design — not a patch. The investigation (§7-§8) and the
> adjacent-cluster survey (§9.0) make the correct shape unusually clear: **599 is not a new mechanism.
> It is the established `594/595/596/597` "single factual-authority" pattern applied at one granularity
> the codebase has not yet covered — the folder — consuming the `575` liveness authority and the `595`
> live-state machinery.** The only genuinely-new structure is a narrow backend attribution count the
> wire cannot derive client-side (§9.0 finding Q2). Everything else is reuse.

### 9.0 What already exists (and is on `main` or shipping) — the reuse inventory

The design is anchored to existing, verified infrastructure rather than new invention:

| Need (599) | Existing authority to consume / extend | Status |
|---|---|---|
| Derive a UI fact in ONE seam, all surfaces consume it, gate the dishonest literal | The `594`/`596`/`597` template: `projectFact` / `projectAvailability` / `computeVerdict` + `check-*.mjs` single-derivation gates | **merged on `main`** (2026-06-17) |
| A Lit surface joins a LIVE signal without its own poller | `595` `aiStateStore` + `subscribeAiState` (the one observed-state authority; status poll is the transport) | shipped `worktree-595-impl` |
| Render a value honestly while state is in flux (don't assert a stale terminal fact) | `595` `Stability` axis + `renderObserved(value, stability, {settled, provisional, unknown})` | shipped `worktree-595-impl` |
| "Is this unit of work live right now?" | `575` `isInFlightLive` + the heartbeat/stale window generated from the action-lifecycle `liveness` block | **merged on `main`** (2026-06-11) |
| The canonical per-job lifecycle record + the "every read-view is a governed projection" rule | `550` `LifecycleEvent` + the operation-surface register | active; record tier on `main` |

**The one thing that does NOT exist and the problem genuinely requires:** a per-folder attribution of
in-flight/failed jobs. Jobs are keyed per-file (`pathHash`), counters are global, and the wire hashes
paths (ADR-0028) — so this **cannot** be an FE projection (§7 Q2). It must be a backend-computed
signal. This is the design's single new structural element; §9.2 keeps it minimal.

### 9.1 The core seam — a per-folder status authority (the 594/597 pattern, folder granularity)

The root defect (§8.1) is that the *render site* receives a raw `lastIndexed` timestamp and decides
"indexed" from it — a fact re-derived at the surface from the wrong input. The structural fix is the
same one 597 used for the result count and 595 used for the health verdict: **resolve the fact once,
upstream, and make the surface a pure consumer.**

- **One derivation** — a single `folderStatus(root) → FolderIndexingStatus`, the folder-granularity
  sibling of `computeVerdict`. It is the *sole* place a folder's searchability is decided. Shape
  (conceptual, not a record spec):
  - `state ∈ { scanning, indexing, ready, failed, empty, unknown }`
  - `inFlight` (jobs not yet terminal under this root), `failed` (terminal-failed under this root)
  - `lastScannedAt` (the *honest* meaning of today's `lastIndexed` — "last walked", §8 finding Q3)
  - and a `Stability` tag projected from `595` (so a global rebuild renders the row provisionally).
- **`ready` is derived from drain, not from the walk.** `state = ready` iff the root has been scanned
  **and** `inFlight == 0` **and** `failed == 0`. The walk timestamp alone can never produce `ready` —
  which makes the §8.1 false-positive ("✓ indexed" while jobs drain) *unrepresentable at the seam*,
  exactly as 597 made `headlineCount < Σfacets` unrepresentable.
- **All surfaces consume the seam.** The row badge/glyph, the meta line, and any future folder health
  read `FolderIndexingStatus`; none re-reads a timestamp. This is the 594/596 "fact-ref not a free
  string" discipline at folder scope.

### 9.2 The new backend signal — minimal, riding the existing substrate projection

To feed §9.1 the wire must carry per-folder counts. The minimal, scope-matched form **extends the
view the folder list already consumes** rather than adding a tier or endpoint:

- The worker already stores **raw** job paths in a prefix-queryable SQLite table (`deleteByPathPrefix`
  uses `path LIKE ? || '%'`). Add a symmetric *count-by-prefix* → `{inFlight, failed}` per watched
  root. **This is the design's one genuinely-new cross-process piece, and it is a new gRPC method, not
  a free projection** *(corrected by §11/U3 — the Head does NOT round-trip to the worker for job data
  today; `handleListRootsSubstrate` reads Head-local state + a local `Files.walk`)*. It follows the
  standard "add a gRPC method" path (proto message → worker-service count impl → `DelegatingIngestService`
  forward → `RemoteKnowledgeClient` call → contract test). Two prefix-matching edge cases to handle:
  trailing-separator boundaries (`F:\docs` must not match `F:\docs-other`) and nested watched roots
  (`F:\a` + `F:\a\b` would double-count without de-overlap).
- The Head projects those counts onto the existing `IndexedRootView` (`/api/indexing-roots/substrate`)
  as two fields keyed under the root's `pathHash` — ADR-0028-safe (no raw paths cross the wire), and
  `LibrarySurface` already fetches this exact view. The **wire extension is mechanical** (§11/U7: edit
  `SSOT/schemas/indexed-root.v1.json` → `gen-wire-schema-types.mjs` → add the `.put()`s in the
  controller Map; governed by `wire-type-single-authority`). **No new REST endpoint, no new record
  tier, no change to the `550` lifecycle record.** Per `550`'s rule, this is a *governed projection* of
  the per-job records grouped by folder — registered like any other projection, not a second authority.

This is the deliberate scope line on granularity: **per-folder counts, not a percentage.** A
percentage needs a separately-sourced denominator (walk-time admitted count) sampled at a different
moment than the numerator — re-introducing the very cross-signal drift §8.3/#4.4 is trying to kill. A
count-down (`indexing · N remaining` → `✓ searchable`) answers both of 593's questions ("is it
progressing?" / "is it done?") truthfully with one self-consistent signal. The denominator is
*optional later polish*, not part of the correct floor — adding it now is structure for a precision
the problem does not require.

### 9.3 Liveness and live refresh — reuse, don't reinvent (575 + 595)

- **In-flight truth comes from the worker, not an FE liveness fold.** *(Corrected by §11/U4 — the
  original "fold `isInFlightLive` over the folder's jobs" was wrong: the FE cannot map a job's
  `pathHash` to a folder's `pathHash`, so the rollup cannot be FE-side.)* Folder in-flight count is a
  **backend** concern: the worker's reaper (runs every 2 min, resets `PROCESSING` rows older than
  `REAPER_STALE_MS`=300 s back to `PENDING`) keeps a worker-side `count(PENDING+PROCESSING under prefix)`
  free of zombie rows, so the count **is itself** the liveness-safe signal — `575`'s window is
  *inherited* by the count, not re-applied on the FE. The folder can never show phantom "indexing…" for
  a dead worker beyond the ~5–7 min reaper window (acceptable; matches `575`'s display semantics).
- **The row joins the live signal through `595`, not a private poller — but NOT via `?counts=true`.**
  *(Corrected by §11/U6.)* The Q1 invisibility bug is closed by making `LibrarySurface` re-pull on the
  existing `aiStateStore` status tick **while mounted** — same transport `StatusDeck` rides, no second
  poller. **Critically, the live tick must consume the cheap per-folder job counts only; it must NOT
  re-run `?counts=true`,** which fires an uncached synchronous `Files.walk` per root every request
  (prohibitive at 10 s for large folders, §11/U6). The filesystem `fileCount` stays on-demand
  (add / manual refresh) or is cached separately — it changes slowly and is not the live signal. During
  a global transition the row renders via `renderObserved`/`Stability` (busy tone, last-known values)
  instead of asserting a stale terminal state. (Per `595` Gap 2, feed-staleness stays *panel-scoped*;
  599 introduces no folder-scoped provisional cause.)

### 9.4 Add-time validation — a separate, smaller seam (#4.1)

Orthogonal to the status machinery and correctly smaller. A synchronous preview the FE consults before
enabling "Add" — `{exists, isDir, fileCount, alreadyWatched}` — reusing the controller's *existing*
synchronous walk (the same one `?counts=true` already runs). It belongs to 599 because it is part of
the one folder-journey root, but it shares none of §9.1-§9.3's live machinery and should ship as its
own move. No new subsystem.

### 9.5 Enforcement — follow the cluster's close, sized to the defect

The 594/596/597 cluster consistently closes with a *gate that makes the next author's shortcut
unrepresentable*, not just a test. The defect class here is identical (a surface re-deriving a fact
from the wrong input), so the same close applies and is **not** speculative:

- **Floor (required):** a regression test asserting the seam's invariant — `state == ready ⟹
  inFlight == 0 ∧ failed == 0` and "a walk timestamp alone never yields `ready`" (the 597
  `matchCount ≥ Σfacets` analogue). This is the `audit-driven-fixes-need-test` companion to §10's live
  check.
- **Durable close (recommended, matching the cluster):** a `check-*.mjs` single-derivation gate
  forbidding any `shell-v0` surface from forming a folder `indexed`/`searchable` verdict outside the
  seam (the `check-verdict-derivation` / `check-search-issuance` pattern), registered like its
  siblings. Warranted because the bug *is* a drift bug; deferrable only if the user wants the seam +
  test first and the gate as a fast-follow.

---

## 10. Boundaries, non-goals, and residual verification

**What 599 deliberately does NOT build (scope discipline — no structure for cases not yet present):**
- **No per-folder vector/embedding-tier breakdown** ("keyword-done, vectors-pending"). 598 establishes
  that the dense leg is *never requested from the search box at all*; per-folder vector completion is
  meaningless to the user until 598 lands. `ready` therefore means *the folder's jobs drained* — the
  honest, reachable fact today. Revisit only after 598. (Was option "B3"; excluded by scope.)
- **No percentage/denominator** (§9.2 rationale) — count-down only, until a real need appears.
- **No `550` lifecycle-record change.** The truthful fact is a *projection derivation* (§9.1/§9.2);
  `lastIndexed` keeps its honest "last scanned" meaning rather than being overloaded. Two distinct
  facts ("scanned at" vs "searchable") get two names — correct modeling, not duplication. (This is the
  resolved form of §8.4's boundary question and the earlier "C1 vs C2": the projection boundary, not
  the record tier, is where the fact belongs — chosen on correctness, not blast radius.)
- **No ownership of the cross-signal drift mechanism (#4.4).** That is `595`'s observed-state authority
  (independent global pollers). 599 owns only making the folder row *join* a coherent live signal
  (§9.3), not reconciling the global counters.
- **No messaging-channel / navigation-toast work** (593 §1) — that is `559` Authority III.

**Collaboration boundaries (not walls):** §9.2's new count is a governed projection of `550`'s records;
§9.3 reuses `575`'s liveness and `595`'s live-state authority; the `ready` semantics depend on the
worker's job-drain truth. 599 is the folder-facing *projection* tempdoc throughout — it consumes these,
it does not re-model them.

**Residual verification (the one cheap empirical step).** The source trace is dispositive on the
mechanism, but per `verify-dont-guess`/`audit-driven-fixes-need-test` a ~10-minute live confirmation of
§7 Q1 should precede implementation: add a folder on the dev stack, confirm the row stays `pending`
until re-navigation, and confirm it would assert `✓ indexed` while jobs are still draining. This both
closes the last open question empirically and seeds the §9.5 regression test.

**Sequencing (an outcome of the design, not a target):** §9.4 (add preview) is independent and can land
first or last. The status core is §9.2 (backend count) → §9.1 (seam) → §9.3 (liveness rollup + live
refresh via 595) → §9.5 (test, then gate). The total surface is small precisely because the design is
mostly composition of existing authorities; the new code is one prefix-count gRPC method, two projected
fields, one derivation seam, and one subscription wiring.

---

## 11. Confidence-building verification (2026-06-17) — assumptions tested before implementation

Per `verify-dont-guess` / `audit-driven-fixes-need-test`, the §9 design's load-bearing assumptions were
tested (8 checks, source-cited + one live read-only probe of the running stack). Three corrected the
design (folded back into §9.2/§9.3 above); the rest confirmed it. Verdicts:

| # | Assumption tested | Verdict | Effect on design |
|---|---|---|---|
| U1 | Jobs table retains terminal rows so `ready` is derivable | **Corrected-minor.** `DONE`/`FAILED` rows are kept (`markDone`/`markFailed` are `UPDATE`s) but a time-based GC `cleanupOldJobs(retentionDays)` prunes them later → "0 rows" is ambiguous (done vs never-indexed). | `ready` MUST anchor on the **scanned** flag (today's walk timestamp), not "0 rows": `ready = scanned ∧ inFlight==0 ∧ failed==0`. Already how §9.1 reads; anchor is now load-bearing, not incidental. |
| U2 | jobs-drain ⟹ keyword-searchable; vector tier separate | **Confirmed (static + live).** Enrichment (embedding/SPLADE/NER) is a **separate flag-based backfill tier**, not the jobs queue; `markDone` fires *after* commit, NRT refresh ≤~500 ms. Live: at rest `pendingJobs:0` while `pendingVduCount:1`, `refreshLagMs:0`. | §9.1 `ready`="keyword-searchable" is honest; §10's vector-tier exclusion (pending 598) is correct, not an over-claim. |
| U3 | Per-folder count is a near-free projection | **Corrected.** The Head reads Head-local state + a local `Files.walk`; it does **not** round-trip to the worker for job data, and no count-by-prefix RPC exists (`jobStateCounts()` is global-only). | The count is a **new gRPC method** (the design's one real cross-process addition), not a free projection. Folded into §9.2 with the standard add-RPC checklist + two prefix edge cases. |
| U4 | Folder liveness reuses FE `isInFlightLive` | **Corrected (contradiction resolved → backend).** FE can't map job-hash→folder-hash. The worker reaper (2-min interval, 300 s stale window) keeps a worker-side non-terminal count zombie-free, so the **count itself** is liveness-safe. | §9.3 rewritten: liveness is backend-inherited; the FE just consumes the count. Removed the impossible FE fold. |
| U5 | `595` `Stability`/`renderObserved`/`subscribeAiState` available | **Confirmed-better.** All present + exported on the **current `main`** (`state/verdict.ts`, `state/known.ts`, `state/aiStateStore.ts`, `utils/statusPoll.ts`@10 s). | No hard sequencing dependency on 595 landing; 599 reuses them directly. |
| U6 | Live-refresh can ride the status tick cheaply | **Corrected.** `?counts=true` runs an **uncached synchronous `Files.walk` per root every request**, and there is no counts-free variant — re-walking every 10 s is prohibitive for large trees. | §9.3 amended: the live tick consumes **job counts only**; the filesystem `fileCount` stays on-demand / cached. Implies a counts-free job-status read path. |
| U7 | Adding two wire fields is mechanical | **Confirmed.** Row type is generated from `SSOT/schemas/indexed-root.v1.json` via `gen-wire-schema-types.mjs`, governed by `wire-type-single-authority`; the response is a hand-built `LinkedHashMap` in `IndexingController`. | 3-step mechanical change (SSOT JSON → regen → controller `.put()`s). Low risk. Wart: response Map ≠ the `IndexedRootView` record (hand-maintained), but additive. |
| U8 | Live end-to-end confirmation of Q1 + false-terminal race | **Partial (read-only).** Stack owned by another session → no `write` (add-folder) probe run, per dev-stack coordination rules. Read-only GETs confirmed the row shape (`lastIndexed` walk timestamp, no per-folder counts) and U2. | The **racing false-terminal observation** (status="indexed" while jobs pending) remains *static-only*; the source trace (§7 Q1: `markIndexed` at walk time, no job→`lastIndexed` feedback) is dispositive. A 10-min add-folder probe on a free/owned stack remains the cheap pre-impl confirmation + the §9.5 test seed. |

### 11.1 Critical confidence rating for the remaining (implementation) work: **7.5 / 10**

The design is well-grounded: the worst defect is verified, every reused authority is confirmed on
`main`, and the corrections *shrank* uncertainty (595 is available; liveness is backend-trivial). The
deductions:

- **−1 the new count-by-prefix gRPC** is the one real cross-process addition — standard pattern, low
  uncertainty, but real work with two named edge cases (prefix boundary, nested roots) that must be
  handled carefully or the counts silently mislead.
- **−1 the live-refresh decoupling** (U6) adds a wrinkle: a counts-free job-status read path is needed
  so the 10 s tick doesn't trigger filesystem walks — a design refinement, not yet shaped.
- **−0.5 the racing false-terminal-state was not live-observed** (U8 write deferred); static evidence is
  strong but a live add-folder confirmation should precede implementation and seed the regression test.

Residual risks are bounded and known; none threatens the design's shape. Recommended pre-implementation
step: the ~10-min add-folder live probe (U8) on a free/owned stack.

---

## 12. User-facing / frontend design

> 599 is **substantially user-facing** — its subject *is* a UI surface. This section is grounded in
> direct inspection of the running UI + committed renders, not the tempdoc alone.

### 12.0 What was inspected (2026-06-17)

- **Live dev UI** (`localhost:5173`, Library surface): confirmed the "Navigated to Library" toast
  (593 §1, still present), the global status bar as the *only* live progress signal (`📄 610` doc
  count, climbing/at-rest), and — notably — the Library's **initial load is heavy**: it sat on
  "Loading Library…" with the status channel showing "Reconnecting…", and screenshot capture hit
  repeated 30 s renderer-freeze timeouts during the substrate load. That is a **live corroboration of
  U6** — the first paint already pays the synchronous `?counts=true` `Files.walk` per root.
- **Committed renders** (the empty / first-run states): the empty state ("No watched folders. Click
  'Add Folder'…" and the friendlier "Your library is ready to grow · N files indexed"), the
  **browser-mode add flow** (a warning banner "Native folder picker not available — enter paths
  manually" + a bare `<input>` placeholder `C:\path\to\folder` + Add/Cancel, **no validation/preview**),
  and the dismissible error banner (an "HTTP 502"-style chip).
- **Render source** (`FolderCardRenderer.ts` / `LibrarySurface.renderCard`): a row is `folder icon ·
  status glyph · path · ONE pre-formatted meta line · Remove`. The glyph is already 3-state —
  `check-circle-2` (indexed, `--text-tint`) / `alert-circle` (error, `--text-danger`) / `clock`
  (pending, `--text-secondary`). The meta line is the single string
  `"{collection} · {N files | count pending} · {relativeTime} · {walkError}"`.

### 12.1 The user-visible parts of the §9 design

| §9 element | User sees | Directness |
|---|---|---|
| §9.1 status seam | the row's **glyph** + **meta line** state (today: clock/check/alert + "N files · time") | direct |
| §9.3 live refresh | the row **changing on its own** after Add (today it does not) | direct |
| §9.4 add preview | a **validation/preview line** by the path input before Add | direct |
| §9.2 backend count | feeds the row's progress/terminal text | indirect (no UI of its own) |
| §9.5 gate/test | none | not user-visible |

### 12.2 The key realization — the visual slot already exists; this is not a card redesign

The folder card **already has** a status glyph and a meta line. 599 does **not** add a new badge
component or restructure the card. It does three things to the slot that exists:
**(a) make the glyph truthful** (today it flips to `✓` at *walk-completion*, a false "ready"; §8.1),
**(b) make the meta line state-aware** (carry progress, not just a static count), and **(c) make it
live** (today the row is frozen at its add-time snapshot; §7 Q1). This keeps the visual change small and
in-vocabulary — the right scope.

### 12.3 The row's user-visible state machine (what the meta line + glyph say)

One honest state per row, projected from the §9.1 seam. The glyph stays the existing 3-symbol
vocabulary; the **meta line carries the nuance** (so no new iconography is required):

| State (seam) | Glyph | Meta line (example) | When |
|---|---|---|---|
| `scanning` | `clock` (active) | `Scanning folder…` | walk in progress, before jobs |
| `indexing` | `clock` (active) | `Indexing · 423 remaining` *(live count-down)* | in-flight jobs > 0 |
| `ready` | `check-circle-2` (tint) | `587 files · indexed just now` | scanned ∧ in-flight 0 ∧ failed 0 |
| `failed` | `alert-circle` (danger) | `587 files · 3 failed · <reason>` | failed jobs > 0 |
| `empty` | `clock`→neutral | `No indexable files found` | scanned ∧ 0 files admitted |
| *provisional* | (last glyph, busy tone) | `Rebuilding… (last: 587 files)` | global transition (595 `Stability`) |

This is the **honesty fix made visible**: the count-down answers 593's *"is it progressing on MY
folder?"*, and the `ready` line — gated on drain, not the walk — answers *"is it done?"* truthfully.
A precise progress *bar/percentage* is deliberately **not** shown (§9.2/§10: a separately-sourced
denominator re-introduces drift); the remaining-count is one self-consistent number.

### 12.4 Add-folder preview (the §9.4 user-facing surface)

The browser-mode add flow already shows the warning banner + a bare input with no feedback. The preview
slots **directly below/beside that input**, as a single live line that annotates and gates the Add
button — e.g. `✓ Folder found · ~587 files` / `✗ Path not found` / `⚠ Already watched`. It reuses the
controller's existing synchronous walk (the same one `?counts=true` runs) and the existing add-row
layout; no new dialog. (Note for later, **out of 599's scope**: that `<input>` is a raw element, not the
596 `jf-control` operability primitive — a 596 concern, flagged, not fixed here.)

### 12.5 Live-update UX + not re-creating the cross-signal drift

The row updates on the existing `aiStateStore` status tick (§9.3) — the user no longer has to
re-navigate to see status change. **Critical user-facing rule:** the row's count is scoped *"this
folder"* and the status-bar doc count is *"everything"* — they answer different questions and must be
**labelled so the user never expects them to match** (the per-folder remaining-count vs the global
doc/queue counters). This is how 599 avoids planting a new instance of #4.4's cross-signal-drift
confusion on the very surface it is trying to make legible. During a global rebuild the row renders
*provisionally* via 595's `renderObserved`/`Stability` (busy tone, last-known values) rather than
asserting a stale terminal state.

### 12.6 Incidental existing-UX win (same root)

The live-observed heavy first paint (§12.0) is the *same* `Files.walk` the design must decouple from the
live tick (U6). Making `fileCount` **lazy/cached** therefore fixes a **current** user complaint (Library
slow to load) in addition to enabling cheap live refresh — one change, two user-visible wins, same root
cause. Worth doing as part of the status work, not a separate effort.

### 12.7 User-visible, but explicitly OUT of scope (other roots)

- The stacking **"Navigated to X" toasts** (593 §1) — `559` Authority III messaging channel.
- The add **`<input>` not being a `jf-control`** (no reachable disabled-reason) — `596` operability.
- The **rebuild-transition trauma** display ("Service degraded" / "Reconnecting…") — `595`. 599 only
  *consumes* 595's provisional vocabulary for the row; it does not own the transition display.

---

## 13. Cross-tempdoc coordination & interference (scanned 2026-06-17 ~05:05 UTC)

The whole 593-spawned cluster (594–598) was modified within the last few hours; 596 has an **active
worktree** (`worktree-596-remaining`); 598 is being **actively implemented by another session** (the
dev-stack holder — which is why §12.0's live Library was wedged). Effect on 599's remaining work:

- **595 — dependency, favorable (not a conflict).** 599 §9.3/§12 *consume* 595's `Stability`,
  `renderObserved`, `subscribeAiState`, and live `aiStateStore`. 595 finalizing/merging first
  **de-risks** 599. Watch: if 595's API shape (the `renderObserved` signature / `Stability` causes /
  the status-poll wiring) shifts as it lands, 599's row-liveness + provisional rendering must track it.
  Also 595's `check-verdict-derivation` gate scans `shell-v0` — 599's *folder* status seam is a
  distinct fact (not the system verdict), so it should not trip the gate, but build the seam so it
  doesn't read `readiness.retrieval` to form a verdict.
- **596 — real code-conflict risk (same file region).** `worktree-596-remaining` is actively migrating
  `shell-v0` controls onto `jf-control` and tightening the `controls-a11y` gate (reason-on-disabled /
  discarded-compose patterns fail the build). 599 edits the **same** `LibrarySurface` add-row (the bare
  `<input>` + Add button) for the §12.4 add-preview, and the §12.4 preview *gates the Add button* —
  exactly 596's disabled-with-reason concern. Coordinate **merge order** and build the add-preview's
  affordances on 596's `jf-control` discipline rather than the current raw `<input>`/`<button>`.
- **598 — semantic coupling (no code conflict; touches different files).** 598 (reachability of the
  dense/vector path) could **redefine what "ready/searchable" means to the user**: if dense search
  becomes reachable/expected, 599's terminal "✓ indexed" (scoped to *keyword*-searchable per §10) risks
  becoming an over-claim again for a user who expects semantic results. 599 already excludes the
  per-folder vector tier "pending 598"; the resolution of 598 is the trigger to revisit whether
  `ready` must also reflect vector-tier coverage. Code conflict is low (598 works in the engine /
  `searchState.ts`, not the Library).
- **594 — template + minor gate.** 594 (merged) is the *pattern* 599 follows (derive the fact once,
  consume everywhere). Minor watch: 594's fact-literal gate scans declared chip labels for fact-shaped
  tokens; 599's status text ("N files", "N remaining") must flow through the §9.1 projection, not
  hardcoded literals — which 599 already plans.
- **597 — template, low conflict.** Merged; the count-truthfulness template 599 mirrors. Touches the
  search surface / `searchState.ts`, not the Library — minimal overlap.

**Net:** no blocker. One favorable dependency (595, land it first), one merge-order/same-file
coordination (596 — the add-row), and one semantic boundary to re-check after it resolves (598 — the
meaning of "searchable"). None changes 599's design shape; all are sequencing/coordination.

---

## 14. As-built (IMPLEMENTED & MERGED 2026-06-17)

Shipped on `main` via the `worktree-599-impl` merge. The §9 design landed essentially as designed,
with the two boundary calls resolved as recommended (C2 — a derived signal, not a `lastIndexed`
semantics change; D1 — refresh on the existing aiState tick).

**What shipped:**
- **Backend signal:** `CountJobsByPathPrefix` gRPC end-to-end (`SqliteJobQueue.countByPathPrefix` →
  `JobQueue` → proto → `GrpcIngestService` → `DelegatingIngestService` → `RemoteKnowledgeClient` →
  `IndexingService`); `inFlightCount`/`failedCount` projected onto `IndexedRootView`.
- **Seam:** `state/folderStatus.ts` — the ONE per-folder status derivation; `ready` ⟸ job drain,
  never the walk timestamp. Enforced by `governance/folder-status-derivation.v1.json` +
  `scripts/ci/check-folder-status-derivation.mjs` (wired in `ci.yml`).
- **Row:** truthful glyph + state-aware live meta line; counts-free live refresh on the aiState tick;
  add-time preview endpoint (`POST /api/indexing-roots/preview`) + `jf-control`-gated add-row.

**Post-merge critical-review fixes (the same commit series):**
1. **Empty/all-excluded folder showed a permanent "Scanning…".** The wire could not distinguish
   "walk in progress" from "walked, zero admitted" (both `pending`, no `lastIndexed`). Fixed with an
   explicit `walkCompleted` signal threaded state → persist → `WatchedRoot` → `IndexedRootView` → the
   seam, so an empty folder reaches the honest `empty` state ("No indexable files"). Live-verified.
2. **`countByPathPrefix` used `LIKE 'x%'`** — case-insensitive LIKE forced a full table scan per
   folder per live tick and treated `_`/`%` in paths as wildcards. Replaced with a PK-index-friendly,
   wildcard-safe half-open range query. (Pre-existing `deleteByPathPrefix` shares the LIKE wildcard
   bug — logged to `observations.md`, out of scope.)
3. **Add-preview `Files.walk` was unbounded** (per debounced keystroke). Bounded via a shared
   `countFilesBounded` helper (the `ExcludesServiceImpl` pattern); preview reports "N+" when capped.
   Also caps the `?counts=true` substrate walk. Live-verified (`C:\` → "50,000+", no stall).

**Deliberately out of scope (unchanged from §10):** per-folder vector-tier completion (revisit after
598), a progress percentage/denominator, navigation toasts (559), the rebuild-transition display (595).

---

## 15. Future directions — what the per-folder-status primitive enables (research, 2026-06-17)

> **Status:** research/ideation only (no code). 599 shipped a *primitive*: a truthful, live, per-folder
> indexing status (`folderStatus` seam) fed by a cheap backend count-by-prefix, plus an add-time
> preview. This section asks what is worth building ON that primitive, grounded in how mature
> adjacent products do it. Nothing here is committed; all are viable, none urgent (no users yet).

### 15.1 How this was researched
Four source families, by domain-closeness: **(a) file-sync clients** (Syncthing, Dropbox, OneDrive,
Nextcloud) — the gold standard for per-folder *state machines*, badges, completion, pause/retry;
**(b) local-RAG ingestion UIs** (AnythingLLM, Khoj) — the closest domain; **(c) desktop/IDE indexing**
(DEVONthink, JetBrains); **(d) progress/ETA + notification UX literature** (NN/g and practitioners).
Each idea is filtered by: value to the core loop · cost/reuse of existing infra · fit with 599's
truthfulness/single-derivation principles · honesty pitfalls.

### 15.2 Cross-domain findings
- **State vocabulary is richer than ours.** Syncthing/OneDrive carry *Up-to-date · Syncing ·
  Processing · Out-of-sync(N items) · Paused · Stopped/"folder path missing" · Error*. Ours is
  scanning/indexing/ready/failed/empty — we lack **unavailable** (path deleted/unmounted) and
  **paused**. (Sources: Syncthing GUI docs; OneDrive status icons.)
- **Per-folder controls are standard & demanded.** Syncthing exposes pause / force-rescan on each
  folder; JetBrains "pause indexing" was a years-long top request before it shipped.
- **Failed-item handling is the field's biggest gap.** Syncthing surfaces an "Out of Sync Items"
  *count* but "the interface does not provide any solutions" — users resort to pause/resume/restart.
  An *actionable* failed list (see → retry) would beat the incumbents.
- **The "folder unavailable" case is a known truthfulness + data-loss trap.** Sync clients show
  "folder path missing/Stopped" on unmount, and a documented bug class is *failing to detect
  re-appearance* (stale error) — and some clients destructively "delete" content when a folder
  vanishes. Re-check existence each poll; never treat "gone" as "0 files / delete docs".
- **ETA honesty (NN/g et al.):** percent-done only for >10 s ops *and only when accurate within ~10%*;
  otherwise a **fuzzy** estimate ("about 2 minutes", or a 1–3 min band) beats a stalling countdown.
  This vindicates 599's no-fake-percentage stance and points to a *throughput-based* ETA instead.
- **Notification fatigue:** no redundancy (don't toast what the UI already shows), no stacking
  (queue/latest-only), context-relevant, ≥5 s. Shapes any completion notification.
- **We're already ahead in our own domain.** Local-RAG tools (AnythingLLM) chunk→embed→store but
  expose little per-source ingestion status; our live per-folder status is a differentiator to extend,
  not catch up on.

### 15.3 Prioritized idea backlog (build on the 599 primitive)

**Top tier — high value, low cost, reuses existing infra, strong principle-fit:**
- **(B1) Failed-count drill-down + per-folder retry.** Turn the row's "N failed" into an affordance:
  click → list the failed files under this folder (their error) → Retry / Retry-all. Reuses
  `failedCount` (599), the `core.failed-indexing-jobs` substrate, and the existing `RetryIndexingJob`
  RPC; the only new bit is a `listFailedJobsByPathPrefix` (mirrors 599's `countByPathPrefix`). This is
  exactly the dead-end the incumbents leave open. *Honesty:* show the real per-file error.
- **(A1) "Folder unavailable" state.** When a watched folder's path is deleted/unmounted, render an
  honest **Unavailable — folder not found** (not stale "ready", not "0 files"). The add-preview already
  does a Head-side `Files.exists`; the row can carry the same per-poll check. *Critical guardrails
  (from the research):* re-evaluate existence every poll so re-mount clears it (avoid Syncthing's
  stale-error bug), and **never let "gone" trigger document deletion / a destructive reindex.**

**Strong — medium-high value, low cost, but honesty-sensitive (design carefully):**
- **(B2) Throughput-based fuzzy ETA on an indexing folder.** "~2 min left" from the existing
  `recentDocsPerSec` × the folder's `inFlightCount` — NOT a percentage (599's denominator-drift
  concern stands; a rate-based estimate sidesteps the denominator). Show it *only* when the rate is
  stable and non-zero; render fuzzily ("about…", or a band); hide when uncertain. Caveat to state:
  the queue is shared across folders, so it estimates queue-drain attributed to this folder.
- **(B3) Per-folder completion notification.** Extend 595 §15.3's global completion toast to
  "✓ '<folder>' finished indexing — N files searchable", but ONLY for a *user-initiated add the user
  navigated away from* (the row already showing ✓ is feedback enough otherwise — avoid redundancy);
  reuse the one 559 message channel; no stacking.

**Efficiency / simplify:**
- **(D1) Batch the per-folder count.** Today the live tick issues one `CountJobsByPathPrefix` RPC per
  folder; a single `countByPathPrefixes(List)` RPC removes N round-trips as folder counts grow. Pure
  efficiency, no UX change.

**Bigger bets (flagged — need more than a cheap extend):**
- **(B4) "Search in this folder."** The per-folder identity now exists; scoping search to a folder
  connects indexing-status to the product's actual value. Needs a folder filter on the search path
  (more backend than the above) — a separate design.
- **(C1) Per-folder re-index** (vs the global Reindex) and **(C2) per-folder pause** — validated by
  Syncthing/JetBrains, but our indexing is a single global queue, so per-prefix pause/reindex needs
  scheduler support; weigh before committing.

**Deliberately NOT pursuing:** a per-folder percentage/progress-bar (denominator drift, §10);
re-introducing the vector tier per folder (still gated on 598); a distinct indexing-vs-scanning glyph
(the count-down text already disambiguates; ambient-purity constraints).

### 15.4 The through-line
Every idea above stays inside 599's discipline: derive once in the `folderStatus` seam, state facts
honestly (fuzzy beats fake, re-check beats cache, actionable beats a dead-end count), and reuse the
existing record/RPC/message authorities rather than forking them. The single highest-leverage next
step is **B1 (actionable failed items)** — it is the gap the whole field leaves open and we already
hold every piece needed to close it.

> Sources: Syncthing GUI docs & community forum (folder states, out-of-sync items, folder-path-missing);
> Dropbox/OneDrive status-icon docs; DEVONthink & JetBrains indexing discussions; NN/g response-time &
> progress-indicator articles + practitioner ETA/notification guidance; AnythingLLM ingestion docs.

---

## 16. Long-term design for the §15 backlog (theorized; general, not implementation-level)

> Turns §15's research into a committed design, scoped to what the problem actually requires and
> grounded in what already exists. **Ownership correction up front (from the 602 coverage map):** the
> throughput/"available in ~Ns" ETA (§15 B2) is **owned by tempdoc 601**, not 599 — it is removed
> from this design (the folder row may *consume* 601's signal later, never compute it). 599 owns the
> per-folder journey: the *unavailable* state (A1) and *completion* (B3). **Failed-actionability (B1)
> is currently unowned**; this section claims it as the per-folder *failure* journey — the natural
> continuation of 599 and the field-wide gap §15 identified.

### 16.1 The core design — make indexing failures actionable per folder (B1)

**The problem, precisely:** 599 made the folder row show "N failed", but a count is a dead-end (the
exact place Syncthing et al. stop). The folder-facing journey is incomplete until the user can *see
which files failed and why, and retry them* — without leaving the folder.

**What already exists (reuse, do not fork):** per-job retry/cancel are fully built —
`core.retry-indexing-job` / `core.cancel-indexing-job` Operations (handlers + gRPC + REST), today
bound as item-operations on the live `core.indexing-jobs` Resource. The `core.failed-indexing-jobs`
Resource exists (TABULAR × ONE_SHOT, hashed paths) but carries **no item-operations**. `resolvePathLazy`
+ `core.resolve-path-hash` already turn a job's `pathHash` into a displayable path. The folder row's
"N failed" comes from the one `folderStatus` seam (599).

**The design — three moves, only one of which is new structure:**
1. **Make the existing failed-jobs Resource actionable.** Bind the *already-built* retry/cancel
   Operations as item-operations on `core.failed-indexing-jobs`. This is the missing wiring, not new
   logic — and it improves the *global* failed view too, not just the folder-scoped one.
2. **Add the one genuinely-required new backend signal: a prefix-scoped failed list** — the twin of
   599's `countByPathPrefix`. This is *forced*, not optional: ADR-0028 hashes paths on the wire, so the
   FE cannot filter the global failed list by folder; the filter must live where the raw paths do (the
   worker), exactly like the count. It mirrors the count's range-query shape — minimal, no new concept.
3. **The folder row's "N failed" becomes the entry point** to a *folder-scoped instance of the existing
   failed-jobs Resource view* — same rows, same per-item retry/cancel, just filtered to this folder
   (plus a "retry all in this folder" using the existing bulk path). The drill-down is a projection of
   an already-registered concept, not a second failures stream.

**Why this scope and no more:** the only new structure the problem requires is the prefix-scoped list
query (a privacy-forced twin of an existing query) plus binding two already-existing Operations to an
already-existing Resource. Everything else — the retry mechanism, the hash resolver, the status seam,
the Resource model — is reused. No new stream, record, authority, or `governance` concept (the failed
jobs are *already* a registered observed-happening concept; we scope it, we don't duplicate it — the
§15 instinct to register `core.folder-failures` is over-structure and is rejected).

### 16.2 Completing the truthful per-folder journey (A1 unavailable, B3 completion — both 599's)

These finish 599's own state machine and ride the existing `folderStatus` seam; neither is new structure.

- **A1 — "Folder unavailable" state.** Add one per-folder existence fact (reuse the add-preview's
  Head-side `Files.exists`, which already exists) → a new `unavailable` branch in the `folderStatus`
  seam ("Folder not found — reconnect the drive or remove it"). **Two guardrails the research made
  load-bearing:** (i) re-evaluate existence on *every* poll so a re-mounted folder clears the state
  (avoid Syncthing's documented stale-"path-missing" bug — never cache it); (ii) "gone" must *never*
  trigger document deletion or a destructive reindex (a real data-loss trap in sync clients). This is
  a truthfulness completion: today a deleted/unmounted folder shows a stale "✓ ready"; it should say so.
- **B3 — Per-folder completion notification.** Reuse 595 §15.3's single `emitEphemeralToast` channel
  (the 559 message authority), fired on the `indexing → ready` transition the seam already computes —
  gated by the §15/Round-2 anti-fatigue rules: only for a *user-initiated add the user navigated away
  from* (the row's own ✓ is feedback otherwise — no redundancy), and never stacked. No new channel.

### 16.3 Boundaries — what 599 must NOT own or fork
- **ETA / throughput → 601.** Do not compute a "~N min left" here. If/when 601 ships a throughput
  signal, the folder row *consumes* it (rendered fuzzily per NN/g — "about 2 min", suppressed when the
  rate is unstable); it never re-derives it. (This corrects §15 B2's placement.)
- **System-level transitions (rebuild / worker-restart) → 595's `Stability`.** Already consumed by the
  seam's `provisional` branch; the per-folder `unavailable` (A1) is a *distinct, folder-scoped* fact,
  not a new `Stability.cause`.
- **Bigger bets stay separate designs.** "Search in this folder" (B4) needs a folder filter on the
  search path — a different surface, not a cheap extend. **Per-folder pause/reindex (C1/C2)** is
  attractive (Syncthing/JetBrains validate it) but our indexing is a *single global queue* with no
  per-prefix scheduler; per-folder pause would require real scheduler structure the present problem
  does not include — so it is explicitly *not* designed here (no speculative scheduler abstraction).
- **Efficiency (D1):** batching the per-folder count into one `countByPathPrefixes(list)` call is a
  pure-efficiency refinement of 599's existing RPC (removes N round-trips as folders grow); fold the
  prefix-*list* (16.1.2) into the same batched shape so the live tick stays one round-trip.

### 16.4 The through-line
The whole design is one idea: **the per-folder status primitive (599) becomes actionable and complete
by extending existing authorities — the failed-jobs Resource, the retry Operations, the hash resolver,
the message channel, and the `folderStatus` seam — adding exactly one privacy-forced backend query
(failed-list-by-prefix) and zero new concepts.** The single highest-leverage piece remains B1: it is
unowned, it is the gap every comparable product leaves open, and after this design every piece needed
to close it either already exists or is a direct twin of something that does.

---

## 17. User-facing / frontend design for the §16 backlog

> §16 is substantially user-facing. This section grounds the frontend design in the **existing UI
> patterns** (inspected live in the demo harness + read from source), so each piece *reuses* a pattern
> rather than inventing one. What's user-visible: B1 (a clickable "N failed" → a failed-files
> drill-down with per-row retry), A1 (a folder "unavailable" row state), B3 (a completion toast).

### 17.1 What the inspection changed about the design
- **The folder row is inert today except Remove.** "N failed" is plain text *baked into the seam's
  `metaText` string* — so the B1 entry point requires the row renderer to render the failed count as a
  **structured, clickable affordance** from the seam's existing `failed` number, not the baked text.
  (Small render change; the seam already exposes the count.)
- **The drill-down surface already has a pattern — a right-drawer.** Confirmed live: the Advisories
  inbox slides in from the right with a header (title + a bulk action + ✕ close) over a content area,
  via the `TransientController` (`layer:'right-drawer'`, single-open). This is the B1 surface; no modal,
  no new primitive (respects the 574 modality/transient governance).
- **The failed-jobs view machinery exists but is never mounted.** `core.failed-indexing-jobs` is a
  TABULAR Resource with a generic `<jf-resource-view>` + per-row `<jf-row-actions>` (which renders a
  button per the Resource's declared `itemOperations`). A source comment literally says "mount via
  `<jf-resource-view resource-id="core.failed-indexing-jobs">`". So B1's list is mostly *mounting
  existing machinery*, once §16.1's two moves (bind retry/cancel item-ops + the prefix-scoped list) land.
- **Toasts already support an action button** (`emitEphemeralToast({actionLabel, onAction, severity,
  durationMs})` → `AdvisoryToastHost`), with auto-dismiss vs `REQUIRES_ACK`. So B3 can offer "Search
  this folder"/"View" without new toast structure.
- **"Unavailable" is not a row vocabulary today** (availability is a *control*-level concept). A1 is a
  new **folder glyph/state**, not a control-availability — extend the row's glyph set, don't reuse the
  button `Availability` type.

### 17.2 B1 — the failed-files drill-down (the headline surface)
- **Entry point:** in the `failed` row state, render "N failed" as a danger-toned **clickable chip/link**
  (reuse the existing error tone token, the same red as the alert glyph). It is the row's only new
  affordance besides Remove; the rest of the row stays a calm summary.
- **Surface:** clicking opens a **right-drawer** (`TransientController`, single-open) titled
  *"Failed — &lt;folder&gt;"* (resolved path), with a header bulk action **"Retry all"** (the analogue of
  the Advisories drawer's "Mark all read") and a ✕ close. The body mounts the existing
  `<jf-resource-view resource-id="core.failed-indexing-jobs">` **scoped to this folder** (via §16.1's
  prefix list), each row showing the **resolved file path** (`resolvePathLazy` / `core.resolve-path-hash`)
  + its **error message** + per-row **Retry / Cancel** (rendered automatically by `<jf-row-actions>`
  once those item-ops are bound to the Resource). 
- **Closing the loop:** after a retry, the row's `failedCount` drops on the existing live tick — no
  manual refresh; the drawer and the row stay consistent because both read the one folder-status signal.
- **Reuse vs new:** reuses the drawer primitive, the resource-view + row-actions machinery (today
  unmounted), the hash resolver, and the retry/cancel ops. New FE = the drawer wrapper, the clickable
  entry affordance, and the folder-scope wiring. This is the summary-row → right-drawer-detail pattern
  the app already uses, applied to failures.

### 17.3 A1 — the "folder unavailable" row state
- **Row:** a **distinct, muted glyph** (extend the folder glyph set with `unavailable` → a slash/ban
  icon in a *neutral/secondary* tone — deliberately NOT the red walk-error glyph, because the folder
  being gone is not an indexing failure) + meta *"Folder not found — reconnect the drive, or remove it"*
  (a reachable remedy, per the operability/reason discipline). The seam derives `unavailable` from the
  per-folder existence fact §16.2 adds; it sits distinctly from `failed` (walk error) and `empty`.
- **Honesty guardrails (visible behavior):** the state is re-derived every poll, so a re-mounted folder
  silently returns to its real status (no stale "unavailable"); and the row offers **no destructive
  auto-action** — Remove stays the only mutating control, the user decides. The row may dim slightly to
  signal "not contributing to search right now," but its last-known file count stays shown (last-known,
  not "0").

### 17.4 B3 — the per-folder completion toast
- Reuse `emitEphemeralToast`: `severity:'success'`, message *"✓ '&lt;folder&gt;' indexed — N files
  searchable"*, ~5 s auto-dismiss, optionally `actionLabel:'Search this folder'` (lands only when B4
  exists; otherwise omit the action). 
- **Anti-fatigue gating (from the research):** fire **only** for a *user-initiated add the user has
  navigated away from* — never for a folder already on-screen showing ✓ (the row is its own feedback;
  a toast would be redundant), never on reconnect/rebuild, at most once per add. The toast host already
  queues/again-dismisses, so no stacking.

### 17.5 Cross-cutting UX principles + boundaries
- **Summary on the row, depth in the drawer.** The row stays a truthful one-line status; per-file
  errors and actions live in the drawer — matching the app's existing summary→detail split.
- **Reachable reasons, never bare counts.** The unavailable meta states the remedy; the failed drawer
  shows each file's real error — the row's count is only an entry point, not the whole story.
- **One visual language.** Reuse the existing glyph/tone tokens, drawer chrome, toast chrome, and
  row-actions — A1/B1/B3 introduce no new visual primitives.
- **Boundaries:** ETA text on an indexing row is **601's** signal to provide (rendered fuzzily when it
  lands, never computed here); system-wide rebuild/restart stays 595's `provisional` row treatment;
  per-folder pause/reindex (needing a per-prefix scheduler) is out of scope. The B1 drawer is a
  right-drawer, **not** a modal (574 modality governance).

### 17.6 The one implementation note this surfaces
The only place 599's *shipped* FE needs a small change to enable this layer is the **entry point**: the
folder-status seam should expose its `failed` count to the row renderer as a structured field the row
can render as a clickable affordance (it already returns the number; today the row only shows it inside
the baked `metaText`). That single seam-to-renderer adjustment unlocks B1 without touching the seam's
derivation logic.

---

## 18. Confidence-building verification of the §16/§17 design (2026-06-17)

Per `verify-dont-guess`, the §16/§17 reuse claims were checked read-only before any implementation.
Verdicts (corrections folded back into the design's understanding):

- **U1 [B1 crux — CORRECTED]:** `<jf-resource-view>` fetches a FIXED Resource endpoint (no per-instance
  scope param; the ONE_SHOT fetcher builds `baseUrl+endpoint`), and `/api/indexing-jobs/failed` takes
  only `limit`. So the drill-down **cannot** be a folder-scoped resource-view mount. **Corrected:** the
  drawer does a bespoke fetch of a NEW folder-scoped endpoint — which confirms §16.1's
  `listFailedJobsByPathPrefix` is *mandatory* (hashed paths preclude FE filtering), not optional — and
  renders rows itself, reusing only `<jf-row-actions>` for the per-row buttons.
- **U2 [B1 — CONFIRMED partial]:** the `<jf-resource-view>` + `<jf-row-actions>` + `itemOperations` path
  IS live (ActivitySurface → `core.operation-history`), so the per-row action wiring is proven; the
  failed-jobs Resource's `itemOperations` is empty ("V1 scaffolding — per-row retry could be added in a
  follow-up"), so binding retry/cancel there is the planned move. Net: reuse `<jf-row-actions>` (proven),
  not the resource-view (corrected by U1).
- **U3 [B3 — CONFIRMED GAP]:** the always-on `aiStateStore` carries ONLY global counters; per-folder
  data is fetched ONLY by `LibrarySurface` (stops on unmount). 595's global toast rides the always-on
  verdict in `StatusDeck` (chrome-level). So a per-folder completion toast "when the user navigated
  away" needs a NEW always-on detection home (a small per-folder poller in the store, statusPoll-style)
  or a backend completion event — **not** "just reuse the toast." **Corrected:** B3's cost rises from
  trivial to moderate; re-scope to either (a) "only while the Library is mounted" (cheap, but the row's
  own ✓ is already the feedback then) or (b) the always-on poller. B3 drops below B1/A1 in priority.
- **U4 [A1 — CORRECTED, risk avoided]:** a per-poll `Files.exists` per root on the Head request thread
  BLOCKS on dead/unmounted (esp. UNC) paths (no timeout pattern exists). **Corrected:** do NOT add a
  per-poll probe — derive "unavailable" from the EXISTING walk-failure signal (`markWalkFailed` already
  records a `walkError` when an unmounted folder's walk fails; today it shows the `error` state). A1
  becomes: distinguish a *path-missing* walkError from other errors and present it as the calmer
  "unavailable" + remedy — reusing an existing signal, no new blocking probe.
- **U5 [A1 — CONFIRMED, MAJOR + standalone bug]:** the system ALREADY deletes indexed docs when a
  folder/files go missing — the watcher's DELETE events (`WatcherEventOps.handleDelete`) and sync
  orphan-prune (`SyncDirectoryOps.pruneOrphansForSync` → `pruneByPathPrefix`; force-sync skips the
  user-active gate). So unmounting a watched folder today **silently deletes its index** (a data-loss
  defect — logged to `observations.md`). **Corrected:** A1 is therefore NOT display-only — to be safe
  it must be **protective** (gate the watcher-delete + sync-prune for a root that is merely *unavailable*
  vs genuinely *removed*, and restore on remount). A1's truthful glyph is cheap; its safety is the real
  work, and it is coupled to the discovered bug.
- **U6 [B1 — CONFIRMED degraded, acceptable]:** `resolvePathLazy` returns null on `found=false` → show
  `[hash…]`. Failed rows carry `errorMessage`, so the drawer still identifies a file by its error even
  when the path is unresolvable (the files most likely to be deleted). Drawer row = resolved-path-or-hash
  + error.
- **U7 [B1 entry — CONFIRMED clean]:** add a `failedCount` field to the `FolderCard` interface + a new
  intent event mirroring `jf-folder-card-remove`; no conformance test pins the card shape. Small, in-pattern.
- **U8 [governance — CONFIRMED bounded]:** drawer = compose `TransientController` (right-drawer) + add to
  `governance/transients.v1.json` adopters (the gate requires composition, not a separate row); glyph =
  extend `FolderGlyph`/`FolderState` + `statusIcon` + reuse an existing icon (`x-circle`) + a tone CSS
  class. Both are bounded checklist items.
- **U9 [testability — CONFIRMED good]:** `folderStatus` is a pure function (the new `unavailable` state +
  failed-entry are unit-testable); `AdvisoryToastHost` has a stub-test pattern + a `.visible` accessor;
  no live backend is needed for the seam/toast logic (the drawer needs a component test). The contended
  live stack is not a blocker for the bulk of verification.

### 18.1 Net recalibration of the backlog
- **B1 (actionable failures):** SOUND, slightly larger than "mount machinery" — a bespoke drawer + the
  (now-confirmed-mandatory) prefix-list endpoint + reuse of the proven `<jf-row-actions>`. Still the
  top, highest-leverage, bounded item.
- **A1 (unavailable):** the DISPLAY is cheap (reuse `walkError`, no blocking probe), but SAFETY is the
  real scope — it must protect against the existing delete-on-missing behavior, and it surfaced a
  standalone data-loss bug (logged). Bigger and more important than first framed.
- **B3 (completion toast):** cost rose (needs an always-on per-folder home) → lowest value/cost ratio;
  defer, or scope to Library-mounted only.
- **D1 (batch count) and the B2→601 boundary:** unchanged.

### 18.2 Critical confidence rating for the remaining work: **6.5 / 10**
Per-item: **B1 ≈ 7.5** (patterns proven; the one new endpoint is a known twin; the only correction is
"bespoke drawer, not resource-view"). **A1 ≈ 5.5** (display trivial, but the protective coordination
with the watcher/sync-prune — and the coupled data-loss bug — is real backend work whose blast radius
needs its own care). **B3 ≈ 6** (mechanically clear, but needs an always-on home it doesn't have today).
The investigation did its job: it converted two "cheap reuse" items into correctly-scoped work and
turned up a pre-existing data-loss defect — all before any code. Residual risk is concentrated in A1's
protective coordination; B1 is ready to implement with high confidence.

## 19. As-built — B1 + A1 implemented (2026-06-17, worktree `599-followup`, NOT merged)

Implemented the §16/§17/§18 backlog per the recalibration: **B1** (actionable per-folder failures) and
**A1** (truthful "unavailable" + the data-loss-bug fix). **D1** and **B3** remain deferred per §18.

### 19.1 B1 — per-folder failed-files drill-down
- **Backend (the §18-confirmed-mandatory prefix-list endpoint, twin of 599's count):**
  `SqliteJobQueue.listFailedJobsByPathPrefix(prefix, limit)` (the `listFailedJobs` projection + the
  half-open range predicate from `countByPathPrefix`) → `JobQueue` default → proto
  `ListFailedJobsByPathPrefix` → `GrpcIngestService` impl → `DelegatingIngestService` forward →
  `RemoteKnowledgeClient` → `IndexingService` default → Head `GET /api/indexing-jobs/failed/by-prefix`
  (`handleListFailedJobsByPathPrefix`, maps FE `pathHash`→raw root via `sha256Hex` over
  `getWatchedRoots()`, shapes the substrate `IndexingJobView`). Route + dev allowlist registered.
- **FE:** `state/failedJobsDrawer.ts` (open/close + `folderPathHash` store) +
  `components/FailedJobsDrawer.ts` (`<jf-failed-jobs-drawer>`, composes `TransientController`
  `right-drawer`/`failed-jobs`; fetches the endpoint; one row per failed file = `resolvePathLazy`
  display + `errorMessage` + per-row actions + "Retry all"). **Move 1 (§16.1) — DONE:** the
  `core.failed-indexing-jobs` Resource now declares the retry/cancel `itemOperations` (was empty V1
  scaffolding), so the per-row actions REUSE the shared `<jf-row-actions>` over the folder-scoped
  instance — and the operator's GLOBAL failed view is now actionable too. The Resource keeps
  `Audience.OPERATOR` for its global view (slice 481 §7 — global triage is admin work); per-op audience
  governs invocability (the retry op is user-invocable, so `<jf-row-actions>` dispatches it with
  `{pathHash: rowKey}`). The drawer drops a row on `row-action-success`; "Retry all" loops the same op.
  Mounted in `chrome/Shell.ts`; registered in `governance/transients.v1.json`. Entry point:
  `FolderCardRenderer` + `LibrarySurface`
  render a clickable danger-toned "N failed" chip (when `failed>0`) emitting `jf-folder-card-show-failed`
  → `openFailedJobs(pathHash)`. (Card field named `failed`, not `failedCount`, to stay clear of the
  `folder-status-derivation` raw-field gate.)
- **Verification:** queue unit test (`JobQueueTest.listFailedJobsByPathPrefix_filtersByPrefixAndState`);
  Resource conformance test (`FailedIndexingJobsResourceCatalogTest` — retry/cancel item-ops bound,
  audience stays OPERATOR); the by-prefix endpoint live-confirmed responding. **A real FAILED indexing
  job could not be manufactured** (read-deny / zero-byte files still extracted cleanly), so the
  chip→drawer→retry **UI is covered by a deterministic component test** (`FailedJobsDrawer.test.ts`, 5
  tests: load→render rows with errors + the per-row `<jf-row-actions>` reuse + "Retry all"; empty state;
  a `row-action-success` drops the row; "Retry all" invokes `core.retry-indexing-job` per file) **in lieu
  of a live chip reproduction** — the one verification tier the absent live failure blocked, substituted
  by the strongest available deterministic tier.

### 19.2 A1 — truthful "unavailable" + the pre-existing data-loss fix
- **Display (U4-honoring, post-review design):** `RootLifecycleOps` keeps a **non-blocking
  folder-availability cache** refreshed on the background `walkExecutor`; `getWatchedRoots()` (request
  thread) only READS the cached boolean and surfaces a **synthetic path-missing `walkError`** when a root
  is gone — reusing the `walkError → "unavailable"` signal U4 pointed to, with the freshness the bare
  signal lacked (the walk does NOT re-fire when a folder is unmounted after its last good walk, which is
  why reusing `walkError` alone left a stale "✓ indexed"). `IndexingController` derives `unavailable`
  purely from the path-missing `walkError` — **no `Files` stat on the request thread**, so U4's
  dead-UNC-mount blocking hazard is avoided. `folderStatus.ts` maps `unavailable` → muted x-circle +
  "Folder not found — reconnect the drive, or remove it" (a reachable remedy), and (§17.3) appends
  "· last known N files" when the count was previously observed (never a "0 files"). Continuous
  background re-evaluation means a re-mounted folder clears the state on its own (guardrail (i)).
  - **Why this differs from the first cut (the value of the browser tier):** the first implementation
    used a direct `Files.isDirectory` on the request thread (first gated behind `?counts=true`, which the
    counts-free live tick overwrote; then run on both paths). The review flagged that this reintroduced
    the exact per-poll request-thread probe §18/U4 had deliberately ruled out. The fix above moves the
    existence check off the request thread entirely — honoring U4 AND keeping the live view truthful.
- **Safety (the load-bearing part — the pre-existing data-loss bug):** `SyncDirectoryOps.pruneOrphansForSync`
  (worker, the single prune choke point) now skips when `!Files.isDirectory(rootPath)`, and
  `WatcherEventOps.handleDelete` (Head) skips a cascade-delete when the watched root no longer exists —
  so a merely-unavailable (unmounted/renamed) folder is never orphan-pruned.
- **Verification (real-UI, the success criterion):** live on a running stack — add two folders, index
  both, **rename one offline while running (no restart)**: the counts-free **live tick** flips it to
  `unavailable` within ~3 polls via the background cache (one-poll lag, no request-thread stat); the live
  Library renders it as muted x-circle "Folder not found — reconnect the drive, or remove it · **last
  known 2 files**" beside the healthy "✓ indexed" folder (screenshots captured); **remounting it clears
  the state** back to indexed (guardrail (i)); and the offline folder's indexed docs **remain searchable**
  (`/api/knowledge/search` returns `drop-b\d1.txt`/`d2.txt`) — the data-loss guard holds. Folder-status
  `unavailable` (with/without last-known count) covered by `folderStatus.test.ts`. (Restart drops missing
  roots — the loader filters non-existent paths — so "unavailable" is reproduced by taking a folder
  offline WITHOUT a restart.)

### 19.3 Governance / validation
`build -x test` (all discipline gates incl. `class-size` — 3 mega-files re-pinned to new exact LOC with a
`declared-growth` changeset covering the threaded `ListFailedJobsByPathPrefix` method + the pin-bump;
`RootLifecycleOps`/the failed-jobs catalog additions stay under their ceilings),
`transient-arbitration` + `folder-status-derivation` gates green, the affected Java module suites
(`indexer-worker`/`worker-services`/`app-services`/`app-api`/`ui`/`app-observability`) green, FE
`typecheck` clean, FE unit suite **3165 passed**. A post-review pass (§19.1/§19.2) corrected three
design-alignment findings (the U4 request-thread probe → background cache; Move 1 item-op binding +
`<jf-row-actions>` reuse; the last-known count) — all re-verified live. Branch `worktree-599-followup`,
**not merged** (per the goal).

### 19.4 Deferred (unchanged from §18)
- **D1 (batch the per-folder count RPC):** small efficiency; not implemented this pass.
- **B3 (per-folder completion toast):** its only valuable form ("notify when the user has left the
  Library") needs a new always-on per-folder poller `aiStateStore` does not have — disproportionate for a
  toast; the in-Library form is redundant with the row's own ✓. Deferred by design.
