---
title: "post-arc platform hygiene sweep — the small, enumerated debts the 2026-07-22 arc surfaced, bundled so they don't evaporate"
type: tempdocs
status: "ALL 6 ITEMS DISPOSED (2026-07-28). Items 1/2/5 shipped in PR #298 (dbba0291); items 3/4a in PR #300 (28d1dd31) with 4b refuted-as-charted; this PR closes the re-scoped 4b caller hunt (2 real bare-request callers found and migrated — the F-037 candidate was wrong), records item 1's re-measured false-positive rate (6 flagged, 3 true-stale / 3 FP = 50%, gate-mode stays OFF), and records item 6's queue dispositions. Every §A item now carries a dated disposition line; §B acceptance met. Awaiting founder review — NOT merged."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: platform / hygiene
related:
  - 772 (installer payload)   # item 2's origin (ORT pack completeness check vs dev staging)
  - 767-camouflaged-injection-corpus-lane  # item 4's origin (harness drift observed during re-pins)
---

## §A. Items (each closes independently; the lane closes when all do)

1. **Tempdoc status-vs-merged linter.** 770's frontmatter still reads "NOT merged — awaiting
   owner" though #268 merged; stale statuses actively mislead agents (nearly did on
   2026-07-22). Build a light check: a tempdoc whose status contains a merge-pending marker
   ("NOT merged", "awaiting", "on branch") while a merged PR references its number gets
   flagged (report-mode first; gate-mode only if the signal proves precise — avoid a nag
   that trains its own discounting, per the docs-granularity-hint lesson).
   **DONE 2026-07-22 (PR #298, commit `dbba0291`) — `scripts/ci/check-tempdoc-status-staleness.mjs`,
   report-mode, always exit 0, offline (local git log, no GitHub API); wired to nothing.**
   **FP measurement re-run on the current corpus 2026-07-28 (§B acceptance clause):** 6 flagged,
   **3 true-stale / 3 false positives → FP rate 50%** — unchanged from the #298 measurement, and
   the same two FP classes:
   - *true-stale (3):* #675 (`"IMPLEMENTED … on branch worktree-675-executor-v2"`, merged
     `483e47bf` #106), #768 (`"… Awaiting orchestrator review + publish"`, merged `ab6f3982`
     #266), #770 (`"IMPLEMENTED on branch …, NOT merged — awaiting owner"`, merged `67e20b76`
     #268 — the motivating case).
   - *FP class (a) — retained pre-merge history (2 of 3):* #702 and #737 both open with
     `"MERGED to main as PR #NNN … [pre-merge status retained: … NOT merged …]"`. The markers sit
     inside the `[pre-merge status retained: …]` bracket, i.e. the status is already correct.
   - *FP class (b) — unanchored substring (1 of 3):* #624's only hit is the literal `on branch`
     inside **"a parallel strategy-sessi*on branch*, merged into this tempdoc"** — a missing
     word boundary in a 10,489-char status log, not a merge-pending claim.
   **Gate-mode decision: stays OFF.** At 50% FP a blocking gate would train its own discounting
   (the explicit failure mode this item names). Both FP classes are mechanically closable —
   (a) ignore text inside `[pre-merge status retained: …]`, (b) anchor the marker on word
   boundaries — so re-measure after those two fixes before revisiting gate-mode. Report-mode
   is the correct tier today.
2. **Dev ORT-pack re-stage helper.** 772 §J's completeness check strands pre-772 staging
   layouts → silent CPU fallback for all ONNX eval inference (observed + fixed by hand
   2026-07-22). Ship a script (or extend prepare-worktree) that completes/re-stages
   `tmp/ort-variant-test/<variant>` from the gradle-cache ORT jar + writes the version
   marker; document in common-workflows §Worktree mechanics.
   **DONE 2026-07-22 (PR #298, commit `dbba0291`)** — `scripts/dev/restage-ort-pack.mjs` (224
   lines; completes a pack to the 772 §J contract, 4 DLLs + version marker, constants parsed from
   `OrtCudaHelper.java` so there is no second hardcode; never deletes, idempotent, exit 1 with an
   inline remedy when the gradle-cache jar can't be resolved). Documented at
   `docs/reference/contributing/common-workflows.md:177`. Re-verified on `main` 2026-07-28: both
   the script and the doc line are present.
3. **Corrections eval data file.** `test_correction_probe::TestLoadManifest` is red
   everywhere because `correction-eval-queries.v1.json` exists nowhere in git history
   (expected-state entry) — a user-facing feature with zero working eval. Author the
   dataset (small, seeded, leak-checked), turn the tests green, remove the expected-state
   exception (fix root cause, don't keep the suppression).
   **DONE 2026-07-22 (PR #300, commit `28d1dd31`)** — `scripts/jseval/jseval/data/correction-eval-queries.v1.json`
   (38 seeded entries: 12 controls + typo / transposition / missing-space classes matching what
   `correction_probe` measures). Root cause was an *unanchored* `data/` line in
   `scripts/jseval/.gitignore` over-matching the package data dir — anchored to `/data/`, so the
   file is tracked; the downloads dir stays ignored. The `correction-eval-queries-missing`
   suppression was REMOVED from `scripts/agent-analytics/expected-state.v1.json` rather than
   retained. Re-verified 2026-07-28: `pytest tests/test_correction_probe.py -q` → **15 passed**,
   and `expected-state.v1.json` carries no `correction` entry.
4. **Harness drift pair.** (a) Register catalog slugs vs jseval CLI dataset names diverge
   (`beir/scifact` in the catalog, `scifact` at the CLI — hit live 2026-07-22): make the
   CLI accept catalog slugs or fix the catalog, one authority either way.
   **DONE 2026-07-22 (PR #300, commit `28d1dd31`)** — resolved in the CLI's favour: the catalog
   stays the one authority for slugs and the CLI now *accepts* them (`scripts/jseval/jseval/corpora.py`
   alias resolution, wired through `commands/run.py` + `ingest.py`; regression coverage in
   `tests/test_corpora.py` +40 and `tests/test_ingest.py` +16). Re-verified 2026-07-28 as part of
   the full jseval suite below. (b) ~~The eval
   query path triggers the worker's `deprecated_mode_fallback` WARN on every request —
   jseval still sends the deprecated mode shape; migrate it to the pipeline-config shape
   so evals exercise what production runs.~~ **REFUTED as charted (2026-07-22, worker
   source-trace + orchestrator verification):** jseval's eval ranking path CANNOT trigger
   the WARN — `KnowledgeSearchEngine.java:683` unconditionally sets the wire
   `PipelineConfig` (":672 PipelineConfig is the sole pipeline control on wire"), so
   `SearchPlanner.plan` never reaches the `deprecated_mode_fallback` branch for
   `/api/knowledge/search` callers; and a client-side static pipeline for `mode=hybrid`
   would have DROPPED cross-encoder + freshness from the headline baseline
   (`SearchPipelinePresets.expandPreset` resolves `ce=rerankConfig.enabled()` at runtime;
   jseval deliberately treats `hybrid` as server-resolved — `retriever.py SERVER_MODES`,
   `preflight._CE_BEARING_SERVER_MODES`). The WARN observed live (2026-07-22, worker log,
   `search_mode=SEARCH_MODE_TEXT`) comes from a bare-request caller OUTSIDE the eval
   ranking path (candidate per F-037 note: `RemoteDocumentService`'s hand-built requests).
   **Re-scoped residue:** identify the actual bare-request caller and migrate IT —
   tracked as this item's remaining half; no jseval change, no 781-sequencing
   constraint (the eval harness already exercises the current wire shape).
   **DONE 2026-07-28 (this PR) — caller hunt closed; the F-037 candidate was WRONG.**
   The deprecated wire shape is precisely *"a `SearchRequest` that does not set `pipeline`"* —
   `SearchPlanner.java:52-59` branches on `request.hasPipeline()` and only the `else` leg emits
   the `deprecated_mode_fallback` WARN. Exhaustive sweep of all 11 non-test
   `SearchRequest.newBuilder()` sites (`grep -rn "SearchRequest.newBuilder()" --include=*.java modules`):
   - **Already compliant (9):** `KnowledgeSearchEngine.java:674` (pipeline at :683, as the
     refutation above states) · `RemoteDocumentService.java:315` (pipeline at :328, migrated by
     735 G5) · `GplJobCoordinator.java:437` · `RemoteKnowledgeClient.java:913` ·
     `SearchRpcOps.java:66`/`:96` · `SearchOrchestrator.java:165` ·
     `GrpcTestClient.java:196` · `SearchRpcOps.java:81` (defensive `null` guard on an
     already-built caller request, not a wire emitter).
   - **The actual offenders (2) — both facet-only probes, migrated in this PR:**
     `GplOrchestration.fetchMimeFacets` (`modules/app-services/.../bootstrap/phases/GplOrchestration.java:110`)
     and `WorkerStatusCache`'s facet-snapshot refresh
     (`modules/app-services/.../worker/WorkerStatusCache.java:195`). Both build a `*:*` LUCENE
     facet request with no `pipeline` **and no `mode`**, so `mode` takes the proto default —
     `SEARCH_MODE_TEXT = 0` (`modules/ipc-common/src/main/proto/indexing.proto:12`). That exactly
     reproduces the live WARN's `search_mode=SEARCH_MODE_TEXT` recorded above, which the
     `RemoteDocumentService` candidate could not (it has set `pipeline` since 735 G5).
   **Fix + why it is behaviour-preserving:** both now set `PipelineConfigs.TEXT`
   (`modules/ipc-common/src/main/java/io/justsearch/ipc/PipelineConfigs.java:15-21` —
   `sparse + lambdamart + expansion`), which is *leg-for-leg identical* to the fallback's
   `default ->` branch (`SearchPlanner.java:310-315`) that these requests were already resolving
   to. So the wire shape becomes current and the WARN stops firing without changing which legs
   run; the only added signal is a `pipeline_name` for the trace. No worker-side change — the
   fallback branch stays as the contract for genuinely legacy clients.
5. **subagent-guide parks-to-wait line.** Five same-arc incidents of workers parking to
   "wait for events" (a stopped agent receives no events). Add the standing line to the
   SubagentStart baseline brief: synchronous end-to-end execution, bounded in-turn
   condition-polls, never park awaiting external events. One sentence, hook-delivered to
   every subagent type (the sole context carrier for Explore/Plan types).
   **DONE 2026-07-22 (PR #298, commit `dbba0291`)** — the line ships in the injected baseline
   brief at `scripts/agent-analytics/hooks/subagent-guide.mjs:67` (verified present on
   `origin/main` 2026-07-28): *"Execute synchronously end-to-end within your turns: use bounded
   in-turn condition-polls for waits; NEVER stop your turn to 'wait for' an external event or
   monitor — a stopped agent receives no events and stalls until manually resumed."*
   The hook is advisory-role in `governance/agent-hooks.v1.json` (no hash pin), `hook-integrity`
   green. **Note 2026-07-28:** this item was briefed to the current session as *skip — founder WIP
   in flight on that file*; the content check (squash-merge rule: verify by content, not ancestry)
   showed it had already landed, so no edit to `subagent-guide.mjs` was needed or made in this PR.
   No collision with the in-flight main-checkout edit.
6. **Dependabot queue triage.** Seven open dependency PRs (some months old) on a public
   repo: merge, supersede, or close each with a reason — supply-chain hygiene; the open
   queue itself is signal to outside readers.
   **DONE 2026-07-28 (queue processed this session; recorded here, no code in this PR).**
   Disposition of the queue:
   - **Merged (3):** #218, #305, #306 — carried the notices regen + verification-metadata fixes.
   - **Flagged to the founder per policy (3):** #228, #60, #59 — green but **major** version
     bumps, which the standing policy routes to the founder rather than auto-merging.
   - **Blocked (1):** #304 — cannot merge on a `typescript-7` peer-dependency conflict; stays
     open until the peer range moves.
   The founder has delegated the flagged remainder, so every one of the seven has a recorded
   disposition and none was silently dropped (§B acceptance).

## §B. Acceptance

- Every item checked off with its PR link or an explicit founder-approved "won't do +
  reason" recorded here (no silent drops).
- Item 1 ships report-mode with a measured false-positive check on the current tempdoc set
  before any gate-mode decision.
- Item 3's dataset passes the same leak discipline as any corpus (closed-book style check
  scaled to its size).

**Acceptance settled 2026-07-28.** All three clauses met: every §A item carries a dated
disposition with its PR link (1/2/5 → #298, 3/4a → #300, 4b + 6 → this PR); item 1's measured
FP check is recorded in-item (50%, both classes named, gate-mode explicitly declined and the
re-measure trigger stated); item 3's dataset was authored under the stated leak discipline —
clean queries generic-domain and drawn from no committed corpus, corrections carrying only
spellings, never doc ids or answers (#300). No item was dropped and none needed a
"won't do" ruling.

## §C. Notes

- Nothing here is design work; anything that grows past "small" gets evicted into its own
  charter rather than bloating this bundle (the bundle's value is that it stays cheap to
  finish).
