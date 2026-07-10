---
title: "Scanned-PDF OCR throughput: replace Tika's opaque internal per-page-serial OCR_ONLY path with JustSearch's own parallel, capped, budgeted render+OCR loop — >10x on scanned documents — and fix the advisory-timebox/orphaned-child liveness class the investigation confirmed on the same path"
type: tempdocs
status: "open — design SETTLED (2026-07-10, design pass after takeover GO verdict): one owned OCR engine, Tika de-OCR'd everywhere (PDFs and images), corrections A/B resolved (maxPages gate preserved + in-loop defense; tryOcr calls the engine directly, no blank-branch reroute), config unified root-cause, orphans named in §Design. Preceded by: takeover investigation (same day, second session) re-verified all evidence claims at file:line against main. Implementation not started, awaiting go-ahead. Spun out of 705's founder-directed sidegoal per 686's own out-of-scope rule ('fixing whatever the measurement finds' is a new tempdoc). Owns OCR execution performance + the extraction timebox/orphan liveness fix; does NOT own routing (607), reason codes (671, shipped), or the extraction-tax verdict (705)."
created: 2026-07-10
author: agent (Fable, takeover-705 session) — founder-directed sidegoal after the 686 instrumented run showed the extraction cost tax concentrates in scanned PDFs
category: extraction / ocr / indexing-performance / worker
related:
  - 705-document-extraction-improvement-and-tax-reduction  # parent frame — owns the extraction-tax question; this doc is its cost-face first fix. Evidence legs recorded there (§Sidegoal).
  - 686-real-pdf-corpus-and-tika-pressure-measurement      # the measurement that exposed the cost concentration; supplies the corpus (mixed/realdocs-v1) this doc verifies against
  - 607-vdu-ocr-extraction-logic-analysis                  # routing authority — UNCHANGED by this doc (evaluateOcrAttempt decisions and tier semantics stay)
  - 671-tika-ocr-skip-routing-misclassification            # shipped reason-code truthfulness — the classifiers this doc must not regress
principle: "the OCR engine's cost model must be owned, not rented: page caps, budgets, parallelism, and honest timeouts can only exist in a loop we control — a black-box serial path is unfixable at any speed."
---

> Noncanonical working tempdoc. Design settled on measured evidence; implementation pending.

# 706 — Scanned-PDF OCR throughput (+ extraction timebox liveness)

## Problem (measured, 2026-07-10)

The 686 instrumented run over `mixed/realdocs-v1` (620 real files) showed the extraction cost tax
concentrates almost entirely in scanned/mixed PDFs: bimodal completion cadence — median inter-doc
gap ~0s, but stalls of 35-350s per scanned document, ~4-5 docs/min overall, near-zero CPU.

## Evidence base (full detail: 705 §Sidegoal, same date)

Three converging legs — code map (`file:line`), external research, local micro-benchmark:

1. **Dominant path is Tika-internal**: `tryOcr` → Tika `PDFParser` `OCR_STRATEGY=OCR_ONLY` renders
   EVERY page (300 DPI GRAY, `ocrDPI` default confirmed from jar bytecode) and spawns tesseract
   per page, serially — opaque: no page cap, no dimension guard, unparallelizable. JustSearch's
   own loop (`tryRenderedPdfOcr`, 160 DPI RGB hardcoded) is only a blank-result fallback.
2. **Tesseract itself is cheap**: ~1.1s/page OCR + ~0.1s/page render + ~0.1s spawn locally
   (tessdata_fast, 300 DPI gray, corpus scan sample). 65-page doc serial ≈ 80-200s — matches
   the stalls. Parallel 8-way measured **3.98×** on just 10 pages (20 logical cores).
   List-file batching measured 0.94× — worthless (init ~10% of a page); rejected.
   DPI reduction: modest gain, unverified quality anomaly; rejected without accuracy eval.
3. **Redundant invocations (code-confirmed)**: whole-file confidence re-OCR after successful Tika
   pass (`PolicyDrivenTikaExtractor.java:292`); text+TSV = 2 spawns/page in the owned loops.
