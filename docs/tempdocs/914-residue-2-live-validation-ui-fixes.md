---
title: "Residue-2 live validation: three UI defects (failed-files drawer layout, failed-chip flicker, Add Folder has no collection)"
type: tempdocs
status: "IMPLEMENTED (2026-09-02) — all three items shipped with unit tests (falsified) + live verification on a running stack; open items in §O"
created: 2026-09-02
updated: 2026-09-02
lane: resid3-ui
model: fable (implementation)
parent: 911-wave1-residue-ui-contract-and-a11y
related:
  - 909-wave1-residue-head-api-and-wire      # the residue-2 wave this validation followed
  - 910-wave1-residue-governance-kernel
  - 912-wave1-residue-worker-watcher-and-commit-floor
  - 813-indexing-progress-queue-ux-design    # the projection-must-not-lie discipline D3 is bound by
  - 906-failure-ux-coherent-surface          # failure wording / reachability of a failure drill-down
  - 599-library-surface-truthfulness         # §16/B1 the failed chip + drawer, §9.1 the folderStatus seam
  - 811-collection-identity                  # IngestCollectionPolicy — the reserved names D4 refuses
---

> Implementation tempdoc for three defects a live product validation of the residue-2 work
> (911/912, PRs #611-#615) found in Chrome against a real running stack.
> §A the validator's findings verbatim · §B verification of every cited line (with corrections) ·
> §C design decisions · §D implementation · §E live evidence · §F falsification ·
> §G verification lines · §O open items routed out of scope.

# 914 — Residue-2 live-validation UI fixes

## §A — Findings as reported (verbatim from the validator's brief)

**D2 (S2).** "The Failed-files drawer collapses the path column to 22 px. Measured live: `.row` =
373 px, sibling `<jf-row-actions>` = 339 px, so `.row-info`/`.row-path` gets 22 px wide x 585 px tall
(one character per line; one row is 822 px tall). `.row-info { flex: 1; min-inline-size: 0 }` shrinks,
the two full-width buttons ("Retry indexing job", "Cancel indexing job") never do.
`modules/ui-web/src/shell-v0/components/FailedJobsDrawer.ts:236-252` (`.row`/`.row-info`/`.row-path`)
and `:339-343` (`<jf-row-actions>`); the drawer slot is 417 px."

**D3 (S3).** "The 'N failed' chip and its drawer drill-down flicker. With 2 permanently failing files
and nothing pending, 60 samples of `/api/indexing-roots/substrate` at 300 ms gave `inFlight=0 failed=2`
x57, `inFlight=2 failed=0` x2, `inFlight=1 failed=1` x1: the retry ladder re-queues FAILED jobs, and
`modules/ui-web/src/shell-v0/.../folderStatus.ts:241-250` (the `inFlight > 0` branch, which outranks the
`failed > 0` branch at :259) then renders `default · Indexing · 2 remaining` and drops the chip, so the
drill-down is unreachable in those windows."

**D4 (S3).** "Add Folder has no collection affordance. `LibrarySurface.ts:799` invokes
`core.add-watched-root` with `{ path }` only, so the Head assigns `"default"`
(`IndexingController.java:142`) although the endpoint accepts `collection` (:163-189) and the wave-1
fix (#605) made the label persist."

## §B — Verification of every cited line (source-verbatim, this base = `a83de156`)

| Claim | Verdict | Evidence |
|---|---|---|
| D2 `.row` / `.row-info` / `.row-path` at `FailedJobsDrawer.ts:236-252` | **Confirmed, off by one** | the block is `:237-252` (`.row` opens at 237, `.row-path` closes at 252). Immaterial. |
| D2 `<jf-row-actions>` at `:339-343` | **Confirmed, off by one** | the element spans `:338-342`. |
| D2 "the drawer slot is 417 px" | **Confirmed, and explained** | `.panel { width: 26rem }` at `FailedJobsDrawer.ts:205` = 416 px + the 1 px `border-left` = 417 px. |
| D2 "the buttons never shrink" | **Confirmed** | `RowActions.ts:102-108` `:host { display: inline-flex }` with no `flex-wrap`; each `<jf-action-button>` is `display: inline-block` (`ActionButton.ts:96-100`) around a `button.invoke` whose text is the operation's full catalog label. |
| D3 `inFlight > 0` branch at `folderStatus.ts:241-250`, outranking `failed > 0` at `:259` | **Confirmed exactly** | `if (inFlight > 0) {` is line 241; `if (failed > 0) {` is line 259. |
| D3 "the substrate cannot distinguish a retry" | **Confirmed** | `IndexedRootView` (generated from `SSOT/schemas/indexed-root.v1.json`) carries only `inFlightCount` / `failedCount` as job columns — no `attempts`, no `retryAfterMs`, no state histogram. The Head fills both from one worker `GROUP BY` (`IndexingController.java:840-841`, `jobCounts.inFlight()` / `.failed()`). The per-job fields the validator asked about (`state`, `attempts`, `retryAfterMs`) exist ONLY on the drill-down listing `/api/indexing-jobs/failed/by-prefix`, which lists FAILED rows — so during a retry window it returns `{"jobs":[],"count":0}` and cannot rescue the count either. **A substrate-truthful rule is therefore not available at this granularity**; the FE-memory rule in §C is bounded instead. |
| D3 sample distribution | **Reproduced independently** | my own 60 samples at 300 ms on the same root: `inFlight=0 failed=2` x56, `inFlight=2 failed=0` x4 (`tmp/resid3-fixtures/d3-before.txt`). Same phenomenon, slightly different split. |
| D4 `LibrarySurface.ts:799` sends `{ path }` only | **Confirmed exactly** | `:799` `await this.invoke(OP_ADD, { path });` — and `:784` `await this.invoke(OP_ADD, { path: picked });` is the Tauri twin the finding does not name but shares the defect. |
| D4 "the Head assigns `default` (`IndexingController.java:142`)" | **Corrected** | `:142` is `handleListRoots`' PROJECTION default (`entry.put("collection", root.collection() != null ? … : "default")`) — it is what the LIST shows, not what the ADD stores. The default that actually applies to this surface is `AddWatchedRootHandler.java:50-54` (`collectionNode … : "default"`), because LibrarySurface invokes the **Operation**, not the REST route. |
| D4 "the endpoint accepts `collection` (:163-189)" | **Confirmed for the REST route only** | `IndexingController.handleAddRoot` reads `collection` at `:163` and validates it through `IngestCollectionPolicy.normalizeRequested` at `:180`. **The Operation handler does not**: `AddWatchedRootHandler.java:50-54` takes the string as-is with no policy call. See §O-1. |
| D4 "is the operation's arg schema governed?" (possible blocker) | **Not blocked** | `governance/operation-surfaces.v1.json` contains no `add-watched-root` entry; `CoreOperationCatalog.java:138-144` declares the ref and documents the args in prose only (`{"path": string, "collection"?: string}`); `AddWatchedRootHandler` parses free-form JSON. Passing `collection` needs no governance edit. |
| D4 allowed charset / length for `collection` | **There is none** | `IngestCollectionPolicy.normalizeRequested` (`:68-85`) rejects exactly two things: a supplied-but-blank value, and a RESERVED name (`justsearch-help`, `agent-history`, trimmed + lowercased, `:43`/`:56-58`). No charset or length rule exists, so the FE must not invent one. |

## §C — Design decisions

**D2 — stack the row; wrap the strip.** The path is the row's identity, so it takes the full width and
the actions sit under it. That is already the house shape for a per-item action inside this same 26rem
right-drawer slot (`AdvisoryInboxDrawer.ts:256-259`, `.item-detail .action-btn` with a `margin-top`
under the item's text). Two changes, at the altitude each belongs to:

- the drawer's own row becomes a column (`FailedJobsDrawer.ts`), and
- `jf-row-actions` gains `flex-wrap: wrap` on its `:host` (`RowActions.ts`) — the strip is ALSO a
  `ResourceView` TABULAR cell, so "does not overflow a narrow host" is a property of the shared
  component, not of one drawer's copy of it.

`ActionButton`'s `:host` bottom margin is a form-control spacing meant for stacked fields; the drawer
withdraws it through the component's own token (`--justsearch-shell-form-control-spacing: 0`) rather
than by overriding the component's rule.

**D3 — bounded FE memory, and the chip says which kind of number it shows.** §B establishes that no
wire field distinguishes a retry, so the substrate-truthful option the brief preferred does not exist
at root granularity. The rule:

- the sample's OWN count always wins when it has one (`failed > 0`);
- only when the sample reports ZERO failures *and* the queue is in-flight does the last settled count
  carry the chip through the window;
- the carried value is flagged `failedIsLastKnown`, and the chip's accessible name becomes
  `Show 2 failed files (last known — this folder is indexing right now)`. Both halves are true at that
  instant: the queue IS working on N jobs (the meta line keeps saying so) and the failed count IS a
  last-known number. This is the same honesty move the seam already makes for a disconnected folder
  (`folderStatus.ts:228`, "last known N files") — 813/906 forbid asserting a stale fact as present, not
  showing a last-known one as last-known.

The BOUND is a pure exported fold, `rememberFailedCounts(previous, rows)`, so the retention rule is
testable rather than being invisible surface state: a reported count refreshes the memory; a zero with
work in flight carries it; **a zero with a SETTLED queue discharges it**; a root absent from the poll is
dropped. No entry can survive one drained observation of its root, and the map cannot outgrow the
current listing.

**D4 — one add form for both modes, and "open the flow" ≠ "submit it".** The optional `Collection`
field lives in the existing add row.
The native-picker branch previously added the root the instant a folder was chosen, which left no place
to put the field on the real desktop product; it now fills the same form (path pre-filled, preview
fetched) and the user confirms with **Add**. That costs desktop users one click and buys them the
preview (file count / already-watched) they never saw before, plus the affordance.
Blank ⟹ the argument is **omitted**, not sent as `""` or as a literal `"default"` — the handler's own
rule is "absent or blank ⟹ default", and storing an explicit `"default"` would be a second spelling of
the untagged bucket. A reserved name is refused with a reachable reason (`unavailableBecause`) using
the FE mirror of `IngestCollectionPolicy` that already existed in `otherSources.ts`, promoted from a
private Set to an exported `isReservedCollection()` rather than re-declared.

The **critical-analysis pass caught a wrong-gate in my own first cut** of this: the picker branch was
re-entered as `if (!this.showManualInput && capabilities.has('folder-picker'))`, one method serving
both "the header's Add Folder" and "the form's submit". With the form open, a header click then
SUBMITTED instead of re-opening the picker — so in Tauri mode the picker became unreachable for the
rest of the flow, and in browser mode the header button silently doubled as Add (a pre-existing
oddity the flag would have entrenched). Split into `handleAddFolderClick()` (open the flow: pick, or
just show the form) and `handleAddRoot()` (submit it); two tests pin the split.

## §D — What changed

| File | Change |
|---|---|
| `modules/ui-web/src/shell-v0/components/FailedJobsDrawer.ts:237-266` | `.row` → column; `.row-info` → `inline-size: 100%`; `.row jf-row-actions` withdraws the form-control spacing and caps at `max-inline-size: 100%` |
| `modules/ui-web/src/shell-v0/components/RowActions.ts:102-115` | `:host` gains `flex-wrap: wrap` |
| `modules/ui-web/src/shell-v0/state/folderStatus.ts` | `FolderStatus.failedIsLastKnown?`; `FolderStatusContext.lastKnownFailed?`; the in-flight branch carries a last-known count; new exported `failedChipLabel()` and `rememberFailedCounts()` |
| `modules/ui-web/src/shell-v0/views/LibrarySurface.ts` | folds the memory in `refresh()`, passes `lastKnownFailed` at both `folderStatus` call sites, labels the chip through `failedChipLabel`; `pendingCollection` state + input, `addRootArgs()`, reserved-name gate in `addAvailability()`, `handleAddFolderClick()` split out of `handleAddRoot()` with the picker filling the form, `aria-label` on the path input, `.add-row` wraps |
| `modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.ts` | `FolderCard.failedLastKnown`; chip label via the shared `failedChipLabel` |
| `modules/ui-web/src/shell-v0/state/otherSources.ts:73-81` | `isReservedCollection()` exported (the private Set is unchanged and still used at `:134`) |
| new tests | `FailedJobsDrawer.layout.test.ts`, `folderStatus.failedChip.test.ts`, `LibrarySurface.addCollection.test.ts` |

## §E — Live evidence (stack on `127.0.0.1:50656`, worktree FE served on `:5174`)

**D2, measured through the shadow DOM with `getBoundingClientRect` after the fix:**

| Element | Before (validator) | After |
|---|---|---|
| `.panel` | 417 px | 417 px |
| `.row` | 373 px wide, 822 px tall | **384 x 127** |
| `.row-info` | **22 x 585** | **384 x 58** |
| `.row-path` | 22 px wide | **384 x 39** (the full path on 2 lines) |
| `jf-row-actions` | 339 px, beside the path | 339 px, **below** the path (`flex-wrap: wrap`, buttons 162 px + 173 px) |

`getComputedStyle(row).flexDirection === 'column'`. The row-width move 373 → 384 is NOT the CSS: at
822 px per row the 895 px drawer body needed a scrollbar for 2 rows, and at 127 px it does not — the
11 px is the scrollbar returning to the content.

Screenshot of the fixed drawer: both rows show the full path, the error line, and both buttons
underneath, with no horizontal starvation (`Failed files` panel, two `…\resid2-live\z.pdf` /
`…\y.docx` rows).

**§E.2 — D3, two simultaneous observations of the same live root (2 permanently failing files):**

- The WIRE, 400 samples of `/api/indexing-roots/substrate` at 300 ms:
  `inFlight=0 failed=2` **x392** · `inFlight=2 failed=0` **x6** · `inFlight=1 failed=1` **x2**.
  The retry window is real, permanent (these files can never succeed) and ~1.5% of samples.
- The RENDERED ROW over the same window (45 in-page samples across ~188 s, i.e. ~47 of the surface's
  4 s live refreshes):

  | rendered state | count |
  |---|---|
  | chip `2 failed`, `data-last-known="false"`, meta `default · 200 files` | 39 |
  | chip `2 failed`, `data-last-known="true"`, meta `default · Indexing · 2 remaining` | **4** |
  | chip `2 failed`, `data-last-known="false"`, meta `default · Rebuilding… · last known 200 files` | 2 |

  **The chip was present in 45/45 samples**, and the drill-down was therefore reachable throughout.
  The 4 `data-last-known="true"` renders are the fix engaging: that attribute can ONLY be set when the
  poll that produced the render reported `failedCount === 0`, so those are exactly the renders that
  showed **no chip at all** before this change — and the meta line stayed honest about what the queue
  was doing while the chip stayed reachable.

**§E.3 — D4, end to end on the live stack** (fixture root
`…\resid3-ui\tmp\resid3-fixtures\resid3-live`, 2 files, added through the served worktree FE):

- The add form rendered the path input, the new **Collection (optional)** input, Add + Cancel, and the
  preview line `Folder found · 2 files`; with `resid3-live` typed the Add availability was
  `{kind: "available"}`.
- After Add, `GET /api/indexing/roots` →
  `{"path":"F:\\…\\resid3-fixtures\\resid3-live","collection":"resid3-live","lastIndexed":"2026-09-02T21:08:57.808310Z"}`,
  and the folder row's meta line read `resid3-live · Indexing · 2 remaining` (the label, not `default`).
- After drain, `POST /api/knowledge/search {"query":"zarbatron"}` returned both fixture files with
  `fields.collection = "resid3-live"` (ranks 1-2); every other hit stayed `default`.
- The fixture root was then removed through the UI's own Remove + confirm; `/api/indexing/roots` is
  back to the single pre-existing root and the search returns **0** `resid3-live` hits. The shared
  stack is in the state it was borrowed in.
- Re-verified live AFTER the `handleAddFolderClick` / `handleAddRoot` split (§C): the header opens the
  form (path input 1166 px, collection input 194 px — the path keeps the width), and a SECOND header
  click leaves the form open and adds nothing (`/api/indexing/roots` still 1 root).

**§E.4 — a11y.** `python -m jseval ui-a11y-gate --ui-url http://localhost:5174` (fixtures mode,
against the worktree FE) → `exit_code: 0`, `clean — no NEW a11y violations vs baseline`,
**20 surfaces ok / 0 ERROR** (including `library` and `library-enriching`).

## §F — Falsification (every new test broken once, watched fail, restored)

| Test | Mutation | Observed failure |
|---|---|---|
| `.row is a COLUMN…` | removed `flex-direction: column` + restored `flex: 1` on `.row-info` | `AssertionError: expected '' to be 'column'` |
| `jf-row-actions wraps…` | removed `flex-wrap: wrap` from `:host` | `AssertionError: expected '' to be 'wrap'` |
| `sample B … the chip stays` | `const carriedFailed = failed;` (drop the carry) | `AssertionError: expected +0 to be 2` |
| `sends collection when the user names one` | `addRootArgs` → `return { path };` | `AssertionError: expected { path: … } to deeply equal { path: …, …(1) }` |
| `refuses a RESERVED collection` | deleted the `isReservedCollection` guard | `AssertionError: expected 1 to be +0` (the add went through) |
| `the native picker fills the SAME form` + `the header button re-opens the picker` | pointed the header button back at `handleAddRoot()` (the pre-split shape) | all six D4 cases fail — `AssertionError: .add-row input must be present in the add form: expected null to be truthy` (the header no longer opens the flow at all) |

## §G — Verification commands (verbatim results)

- `npm run typecheck` → clean (no output past the tsc banner).
- `npm run test:unit:run` → `Test Files 466 passed (466)` / `Tests 6253 passed (6253)`.
- `node scripts/ci/run-ui-web-gates.mjs` → `ui-web gates: 40/40 passed`.
- `npm --prefix modules/ui-web run knip:report` + `node scripts/governance/run.mjs --gate dead-code --mode gate`
  → `governance: 1 gate evaluated, 0 fail, 12 findings` / `dead-code: pass`. None of the three new
  exports appears in `tmp/knip-report.json`, so no changeset and no baseline advance are due.
- `node scripts/ci/check-ui-step-coverage.mjs` → `ui-step-coverage gate OK — 44 step-index source path(s) …`.
- `node scripts/ci/check-tempdoc-numbers.mjs` → `OK — … no collisions across 11 worktree(s) + origin/main`.
- `python -m jseval ui-a11y-gate --ui-url http://localhost:5174` → `exit_code: 0`, 20 ok / 0 ERROR (§E.4).
- Diff hygiene: `git diff origin/main | grep -P '^\+.*[^\x00-\x7F]'` shows only intended typography
  — the added non-ASCII set is exactly `§ · — … → ≠ ≥ ⟹` (prose and comments);
  `git diff origin/main | grep -cP '\x00'` = **0**.

## §O — Open items (routed, not investigated further)

**§O-1 — `core.add-watched-root` bypasses `IngestCollectionPolicy`.** The REST route
`POST /api/indexing/roots` validates a supplied collection through
`IngestCollectionPolicy.normalizeRequested` (`IndexingController.java:176-186`), but the Operation
handler every UI and agent path actually uses does not
(`AddWatchedRootHandler.java:50-54` assigns the raw string). An agent invoking the operation can
therefore create a watched root tagged `agent-history` and have its documents inherit that corpus's
default-EXCLUDED search posture — exactly what 811 C-2a's reserved list exists to prevent. This lane
owns `modules/ui-web/**` only, so the fix (route the handler through the same policy, mirroring the
REST branch) belongs to whoever owns `modules/app-services/**`. The FE guard added here closes the UI
path only; it is not the authority.

**§O-2 — the Tauri add flow now needs one confirm click.** Deliberate (§C), but it is a product-visible
change to the desktop path that no live desktop run in this lane could exercise (browser mode only).
Worth a look on the next installer smoke.