4. **Timebox is advisory (liveness defect)**: single shared single-thread executor in
   `TimeboxedContentExtractor`; `cancel(true)` can't stop CPU-bound work; interrupted tesseract
   children not `destroyForcibly`'d (orphan leak, `OcrConfidenceExtractor.java:64-66,113-115`);
   next document queues behind the stuck one → logged per-doc times include inherited queue wait;
   true ceiling unbounded. (Sidecar audit's prediction, now runtime-corroborated.)
5. **Config duality**: `application.yaml` ships `per_file_timeout_ms: 5000, max_pages: 10`; code
   fallback `OcrRoutingConfig.defaults()` is `30s/50p`; the run OCR'd a 29+-page doc → code
   fallback was live. Needs an explicit decision.

## Design (settled 2026-07-10, design pass; supersedes the same-day sketch preserved in 705 §Sidegoal)

The design's one sentence: **Tika does structure; JustSearch does OCR — everywhere.** Tika never
spawns tesseract again; every tesseract invocation in the system goes through one owned engine
that owns rendering, parallelism, budgets, and child lifecycle.

### D1 — One owned OCR engine (new component, worker extract package)

A single component owns PDF page OCR end-to-end:

- **Render serially** (PDFBox is not thread-safe on a shared document; render is ~10% of page
  cost), 300 DPI GRAY, DPI a named config (replacing both Tika's implicit 300 and the owned
  loops' hardcoded 160), pages written to a **per-document temp directory** (one create, one
  recursive delete — also sweeps orphaned artifacts on kill), image freed promptly, bounded
  in-flight queue for heap backpressure (686's heap verdict: no margin).
- **OCR in a bounded parallel pool**: default ~min(cores/2, 8) workers, config-visible,
  `OMP_THREAD_LIMIT=1` per child (defensive). **One spawn per page emitting `txt tsv` together**
  — text and confidence from the same invocation; no second pass exists anywhere anymore.
- **Bounded honestly**: per-page timeout (existing per-invocation semantics) + an **aggregate
  per-document elapsed budget** sitting *below* the 60s outer timebox, so the budget fires first
  and returns partial page-ordered text with an honest truncation record — converting today's
  "timeout → whole document discarded + queue pileup" into "budget → partial text kept".
  The `maxPages` eligibility gate in `evaluateOcrAttempt` is **unchanged** (routing stays 607's);
  the engine additionally enforces the cap in-loop as defense-in-depth for documents whose page
  count was unknown (0) at gate time (correction A resolved).
- **Child lifecycle owned**: every spawned process is registered; interrupt/cancel/close
  forcefully terminates the registered set (the `LlamaServerOps` kill discipline, applied to
  tesseract). The outer timebox needs no process knowledge — its `cancel(true)` interrupt lands
  in the engine's waits, and the engine kills its own children (fixes the
  `OcrConfidenceExtractor` interrupt-path orphan leak as a by-product of the restructure).
- **Output**: page results joined in page order with the existing `--- OCR page N ---` markers,
  aggregate confidence summary from the per-page TSVs, truncation + evidence facts in the shapes
  the evidence builder already consumes.

### D2 — All OCR call-sites route through the engine; Tika's OCR is retired

- **Full-scan PDFs**: `tryOcr` invokes the engine *directly* for PDFs — not via a Tika pass
  whose blank output happens to fall through (correction B resolved: the blank-content branch is
  no longer a reroute mechanism; under the old structure a `NO_OCR`-configured Tika pass would
  have silently ended OCR for partially-texty PDFs).
- **Mixed PDFs**: `trySelectivePdfOcr` uses the same engine on the missing-readable-text page
  subset (`ocr_selective` semantics unchanged), replacing its per-page Tika-on-PNG spawn +
  direct-text fallback + separate confidence spawn (up to 3 spawns/page → 1).
- **Raster images**: the direct single-spawn path becomes primary (it already exists as
  `tryDirectImageOcr`'s substrate); Tika's parser config is de-OCR'd at `TikaConfig` level so
  the structured first pass can never invoke tesseract — which, once verified, retires the
  raster-image blank-baseline workaround in `extractArtifact` (that hack exists *because* Tika's
  default config could OCR images uninvited).
- **Confidence** always comes from the same spawn's TSV; the whole-file confidence re-OCR after
  a successful OCR pass is deleted.

### D3 — What is explicitly preserved (the seams this design must not move)

- `evaluateOcrAttempt` gate order and skip semantics — routing authority stays 607's.
- The single-terminal-classifier structure and `OcrOutcomeClassifier` (671's registered
  `ocr-outcome-classifier` logic seam; its PIT-strength guarantee must hold through the
  restructure), the `OcrSkipReason` vocabulary, `ocr_full`/`ocr_selective` extraction methods,
  and `ENGINE = "tesseract"`.
- `PARSER_ID = "tika-policy-ocr"` stays — it is wire-visible provenance naming the *route*
  (the tika-policy extractor's OCR branch), not the execution engine; renaming would be consumer-
  visible churn for zero information. Revisit only with a consumer sweep if the route itself is
  ever renamed.
- `TimeboxedContentExtractor`'s architecture (60s outer box, single-thread isolation) and the
  process-sandbox seam (tempdoc 410) — untouched; the executor redesign remains deferred.

### D4 — Config unification (root cause, not plumbing patches)

Two defects, both fixed: (a) the worker falls back to `OcrRoutingConfig.defaults()` whenever its
`ConfigStore` is unpopulated — fix the population path so shipped yaml is authoritative in every
process that extracts; (b) yaml (5s/10p) and code defaults (30s/50p) disagree — unify on **one
value set** (recommendation: 30s aggregate budget / 50 pages, which the parallel engine makes
affordable; honoring yaml's current 10p cap would have *skipped* most of 686's stall documents
entirely — worse extraction masquerading as speed) and make code defaults equal shipped yaml so
the fallback can never diverge again. Render DPI and worker count join the same config surface.

### Orphans (deleted by this tempdoc's work — not a later sweep)

1. `StructuredContentExtractor.extractWithOcr` + `parseContextWithOcr` + `configurePdfOcrOnly` +
   `configureTesseractOcr` — the entire Tika-OCR execution surface loses its last caller.
2. `tryRenderedPdfOcr`'s serial 160-DPI loop — subsumed by the engine (the blank-fallback role
   disappears because the engine is primary, not fallback).
3. The per-page Tika-on-PNG spawn inside `trySelectivePdfOcr`.
4. The whole-file confidence re-OCR after OCR success (`PolicyDrivenTikaExtractor.java:292`).
5. The two-spawn text+TSV call pattern — `OcrConfidenceExtractor`'s two entry points collapse
   into the engine's single spawn primitive (the TSV parsing survives as its consumer).
6. The hardcoded 160-DPI constants.
7. Conditionally: the raster-image blank-baseline workaround in `extractArtifact` — delete once
   the Tika-level de-OCR is verified to make it unreachable; keep with an updated comment if any
   reachable path remains.

### Implementation prerequisites

Load `/search-quality` before coding (extraction feeds retrieval quality; update the register
before closing this tempdoc), and expect the `seam-hint`/logic-seams gate when touching the
classifier-adjacent code (671's seam).

## De-risk pass (2026-07-10, pre-implementation; all eight planned uncertainties closed)

Confidence-building only — no feature work. Experiments ran in the session scratchpad against the
app-owned tesseract 5.5.0 runtime (`native-bin/tesseract`) and the real corpus scan
(`govdocs1-000--000164.pdf`, 13 pages, from the 686 dataset).

### Experimental results (all pass)

- **Single-spawn dual output works** (was claimed, never demonstrated): one
  `tesseract <img> <base> --psm 6 -l eng txt tsv` invocation produced both `.txt` and `.tsv` in
  **1052 ms** vs **1978 ms** for the two separate spawns the code does today (~1.9× per page on
  confidence-carrying paths), with byte-identical text output. Stderr shows only harmless
  small-region warnings; no failure modes.
- **The owned render is sound**: PDFBox 3.0.6 `renderImageWithDPI(i, 300, GRAY)` on the corpus
  scan → 2550×3299 px at **30–142 ms/page** (matches the 705 bench), ImageIO-written PNG produced
  real OCR text with **no** tesseract resolution-metadata warnings — the feared "ImageIO PNG lacks
  DPI metadata" degradation did not materialize. Parity with the Tika path is structurally
  near-guaranteed (Tika renders via the same PDFBox with the same 300/GRAY defaults into the same
  tesseract with the same `--psm 6`); the formal word/char-parity check stays in the verification
  plan as the closing gate.

### Root cause of the config anomaly — CORRECTION to Evidence item 5 and design D4

The evidence base inferred "the code fallback (`defaults()` 30s/50p) was live" from the 686 run
OCR-ing a 29+-page doc. **That inference is wrong.** The full chain was traced and the mechanism
is different and worse:

1. The worker *always* populates its `ConfigStore` (`IndexerWorker.java:76,92`) — the `defaults()`
   fallback in `resolvedOcrConfig()` is effectively dead in real runs.
2. The yaml→`Ocr` mapping is correct and unit-proven (`ResolvedConfigBuilderTest.ocrConfig`,
   asserts `maxPages`), and the Head→Worker snapshot carries **all** resolved keys
   (`ResolvedConfig.toWorkerSnapshot`).
3. But the headless/eval backend (which 686 used) sets `JUSTSEARCH_CONFIG` to
   `modules/ui/src/main/resources/headless-config/application.yaml` — **which has no `ocr`
   section at all**. All `index.ocr.*` keys resolve absent → `ResolvedConfig.Ocr` fields are
   null → `OcrRoutingConfig.from()` **passes nulls through**: `enabled=true` (null ≠ FALSE),
   `maxPages=null` → the page-cap gate never fires, `maxImageDimension/maxImagePixels=null` → no
   guards, `perFileTimeoutMs=null` → 30s per tesseract invocation. The 686 run ran **unbounded**
   OCR, not 50-page-capped OCR.
4. Consequence: there are (at least) three different effective OCR configs by environment —
   repo-root desktop dev runs get yaml's aggressive 5s/10p; headless/eval runs get *no limits*;
   packaged installs (no repo root, `CONFIG_PATH` unset) most likely also get no limits.

**D4 is accordingly revised** (supersedes its "fix the ConfigStore population path" wording —
population is fine): (a) `OcrRoutingConfig.from()` must fill absent fields with safe defaults —
"config absent" must mean *safe limits*, not *no limits*; (b) the headless-config yaml gets an
explicit `ocr` block; (c) one unified value set across both yamls and `defaults()` (30s aggregate
budget / 50 pages, per §Design); (d) log the effective OCR config at extractor construction —
today it is logged nowhere, which is why the 686 anomaly needed a code-archaeology session to
diagnose. Correction A's in-loop page cap is now known to be load-bearing (not just
defense-in-depth), since real environments have been running capless.

### Mechanism verifications (code/jar evidence)

- **De-OCR lever confirmed**: `TesseractOCRConfig.setSkipOcr(boolean)` exists in the Tika 3.2.3
  ocr-module jar (javap-verified) — disabling Tika-initiated OCR is a one-line addition to the
  existing reflective config helper, no custom TikaConfig XML. Also re-confirmed:
  `PDFParserConfig` has **no** OCR page-cap setter (the "Tika can't be capped" premise holds).
- **Interrupt propagation is clean** (U7): `InProcessExtractionSandbox` is a plain pass-through;
  the timebox's `cancel(true)` interrupt lands on the extraction thread, which in the new engine
  blocks at the parallel join — an interruptible point. Per-document pool + child-process
  registry + `destroyForcibly` on interrupt needs **no** `TimeboxedContentExtractor` change.
  One implementation note: add `Thread.interrupted()` checks between rendered pages (PDFBox
  rendering is CPU-bound and interrupt-blind).
- **Quality-gate stability** (U6): `computeQualityScore` is length + alphanumeric-ratio banded
  with hard zeros only on `(cid:`/U+FFFD (which tesseract output cannot contain); page markers
  are alphanumeric-friendly. No perverse acceptance-gate flips expected from the output-shape
  change.
- **Blast radius** (U5): every production caller of the OCR execution surface lives inside
  `worker-services`' extract package; `VisualExtractionEvidence` consumes only the `Summary`
  type; `IndexStatusOps` touches `OcrRoutingConfig` only. Tests that must stay green:
  `OcrOutcomeClassifierTest` (registered logic seam), `VduEligibilityPdfFixturesTest`; tests that
  will need updating: `PolicyDrivenTikaExtractorTest`, `OcrConfidenceExtractorTest`. Bonus
  orphan found: `OcrConfidenceExtractor.extractPlainText` is already flagged suspected-dead in
  `UnreferencedCodeTest` (638 F6) — delete it in this work and update that registry entry.
- **Verification prerequisites** (U8): tesseract 5.5.0 runs app-owned at
  `<main-checkout>/native-bin/tesseract/` (plus a scoop shim on PATH); the scanned corpus lives in
  the 686 dataset (`datasets/mixed/realdocs-v1/`, currently materialized in the takeover-705
  worktree; regenerable via jseval if that worktree is removed).

### Confidence: 8/10

Every mechanism the design depends on is now demonstrated or code-verified; the config root cause
is named with unit-test-level proof of the mapping; the blast radius is one package plus its
tests. The two points withheld: (1) the `tryOcr`/`trySelectivePdfOcr` restructure must preserve
the 671 evidence-builder first-write-wins and single-terminal-classifier semantics — precise,
well-tested, and fiddly; (2) formal quality parity and the live 686-style re-run remain
post-implementation gates (structurally argued, not yet measured).

## Execution log (implementation, 2026-07-10)

Orchestrated per the approved plan: design/briefs/review/evidence by the orchestrating session,
mechanical implementation by subagents (S1 opus, S2 sonnet), every subagent result independently
re-verified (build + module tests re-run by the orchestrator; diff reviewed line-level).

### S1 — engine + call-site restructure + teardown (commit 8e882d1)

Shipped as designed: `PdfOcrEngine` (serial 300-DPI GRAY render → bounded pool `min(cores/2, 8)` →
one spawn/page emitting `txt tsv` → page-ordered join; in-loop page cap; aggregate budget below the
60s timebox returning honest partials; per-document temp dir; child registry with `destroyForcibly`
on interrupt/timeout). All seven orphans deleted; `evaluateOcrAttempt`, reason codes, evidence
shapes untouched; `OcrOutcomeClassifierTest` + `VduEligibilityPdfFixturesTest` green unmodified.
Two review findings during orchestration:
- **Design part C was already shipped on main** (commit `04a4700` — `configureTesseractSkipOcr` in
  the structured parse context); the takeover investigation had missed it. The raster-blanking
  workaround was therefore already belt-and-suspenders; deleted after the new de-OCR completeness
  test proved it unreachable.
- **Reviewer-found defect, fixed same pass**: the engine initially snapshotted
  `TikaOcrRuntime.resolve()` at construction — a lifecycle regression vs the old per-attempt
  resolution (`standalone-capability-stays-stuck` shape: a mid-session Install-AI tesseract restore
  would pass the per-attempt eligibility gate while the engine held stale paths). Fixed to per-
  engine-call resolution with a mutable-supplier regression test.
- **One deliberate semantics change**: the full-scan PDF path now *merges* baseline + OCR text
  (the old primary path *replaced* content with OCR-only text; the old fallback loop already
  merged). On text-layer+scan hybrids this retains text the old path threw away, at the cost of
  some duplicated content (measured on doc 208 below). Flagged for the independent review.

### Measured before/after (same 3 corpus docs, same machine — RTX-4070 box, 20 logical cores,
pool=8 — same custom harness config `600s budget / 100p cap` both sides; before = commit `aec9b99`
in a detached worktree, after = `8e882d1`)

| Doc (686 corpus) | Shape | Before | After | Delta |
|---|---|---|---|---|
| `govdocs1-000--000208.pdf` | 77p, 76 image pages, `ocr_full` | **113,855 ms**, 161,819 chars | **16,774 ms**, 318,791 chars | **6.8×** faster; content = baseline + OCR (merge semantics) |
| `govdocs1-000--000164.pdf` | 14p full scan | 29,517 ms, **OCR FAILED** (63 chars kept, `ocrSkipReason: unknown`) | **3,661 ms**, 36,478 chars, `ocr` route | **8.1×** faster AND recovers a document the old path lost entirely |
| `govdocs1-000--000187.pdf` | text PDF control | 1,900 ms, structured | 2,140 ms, +394 chars accepted selective OCR | +240 ms; old path's selective OCR silently failed on its image pages, new succeeds |

**Quality parity (doc 208): 100.0% of the before-run's unique word vocabulary (4,504/4,505 words
>3 chars) is present in the after output**, which adds 868 further unique words (retained text
layer + recovered pages). The after output is a strict vocabulary superset on the headline doc.

Notes for honest reading: 6.8× (not the >10× projection) because the measured doc spends ~2.5s in
baseline parse + serial render alongside the parallelized OCR, and the projection assumed pure
serial-OCR docs; doc 164 shows the second win class (old-path hard failures recovered). Speedup
scales with worker count (~2 workers ≈ ~2× on a 4-core machine).

## Reach (design-pass judgment)

**Conforms to an existing shape rather than inventing one.** This is the third instance of
JustSearch converting a rented external-engine lifecycle into an owned one — llama-server
(`InferenceLifecycleManager`/`LlamaServerOps`: hung-process kill, zombie recovery, adoption) and
ORT sessions (`ort-common`: session ownership, VRAM budgeting) are the precedents. The design
deliberately mirrors their kill discipline instead of creating a parallel convention.

**The principle, named**: *no black-box call may own an unbounded amount of per-document work* —
external engines are invoked for one bounded unit (one page, one inference, one session op);
JustSearch owns the loop, the budget, the parallelism, and forceful termination.
- **Where else it applies / residual violator**: the Tika *parse itself* (a pathological
  non-OCR document inside `extractStructured`) is still an unbounded black-box call, mitigated
  only by the advisory timebox. That violation is real but owned: it is exactly what the
  process-sandbox seam (410) exists to fix, and this doc defers to it rather than building a
  second mechanism.
- **A second, smaller seam this design creates**: the per-page OCR call inside the engine is the
  natural future swap point for a different OCR engine (the ONNX/specialist-model candidates
  priced in 705 F5/F7). Kept as a method boundary only — no interface, no plugin machinery —
  per AHA; the point is that owning the loop is what makes the engine *swappable at all*.
- **Evidence the principle earns its keep**: per-document extraction ceilings actually bounded
  in the next 686-style run (no 350s tails, no inherited queue wait); zero orphaned tesseract
  processes across kill/interrupt tests; a future engine swap that touches only engine internals.
- **Retirement condition**: if extraction moves wholly behind the 410 process sandbox (ownership
  transfers to the sandbox boundary), or OCR moves to an in-process ORT pipeline (no child
  processes to own), the in-process spawn-ownership discipline retires with the code that needed
  it. If the 686 re-run shows the tails were NOT eliminated, the principle mis-locates the
  problem and must be re-examined rather than defended.

## Expected effect

65-page scan: ~15-20s vs current 80-350s (>10×); fast documents unaffected; quality neutral-to-
better (same engine; 300 GRAY ≥ each replaced path's settings).

## Verification plan

- Before/after wall-time on the same scanned corpus docs (`govdocs1-000--000164.pdf` 13p full-scan;
  the 25-65p mixed-PDF stall candidates), plus a full `mixed/realdocs-v1` ingest re-run when wanted.
- Quality: word/char parity (±few %) on the scan sample vs the pre-change path; extraction_method /
  reason-code assertions unchanged (`VduEligibilityPdfFixturesTest` + 671's classifier tests green).
- Liveness: a regression test for the orphan fix (interrupt → registered children killed) and for
  the budget (expiry → partial page-ordered text + truncation recorded, not an empty result), per
  `audit-driven-fixes-need-test`.
- De-OCR completeness: an assertion that no Tika parse path can spawn tesseract (e.g., structured
  pass over a scan-image fixture produces zero OCR spawns) — guards the raster-blanking-hack
  deletion and the "Tika does structure, JustSearch does OCR" invariant.
- Quality parity must cover raster images too (their OCR route changes from Tika-internal to the
  direct single-spawn path).
- Timing evidence recorded here with the same discipline as 686 §Execution log.

## Boundary

Not owned: OCR routing/eligibility (607), reason-code vocabulary (671), VDU anything (672/677),
the extraction-tax verdict (705), heap sizing (686 follow-up). The single-thread extraction
executor's broader redesign is explicitly deferred — this doc ships the narrow liveness fixes
(forceful child kill, budget) only.

---

## Takeover investigation (2026-07-10, second session, worktree `takeover-706`)

Independent verification pass per `/takeover` (verifier ≠ author: this doc was written by the
takeover-705 session the same day; every load-bearing claim was re-checked against source rather
than trusted). Branch base `b7c9258`; `git diff main...` on `modules/worker-services/src` is
empty, so all citations below are current against `main` (f4889d1).

### Evidence re-verification — all claims CONFIRMED at source

| # | Tempdoc claim | Verified at |
|---|---|---|
| 1 | Full-scan OCR path is Tika-internal `OCR_ONLY` | `StructuredContentExtractor.java:261` (`configurePdfOcrOnly` → `OCR_STRATEGY=OCR_ONLY`), wired from `extractWithOcr:103-121`; non-OCR pass sets `NO_OCR` at `:242` |
| 2 | JustSearch never sets `setOcrDPI` → Tika defaults (300 GRAY) live | repo-wide grep: zero hits for `setOcrDPI`/`ocrDPI`/`setOcrImageType` |
| 3 | Owned fallback loop is 160 DPI RGB, hardcoded, serial | `PolicyDrivenTikaExtractor.java:508` (`tryRenderedPdfOcr`), `:400` (`trySelectivePdfOcr`) |
| 4 | 2 spawns/page in owned loops (text + TSV) | text `:516` + confidence `:524`; **undercounted for the mixed-PDF path**: `trySelectivePdfOcr` is up to **3** spawns/page (Tika-on-page-PNG `:409` + direct-text fallback `:417` + confidence `:428`) — the double-pay claim holds a fortiori |
| 5 | Whole-file confidence re-OCR after successful Tika pass | `PolicyDrivenTikaExtractor.java:292-293` (`OcrConfidenceExtractor.extract(file, …)` on the raw PDF — tesseract can't read PDFs, so fast-fail waste for PDFs, genuine full re-OCR for raster images) |
| 6 | Timebox advisory; next doc queues behind stuck one | `TimeboxedContentExtractor.java:122` (single shared single-thread executor), `:154` (`cancel(true)` best-effort) |
| 7 | Orphaned tesseract children on interrupt | `OcrConfidenceExtractor.java:64-66,113-115` — `InterruptedException` → return **without** `destroyForcibly()`. Nuance: the *timeout* path DOES kill (`:50,:106`); only the interrupt path leaks |
| 8 | Config duality 5s/10p (yaml) vs 30s/50p (code) | `config/application.yaml:120-121` vs `OcrRoutingConfig.java:39`; fallback mechanism found: `DefaultWorkerAppServices.resolvedOcrConfig():483-489` returns `defaults()` whenever the worker's `ConfigStore.globalOrNull()` is unpopulated — that is how a 29+-page doc got OCR'd in the 686 run |
| 9 | Tika can't be configured out of the problem | Tika 3.2.3 `PDFParserConfig` offers `ocrStrategy` (incl. per-page `AUTO`), `ocrDPI`, `ocrImageType` — but **no OCR page cap, no parallelism, no aggregate budget**; OCR inside a Tika parse is strictly serial (SAX). Owning the loop is the only route to the 3.98×-measured parallel win and to honest budgets. (Web-checked 2026-07-10: Tika wiki TikaOCR + PDFParser pages.) |

Also verified: `VduEligibilityPdfFixturesTest` exists (`modules/worker-services/src/test/.../loop/`);
the process-sandbox seam (tempdoc 410, `JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process`,
`DefaultWorkerAppServices.java:452-481`) exists but defaults to in-process and requires an external
command — so this doc's "narrow liveness fix now, executor redesign deferred" boundary correctly
defers to existing infrastructure rather than duplicating it.

### Design corrections required (found by this pass; resolve at design/implementation time)

- **A. `maxPages` is currently an eligibility GATE, not a cap** (`evaluateOcrAttempt`,
  `PolicyDrivenTikaExtractor.java:174`: `pageCount > maxPages` → skip OCR entirely,
  `OcrSkipReason.SIZE`). The design's "maxPages enforced INSIDE the loop (truncate + record)" is
  ambiguous: removing the gate silently converts skip-entirely into partial-OCR — a routing-
  semantics change this doc's own boundary assigns to 607; keeping the gate makes in-loop
  truncation reachable only when pageCount was unknown (0) at gate time. **Recommended: keep the
  gate unchanged (routing untouched, as promised), add the in-loop cap as defense-in-depth for
  unknown page counts.** If truncate-instead-of-skip is wanted as a product improvement, that is a
  conscious 607-adjacent decision to record, not a side effect.
- **B. Setting `NO_OCR` on the OCR pass must not rely on the blank-content branch to reroute.**
  Today the owned loop fires only when the Tika OCR pass returns blank
  (`tryOcr:273-279`). Under `NO_OCR`, `extractWithOcr` returns *embedded text* — blank for pure
  scans (reroute works by accident) but **non-blank for partially-texty PDFs**, whose OCR would
  silently stop happening. `tryOcr` must be restructured to invoke the owned loop directly for
  PDFs, not via the blank check. (Classic wrong-gate shape; flagged per `critical-analysis-pass`.)

### Implementation cautions (record now, apply then)

- **PDFBox `PDFRenderer` is not thread-safe** on a shared `PDDocument`: render serially
  (~0.1s/page measured), fan out only the tesseract spawns.
- **Heap**: a 300 DPI GRAY Letter page ≈ 8-9 MB `BufferedImage`; 686's heap verdict says 1g has
  no margin. Bound in-flight rendered pages (backpressure), write PNG → free image promptly.
- **CPU contention**: the pool competes with enrichment (691's throughput domain);
  `min(cores/2, 8)` is sane, make it config-visible.
- **Budget ↔ timebox interplay is a quality WIN to state**: today a 60s timebox expiry discards
  the whole document (empty result + queue pileup); an in-loop budget returns partial text
  honestly (truncated). Budget must sit below the 60s timebox to fire first.
- **671 preservation**: `OcrOutcomeClassifier` no-improvement classification and the
  first-write-wins `OcrEvidenceBuilder` discipline (see the `:578-583` comment) must survive the
  restructure; 671's classifier tests + `VduEligibilityPdfFixturesTest` are the guard.
- **Config decision (design item 4) interacts with the win**: honoring yaml's 10p cap would have
  *skipped* most of the 686 stall docs (worse extraction, not faster OCR). With the parallel loop,
  50p at 300 DPI is affordable (~15-20s) — recommend reconciling toward the code defaults
  (30s/50p), fixing the `ConfigStore` fallback path, and making yaml the single authority.

## Theorization (2026-07-10, pre-design breadth pass)

Recorded before final design so the settled mechanism is chosen against, not instead of, the
alternatives. Nothing here reopens the GO verdict; it widens the option space and names the
assumptions the design rests on.

### Alternative framings of the same problem

1. **Latency vs throughput vs freshness.** The design optimizes per-document wall-time. But the
   user-visible harm has a second component: extraction is a single-serial lane, so one 300s scan
   blocks *every* document behind it — the stall taxes corpus freshness, not just the one file.
   Two consequences: (a) the liveness fixes (budget + forceful kill) are valuable *independently*
   of any speedup, because they bound the worst case the whole pipeline inherits; (b) per-page
   parallelism attacks the worst-document latency, which is the right first target — but
   document-level parallelism (N extractions concurrently) is the complementary lever left with
   the deferred executor redesign. They compose; this doc deliberately takes only the first.
2. **"Faster OCR" vs "OCR off the hot path".** A different shape entirely: index the document
   immediately with whatever cheap extraction yields, and queue OCR as a background enrichment
   that upgrades the document later — the pattern the VDU path already ships (idle auto-trigger +
   batch mode, tempdoc 672). Ingest latency for scans becomes ~0 regardless of OCR speed;
   the cost is searchable-but-empty documents until OCR lands, plus an update/re-index path.
   Not this doc's scope (it changes indexing-job lifecycle surfaces), but it is the natural
   *next* frame if scanned corpora grow, and the owned loop built here would serve that lane
   unchanged. Worth remembering that this framing exists before anyone proposes heroics inside
   the synchronous path.
3. **Pay-per-index vs pay-once.** No extraction cache exists anywhere in the tree (verified by
   grep). Blue/green schema migrations and `--clean`/`--reset` rebuilds re-pay full OCR for every
   scanned document on every rebuild — a standing multiplier on whatever per-document cost
   remains. A content-hash-keyed extraction cache (key must include engine + DPI + config
   version) would make rebuild OCR ~free. Out of this doc's scope and possibly its own decision
   (interacts with 691's throughput domain and store recoverability), but it may ultimately be
   worth more than the speedup on corpora that get rebuilt often. Recorded so it isn't
   rediscovered from scratch.

### Alternative mechanisms considered (and why the owned loop still wins now)

- **Configure Tika harder** (AUTO strategy, lower `ocrDPI`): no page cap, no budget, no
  parallelism exists behind those knobs (verified §above); rejected for the same reasons as
  before. AUTO's per-page selectivity is already covered by JustSearch's own
  mixed-PDF selective path.
- **In-process OCR via JNI (tess4j-style)**: spawn cost is measured at ~0.1s/page (~10%) — the
  gain is small, and a native tesseract crash would take down the Worker JVM. Process-per-page is
  not overhead to eliminate; it is the crash-isolation architecture working as designed. Rejected.
- **A different OCR engine (ONNX-based PP-OCR-class det+rec models, or the PaddleOCR-VL /
  DeepDoc candidates from 705 F5/F7)**: potentially faster *and* better than tesseract, GPU-
  capable, and JustSearch already owns ORT session infrastructure (`modules/ort-common`) and a
  model-distribution pipeline. Not now — it is a model-selection + quality-eval + distribution
  project (374/705 territory, gated on the 686/677 evidence preconditions). The key structural
  point: **the owned loop is what makes this future swap possible at all** — once the loop is
  JustSearch's, the engine behind it becomes a pluggable seam; inside Tika it is unreachable.
  Design should keep the per-page OCR call behind a minimal internal seam for that reason (a
  method boundary is enough; no speculative abstraction — AHA applies).
- **Page-level result streaming** (emit pages into the index as they finish): interacts with
  document atomicity and the indexing job model; complexity >> value while whole-doc OCR is
  15-20s. Rejected without prejudice.

### Hidden assumptions worth naming (each is cheap to check during implementation)

1. **Absolute tesseract quality is adequate** — the verification plan checks *parity* with the
   old path, not adequacy; nobody has evaluated tesseract-at-300-GRAY accuracy on this corpus.
   Adequacy is 705/686's question; this doc must only not regress it. (`--psm 6` for uniform
   text blocks is likewise inherited from both existing paths, unevaluated — a possible future
   quality lever, not touched here.)
2. **Cores are idle during OCR** — measured true (686: near-zero CPU during stalls), and the
   pool must stay bounded so it remains roughly true alongside enrichment.
3. **Low-end hardware still wins** — a 4-core machine gets ~2 workers ≈ ~2×, not 10×; the >10×
   headline is a 16-20-thread-machine number. Honest reporting should state speedup as a
   function of workers.
4. **Windows environment effects are second-order** — per-page temp PNG + txt/tsv churn invites
   antivirus real-time scanning overhead per file; a per-document temp directory (one create,
   one recursive delete) is cheaper and cleans up orphaned artifacts on kill. Native memory of
   ~8 concurrent tesseract children (each can reach a few hundred MB on dense pages) sits
   *outside* the JVM heap — worker-count choice should respect machine RAM, not just cores.
5. **Out-of-order completion is handled** — parallel page results must be joined in page order,
   and the `maxExtractedChars` cap means late-cancelling in-flight pages once the cap is hit
   (waves or a completion-ordered collector; minor waste acceptable).

### The recurring system shape (principle, not yet design)

This is the third time JustSearch converts a rented lifecycle into an owned one: llama-server
(`InferenceLifecycleManager`/`LlamaServerOps` — hung-process taskkill, zombie recovery, adoption)
and ORT sessions (`ort-common` — session ownership, VRAM budgeting) preceded it. The shape:
**an expensive external engine is invoked only for one bounded unit of work per call (one page,
one inference), while JustSearch owns the loop, the budget, the parallelism, and the child
lifecycle — including forceful termination.** Tika-internal OCR violates the shape (unbounded
work per call, rented loop); this doc restores it for OCR. If a future gate or doc wants the
invariant named: *no black-box call may own an unbounded amount of per-document work.* The
liveness fix should consciously mirror the existing `LlamaServerOps` kill discipline rather than
inventing a new one.

### Sequencing option (de-risking, if wanted)

The design can ship as one change, but it decomposes into three independently-verifiable slices
with strictly increasing risk: **(1)** liveness only — `destroyForcibly` on interrupt + process-
tree kill + aggregate budget (no behavior change on healthy docs, kills the worst UX harm);
**(2)** waste removal — single spawn emitting `txt tsv`, drop the whole-file confidence re-OCR
(pure cost removal, output identical); **(3)** engine swap — bypass Tika OCR, owned parallel
loop at 300 GRAY (the >10×, and the only slice carrying quality-parity risk). If anything forces
a partial ship, that is the order; slice 3 is where corrections A and B (§above) live.

### Verdict

**GO — do it, and now.** This is the opposite case from 705's WAIT-FOR-EVIDENCE: the evidence
this fix needs already exists and is fresh — 686's instrumented run located the cost tax in
scanned PDFs (35-350s stalls, near-zero CPU), the micro-bench measured the parallel win (3.98× at
8-way on 10 pages) and rejected the non-wins (list-file batching 0.94×, DPI reduction unverified),
and every mechanism claim is now independently code-confirmed. The liveness defect (orphan
children + unbounded queue-behind-stuck-doc) is a proven structural defect — per
`structural-defects-no-repeat` it needs no further incidents. **Cheapest validating evidence:
already exists** (the "before" half of the before/after is 686's run; the parallel-speedup leg is
the micro-bench); nothing cheaper than implementing + running the doc's own verification plan
remains. **Displaces**: Tika-internal OCR *execution* for PDFs (config-level bypass, no fork) and
consolidates three overlapping OCR paths (Tika-internal, `trySelectivePdfOcr`,
`tryRenderedPdfOcr`) into one owned engine — a net deduplication. **Duplicates nothing**: routing
(607), reason codes (671), VDU (672/677), corpus/measurement (686), throughput attribution (691),
and the process-sandbox seam (410) are all untouched or deferred-to. Main residual risk is OCR
quality parity, and the verification plan already gates on it. Conditions on GO: resolve
corrections A and B in the design before coding; keep the verification plan's liveness regression
test (`audit-driven-fixes-need-test`).
