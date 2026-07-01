---
title: "Mixed-corpus reproducibility: MIRACL-de/fr-2k and CourtListener-200 have no committed build path anywhere in this project's history — research pass on what a real fix would look like"
type: tempdocs
status: "IMPLEMENTED + POST-IMPLEMENTATION FIXES APPLIED + FUTURE-WORK RESEARCH RECORDED (fifth pass, 2026-07-01). Third pass shipped: mixed/miracl-de-2k and mixed/miracl-fr-2k regenerated from real ir_datasets data (3103/305 and 5407/343 docs/queries); mixed/courtlistener-200 retired and replaced by mixed/legal-clerc-200 (198 docs, 200 queries, built from CLERC/jhu-clsp on HuggingFace). Fourth pass (critical-analysis review) found and fixed 4 real bugs: a spurious-warning bug (a `suite` metadata field wrongly tagged these real corpora as tempdoc-635 self-demo members), a silently-dropped provenance record (a field-name mismatch with `corpus_build.build_golden()`), an incomplete/non-runnable regeneration command documented in the register, and an unverified reproducibility claim — now verified live via a byte-identical diff across two fully independent CLERC fetches. Fifth pass (research only, no code changed): identified the single most important open risk — neither fetcher pins an upstream revision, so the reproducibility guarantee is technically conditional on CLERC's/MIRACL's sources never silently changing (HuggingFace confirms a pinnable commit SHA exists) — plus two concrete extension candidates already sharing the same fixed problem class (mixed/miracl-zh-2k, likely mixed/cord19-qddf), and a set of real, directly-observed contributor-experience gaps (silent multi-minute fetches, a bare FileNotFoundError on a fresh checkout, hand-retyping a recipe that's already committed). Real, comparable, live-measured nDCG@10 unchanged throughout: miracl-de-2k 0.852, miracl-fr-2k 0.866, legal-clerc-200 0.521. Full jseval suite green (1120 passed; 2 pre-existing unrelated failures). See §Implementation (third pass), §Post-implementation fixes (fourth pass), and §Future work (fifth pass) for full detail."
created: 2026-07-01
updated: 2026-07-01
author: agent research pass, prompted by an external agent's grant-plan investigation into these same two corpora's reproducibility
related:
  - 664-eval-corpus-integrity-and-verified-identity  # named the un-committed builder script in passing; this tempdoc investigates it directly
  - 635-contamination-resistant-eval-corpus          # the procedural-corpus certification pattern this design should extend, not duplicate
  - 623-reproducible-benchmark-release               # the release/cohort provenance layer these corpora would need to plug into once reproducible
---

# 666 — Mixed-corpus reproducibility for MIRACL-de/fr-2k and CourtListener-200

## How this started

Tempdoc 664 (eval-corpus & ratchet-provenance integrity) named, in one passing clause, "the un-committed
`tmp/build-mixed-corpus.py` builder script" as a new root cause found during its investigation — without
specifying which corpora it was for. Separately, an external agent working on an unrelated grant-plan task
independently found that `mixed/miracl-de-2k`, `mixed/miracl-fr-2k`, and `mixed/courtlistener-200` have no
committed, reproducible construction path in the public repo, and asked whether tempdoc 664's mention
"confirmed" their finding.

Checking the actual tempdoc 664 text showed the mention was too vague to confirm anything corpus-specific.
That prompted a direct investigation of the archive repo (`F:\JustSearch`, the frozen, un-squashed private
history predating the public-release cutover — see ADR-0044/tempdoc 634) — 6563 commits, versus the public
repo's curated few. This tempdoc records what that investigation and the following research found.

## What the archive investigation established

- **`tmp/build-mixed-corpus.py` genuinely never existed as a tracked file**, on any branch, in any commit,
  in 6563 commits of full history. Nothing to recover.
- **CourtListener-200's origin commit** (`e874e4d03`, 2026-03-17, "CourtListener 200-doc QDDF eval") added
  exactly three files — the finished `corpus.jsonl`, `qrels/test.tsv`, `queries.jsonl` — no builder script,
  no scraper. The commit message states "200 queries with human qrels." The referenced tempdoc (316)
  describes the source material as "already cached locally" *before* that tempdoc even started — the raw
  document acquisition is undocumented anywhere in this project's history, and the 200-doc stratified
  subset + its human-authored relevance judgments were a one-off manual curation, never scripted.
- **MIRACL-de/fr-2k**: no commit ever added static MIRACL files directly, and it isn't registered in
  `corpora.py`'s `BEIR_DATASETS` map. The earliest reference (tempdoc 343, 2026-03-27) already treats
  `mixed_miracl-de-2k` as a pre-existing materialized directory. The "2k" sampling step from the full MIRACL
  corpus has no visible record anywhere in the full history either.
- **A clarifying comparison, checked to understand why other `mixed/` corpora *do* work**: `mixed/enron-qa`
  is not custom-built either — it's EnronQA, a real published academic dataset (arXiv 2505.00263,
  "EnronQA: Towards Personalized RAG over Private Documents," Ryan/Xu/Nivera/Campos), officially hosted on
  HuggingFace (`MichaelR207/enron_qa_0922`) with 528,304 question-answer pairs already included as ground
  truth. The working, register-validated `mixed/` corpora appear to share one property the broken ones
  lack: they're downloads of an existing, citable, versioned academic release — not a bespoke local
  scrape-and-curate.

## External research: what fixing each corpus would actually take

### MIRACL-de/fr — tractable with existing infrastructure, no new dependency

- `ir_datasets` (already this project's only corpus-loading dependency, already the mechanism behind every
  entry in `BEIR_DATASETS`) **already registers MIRACL directly** — confirmed live: `miracl/de`, `miracl/de/dev`,
  `miracl/fr`, `miracl/fr/dev` are all present in the installed `ir_datasets` registry today, with real
  topics (queries) and qrels, not just raw passages.
- The corpus itself is also directly available via HuggingFace (`miracl/miracl-corpus`, Apache 2.0) with a
  companion `miracl/miracl` dataset ([project-miracl/miracl](https://github.com/project-miracl/miracl))
  holding the topics/qrels for dev and test splits per language — "thorough human-annotations across 18
  diverse languages." Either access path is a standard, citable, permissively-licensed academic release.
- **What's actually missing**: a small, seeded sampling step. The full MIRACL/de or /fr split is much larger
  than the "2k" scale the original corpus used; reproducing the *original* 2026-03-27 sample isn't possible
  (no seed was ever recorded — same "claimed reproducibility without ever diff-testing it" shape tempdoc 664
  found and fixed for the procedural corpora), but a **new**, deterministically-seeded ~2k-document sample
  with its correct paired queries/qrels is straightforward to build and would be genuinely, verifiably
  reproducible going forward — the exact same "accept new content, verified reproducible, rather than force
  a byte-match to something that can't be recovered" resolution tempdoc 664 already reached for
  `needle-burial-v1` and its siblings.

### CourtListener-200 — the document side is solvable; the judgment side is the real blocker

- **Document acquisition is solvable.** The Free Law Project (courtlistener.com's operator) publishes an
  official [Bulk Data API](https://www.courtlistener.com/help/api/bulk-data/) — quarterly CSV snapshots of
  case law, oral arguments, dockets, and more, generated from their production database and streamed to a
  public S3 bucket — plus a documented [REST API](https://www.courtlistener.com/help/api/rest/) for
  targeted fetches. Either path can deterministically re-fetch real US case-law documents at a stated
  snapshot date.
- **The relevance judgments cannot be mechanically reconstructed.** The original 200-doc subset's "human
  qrels" were authored by a person judging query-document relevance directly — there is no way to derive the
  same judgments from raw case-law text alone, and CourtListener's own data does not ship a retrieval
  benchmark (queries + relevance labels) the way MIRACL/EnronQA do. Any rebuild is a **new** corpus with
  **new** judgments, not a restoration.
- **A cleaner alternative surfaced by this pass's research**: real, citable, academic legal-domain retrieval
  benchmarks already exist with genuine human-judged relevance labels — [CLERC](https://arxiv.org/pdf/2306.07471)
  (built on the Caselaw Access Project's 1.8M+ US federal case documents — the same underlying source
  material family as CourtListener) and [COLIEE](https://arxiv.org/pdf/2505.03970) (an established annual
  legal-IR competition benchmark, case-retrieval task). Either would sidestep the judgment-authoring problem
  entirely, the same way this project already gets MIRACL/EnronQA "for free" from academia rather than
  building bespoke ones — **replacing** `mixed/courtlistener-200` with a real published benchmark is a more
  scope-matched fix than trying to re-curate the same bespoke corpus with fresh (necessarily different)
  judgments.

## Design direction (recognized, not built — no code changed this pass)

Both fixes should extend, not duplicate, machinery this codebase already has:

- **MIRACL**: register the `ir_datasets` IDs in `corpora.py`'s existing `BEIR_DATASETS` mechanism (the same
  dict every current corpus uses), then write one small, parameter-recording sampling script mirroring
  `corpus_generate.py`'s own discipline (explicit seed/lang/n_docs/n_queries recorded in
  `generation_provenance`-shaped metadata) — this would let the sampled corpus plug directly into the
  regeneration-determinism check tempdoc 664 just built for the procedural corpora, rather than inventing a
  parallel verification path.
- **CourtListener-200 → a real benchmark**: this is a bigger decision than "write a sampling script" — it
  replaces a named, register-tracked corpus with a different one, which touches whatever's currently pinned
  against `mixed/courtlistener-200` (BM25-dominance findings in the search-quality register, any ratchet
  baselines). This needs the same registered-corpus-catalog thinking tempdoc 664's ninth pass already
  recognized as a real, currently-missing piece (a canonical "which corpora exist" record) — worth resolving
  together rather than as two separate asks.

## Open questions (not resolved by this pass)

- Is replacing CourtListener-200 with CLERC or COLIEE actually acceptable for this project's purposes (legal
  document *shape* — long, dense case-law text — matters more here than exact source identity for most of
  the search-quality findings that cite it)? This is a product/scope call, not a research conclusion.
- Should MIRACL's new deterministic sample target the same "~2k" scale as before, or is there a
  better-justified size now that a real sampling methodology is being designed instead of inherited silently?
- Does this belong in its own implementation tempdoc, get folded into whichever tempdoc eventually resolves
  the "canonical corpus catalog" gap tempdoc 664 recognized-but-declined-to-build, or is it the external
  agent's grant-plan work to pick up directly? Not this pass's call to make.

## What this pass deliberately did not do

No code was written. No corpus was rebuilt. This is a research pass answering "is this fixable, and how" —
matching the discipline this whole family of tempdocs (625, 635, 664) already established: investigate and
name the correct scope before writing anything, especially for a change (replacing a named corpus) that
other work may already be depending on staying exactly as-is.

## Confidence-building pass (second pass, 2026-07-01) — MIRACL confirmed end-to-end; CourtListener candidate settled; a design-clarifying architecture discovery

The user decided: a genuinely new corpus is acceptable for CourtListener's replacement (no attempt to
reconstruct the old, unrecoverable human-judged corpus), and asked for this implemented for real. Before
planning, every load-bearing assumption from the first pass was checked directly rather than trusted.

**MIRACL confirmed working end-to-end, with one real gotcha found and fixed.** Live probes against
`miracl/de/dev` via the already-installed `ir_datasets`: real qrels (3,144 entries, genuine
`TrecQrel(query_id, doc_id, relevance, iteration)` judgments, not placeholders); 305 queries — matching the
*original* `mixed/miracl-de-2k`'s registered query count exactly, strong evidence the original construction
used all dev queries plus a sampled document pool; a real Windows-specific bug in `ir_datasets`' TSV reader
(defaults to the system codepage instead of UTF-8, corrupting German text) fixed by setting
`PYTHONUTF8=1`/`PYTHONIOENCODING=utf-8`; and a full, successful random-access document fetch after a ~19-minute,
~7GB one-time index build for German alone (`(doc_id, title, text)` schema, directly compatible with this
project's existing corpus shape). This closes essentially all technical uncertainty for MIRACL.

**CourtListener replacement candidate: COLIEE ruled out, CLERC confirmed the better fit — checked directly,
not from the earlier web-search summary.** Neither COLIEE nor CLERC is `ir_datasets`-registered (checked the
installed registry directly). COLIEE requires manually registering with the competition organizers to obtain
the data — not something a reproducible script can automate, which disqualifies it outright for
"built to last." CLERC is freely downloadable from HuggingFace (`jhu-clsp/CLERC`) with no gate, and its
record shape (`query` + one `positive_passage` + twenty `negative_passages`) is close to a native fit for
this project's existing query/qrels/distractor shape.

**CLERC's licensing was investigated exhaustively, through five independent channels, before any decision:**
the GitHub repo's actual file listing (via the GitHub API directly, not a guessed path) — no LICENSE file;
GitHub's own automated license detector — `null`; the HuggingFace Hub API's dataset-card metadata (the
authoritative source, not a rendered-page summary) — no `license` field; a full-text search of the paper's
own Ethical Considerations and Data Availability sections — discusses historical-bias risk in legal data,
never states a license. **Conclusion: CLERC genuinely has no stated license anywhere.** Its underlying source
(the Caselaw Access Project) is confirmed CC0/public domain, but CLERC's own added structure (query
generation, positive/negative pairing) is a separate creative artifact the authors never licensed.

**The design-clarifying discovery that resolves this, found while investigating what this project's actual
license/notices enforcement covers:** `scripts/codegen/gen-notices.mjs` (the mechanism behind the
"License and notices" CI check) is scoped entirely to *software redistributed inside the shipped
installer* — npm/Gradle/Cargo dependencies, bundled AI models, bundled Tesseract binaries. It has nothing to
do with eval-time test data. Separately — and this is the finding that actually settles the design — **`datasets/`
is wholesale gitignored in the public repo** (confirmed: `.gitignore:211`), and the original
`datasets/mixed/courtlistener-200/corpus.jsonl` that the archive investigation found committed in the private
history (commit `e874e4d03`) **does not exist in the public repo's git history at all** — it was one of the
paths stripped out during the public-release cutover (tempdoc 634's `git archive HEAD` snapshot step, which
explicitly excluded a named set of paths). **This project already has a standing, universal policy of never
committing materialized corpus data to the public repo, for every corpus, regardless of source** — the
"fetch fresh, never commit" pattern this pass was considering as a special-case mitigation for CLERC turns
out to already be how every BEIR corpus (SciFact, NFCorpus, etc.) and now MIRACL already work. Applying the
identical, already-established pattern to a CLERC-derived corpus is not a new exception — it's the existing
rule, and it resolves the residual licensing exposure the same way it already resolves it for every other
external dataset this project touches: nothing of CLERC's specific structure is ever redistributed via this
repo's own git history, only a small, committed *recipe* (source id, seed, sample size) that fetches the
real thing fresh each time, the same way `corpus_generate.py`'s `generation_provenance` records a recipe
rather than shipping the corpus itself.

**Settled design, both corpora treated symmetrically:** a small, committed "recipe" (mirroring
`corpus_generate.py`'s parameter-recording discipline) records the exact fetch/sample parameters for each —
`ir_datasets` ID + seed + target size for MIRACL, HuggingFace dataset id + seed + target size for the CLERC-
based replacement. A build-time step (parallel to, not a replacement for, `corpus_build.py`'s existing
`build_golden()`) fetches from the real external source using the recorded recipe and materializes the
result into the already-gitignored `datasets/mixed/<name>/` tree — never committed, for either corpus.
`corpora.py`'s existing `_load_local()` reads whichever `mixed/` directory exists exactly as it already does
today; it does not need to change.

**Confidence rating: 8/10.** Every concrete technical unknown from the first pass has a direct, verified
answer, not an inference: MIRACL works end-to-end against real data; the CourtListener candidate decision is
settled with a concrete disqualifying reason for the alternative; the licensing question has a real,
architecture-level resolution grounded in this project's own existing, already-enforced pattern, not a
judgment call weighed against ambiguity. The residual 2 points are ordinary implementation risk — sizing the
recipe parameters correctly and re-baselining the ratchet files `mixed/courtlistener-200` is currently pinned
in (`leak-gate-baselines.v1.json`, `perf-ratchet-baselines.v1.json`, `release.v1.json`) — not open unknowns.

**Recommendation: sonnet**, on the higher-care end of it. The mechanical work follows well-precedented
patterns throughout this project; what raises the bar slightly is sequencing a multi-file production
re-baseline correctly and verifying the fetch-only/gitignore discipline is actually followed (not
accidentally committing a fetched corpus file), not any open-ended architectural judgment.

## Implementation (third pass, 2026-07-01)

Implemented per the approved plan, in the same worktree. Two real bugs were found and fixed during
implementation that the confidence-building pass's spot-checks hadn't caught — recorded here because both
are the kind of "looked like it worked" trap CLAUDE.md's Interrogate Results rule warns about directly.

### What shipped

- **`scripts/jseval/jseval/corpus_fetch.py`** (new): `fetch_miracl_sample()` and `fetch_clerc_sample()`,
  each writing the committed-source shape `corpus_build.build_golden()` already expects. Two new CLI
  commands, `corpus-fetch-miracl` and `corpus-fetch-clerc` (`commands/corpus.py`), fetch into an ephemeral
  temp dir then materialize via the *existing, unchanged* `build_golden()` into `datasets/mixed/<name>/`
  (gitignored — confirmed via `git status`/`git check-ignore` after every build that nothing under
  `datasets/` is ever staged). A small recipe (source id, seed, target sizes) is committed to
  `scripts/jseval/666-corpora/<name>/recipe.json` — mirroring `corpus_generate.py`'s `generation_provenance`
  discipline, the reproducible record instead of the data itself.
- **`mixed/miracl-de-2k`** rebuilt: `corpus-fetch-miracl --lang de --seed 666 --n-docs 3103` → 3103 docs, 305
  queries (all real dev-split queries with a qrel, matching the original corpus's own scale exactly).
- **`mixed/miracl-fr-2k`** rebuilt: `--lang fr --seed 666 --n-docs 5407` → 5407 docs, 343 queries (343, not
  the old register's 316 — the full qrelled dev-split count; 316 had no recorded sampling method to
  reproduce, so this is corrected, not matched).
- **`mixed/legal-clerc-200`** built (new, replaces `mixed/courtlistener-200`): `corpus-fetch-clerc --seed 666
  --n-queries 200` → 198 docs (2 query pairs share a cited document), 200 queries, from CLERC's test split
  (`single-removed`/`direct` task variant — the citing sentence with its citation redacted as the query,
  direct citation as the qrel).
- **Real, live, comparable measurements** (`jseval run --modes hybrid --pipeline --start-backend --clean`,
  retried once each after a documented cold-start race — `dense_requested_but_embedding_compat_blocked`,
  resolved on retry exactly as `docs/reference/jseval-pipeline-reference.md` already describes): miracl-de-2k
  nDCG@10=**0.852**, miracl-fr-2k=**0.866**, legal-clerc-200=**0.521** (full leg-mode breakdown: vector 0.060,
  lexical 0.686, splade 0.059 — BM25-dominant on this corpus too, consistent with the retired corpus's own
  finding, but measured fresh, not inherited).
- **`leak-gate-baselines.v1.json`**: `mixed/courtlistener-200`'s entry replaced with `mixed/legal-clerc-200`
  (measured `leak_rate_max=0.205` from a fresh leg-mode run's `staged_recall_accounting` projection).
  Justified via a `.changesets/leak-gate-mixed_legal-clerc-200-tempdoc666.md` entry (the existing
  `changeset-new` scaffold).
- **`release.v1.json`** recomposed from a fresh, single cohort (git_sha `84b305b2be`) covering `beir/scifact`
  + the 3 tempdoc-666 corpora. `perf-ratchet-baselines.v1.json` needed no direct edit — confirmed its
  `current_release` pointer design correctly re-projected floors for `mixed/legal-clerc-200` automatically
  (`perf-gate`/`relevance-gate` both pass cleanly against it).
- **`docs/reference/search-quality-register.md`**: Dataset Catalog rows updated (courtlistener-200 marked
  RETIRED, legal-clerc-200 + regenerated miracl rows added with recipe pointers); a new "Corpus provenance
  note (2026-07-01, tempdoc 666)" mirrors the exact pattern tempdoc 664's twelfth pass established for
  `needle-burial-v1`; historical courtlistener-200/miracl findings are annotated as predating this change,
  not deleted or silently reinterpreted; a first-measurement ablation table added for `legal-clerc-200`.
  Regenerated `docs/llms.txt` and skills sync per the `docs-regen-hint` hook.
- **`scripts/jseval/tests/test_corpus_fetch.py`** (new, 4 tests): mocked `ir_datasets`/HTTP sources —
  qrelled docs always kept, deterministic sampling reproducible across two calls, unjudged queries dropped,
  CLERC's doc-collection filter excludes unreferenced documents.

### Two real bugs found during implementation, not the confidence-building pass

1. **The encoding "fix" from the confidence-building pass was actually a no-op.** Patching
   `locale.getpreferredencoding` (verified there via `next(ds.queries_iter())` on French — one line) looked
   like it worked, but re-running against the *full* German query file crashed identically. Root cause:
   `ir_datasets/formats/tsv.py` opens its stream as `io.TextIOWrapper(stream)` with no `encoding=`, which on
   Windows resolves through a C-level `sys.flags.utf8_mode` check, never touching the Python `locale` module
   at all — the earlier "confirmation" was a lucky short read, not a working fix (exactly the "expected
   result, don't dig further" trap the confidence-building pass should have caught with a full-file read, not
   a one-line probe). The real fix subclasses `io.TextIOWrapper` to default to UTF-8 — verified by writing
   decoded output to a file and inspecting raw bytes directly (`groß`/`größte`/`berühmte`/`Universität` all
   correctly UTF-8-encoded), not by trusting a terminal print (which separately mis-renders these characters
   regardless of whether the underlying string is correct — a second, cosmetic-only red herring along the way).
2. **The first version of that fix patched `io.TextIOWrapper` globally and never restored it**, silently
   breaking unrelated tests later in the same pytest process (`fsspec`'s `PickleableTextIOWrapper`, used by
   `inspect_ai`, subclasses `io.TextIOWrapper` and passes positional args the patched `__init__` didn't
   expect — 6 unrelated `test_utility_comparison.py` failures). Caught only by running the *full* suite, not
   just this module's own new tests, which had passed throughout (they mock `ir_datasets` entirely and never
   exercised the patch against anything else). Fixed by scoping the patch as a `contextlib.contextmanager`
   that restores the original class on exit.

### One genuine scope boundary hit, logged not fixed

Recomposing `release.v1.json` needs every corpus in one cohort-consistent set. `mixed/enron-qa` has no
committed fetch/materialization mechanism anywhere in this worktree (`datasets/` is gitignored, and there is
no `corpus-fetch-enron` equivalent) — a sibling gap to what this tempdoc fixed for MIRACL/CourtListener, but
genuinely out of this tempdoc's scope. `release.v1.json`'s `mixed/enron-qa` entry was carried over from the
prior release cohort with an explicit `_cohort_note` disclosing it was not remeasured; logged as an
observation (`docs/observations.d/`) rather than fixed here.

### Verification

Full suite: `python -m pytest -q` → 1120 passed (1116 baseline + 4 new), 2 pre-existing failures unrelated to
this work (`test_correction_probe.py::TestLoadManifest` — a missing data file, already failing before this
tempdoc started, explicitly excluded from the baseline count at the start of this session). Every new corpus
was measured against a real, live `--start-backend --clean` run, not asserted from static data. `git status`
confirmed clean throughout: nothing under `datasets/` ever staged, only the recipe/code/docs changes listed
above.

## Post-implementation fixes (fourth pass, 2026-07-01)

A critical-analysis pass over the third pass's diff — re-examining it against this tempdoc's own stated
design, not just re-reading the code — found 4 real, substantive issues, all confirmed live (not just read
from source) before being accepted as real:

1. **Spurious self-demo warnings on every load.** `_write_source()`'s `meta` dict set
   `"suite": "666-mixed-corpus-reproducibility"` in both fetch functions. `corpora.py`'s
   `_validate_golden_set()` treats any non-empty `suite` as "this is a tempdoc-635 self-demo corpus,"
   unconditionally firing 4 warnings (`closed_book_certification`/`fidelity`/`descriptor_collisions`/
   `regeneration_determinism`) that only make sense for procedurally generated synthetic corpora — confirmed
   live: loading `mixed/miracl-de-2k` via `corpora.load()` with `warnings.simplefilter('always')` produced
   all four. **Fixed** by removing the `suite` key entirely; these are real external corpora, not suite
   members.
2. **The provenance record was silently dropped on materialization.** `corpus_build.build_golden()` only
   threads through `src_meta.get("generation_provenance")` into the written `metadata.json` — confirmed live:
   `datasets/mixed/miracl-de-2k/metadata.json` had `"generation_provenance": null` even though the design's
   entire point was recording this. The code wrote a differently-named key, `fetch_provenance`, that
   `build_golden()` never reads. **Fixed** by renaming the recorded key to `generation_provenance` (reusing
   the existing field rather than patching the shared `build_golden()`, which tempdoc 635's own golden/
   corpora also depend on). Two regression-guard assertions were added to the unit tests, checking the actual
   on-disk `meta.json` key — not just the returned dict — since that's precisely the gap that let the
   original bug through undetected.
3. **The register's documented regeneration commands didn't actually run.** All three Dataset Catalog notes
   gave a command missing required CLI flags (`--name`, and `--n-docs`/`--n-queries`) — confirmed against
   `corpus-fetch-miracl --help`/`corpus-fetch-clerc --help`'s `required=True` options. Given this tempdoc's
   entire purpose is fixing "no reproducible construction path," shipping an incomplete reproduction command
   undermined that goal directly. **Fixed** by correcting all three notes to the full, runnable commands.
4. **The reproducibility claim had never been verified against the live source, only mocked.** The existing
   unit test proves the *sampling algorithm* is deterministic given a fixed, in-memory doc order — it never
   confirmed the real external source's iteration order is stable across two genuinely separate fetches, the
   exact class of claim tempdoc 664 itself built a `regeneration_determinism` check for. **Verified live**:
   `fetch_clerc_sample(seed=666, n_queries=200)` was run twice — once into a fresh temp location, once via
   the real `corpus-fetch-clerc` CLI command, several minutes apart, over two fully independent HTTP fetches
   of CLERC's multi-gigabyte collection stream — and `docs.jsonl`/`queries.json` from both runs are
   byte-identical (`sha256` match on both files). The reproducibility claim holds for real, not just in a
   mock.

All three corpora were rebuilt via their actual CLI commands (not hand-patched output files) so the fixes
were exercised for real: `mixed/miracl-de-2k` and `mixed/miracl-fr-2k` reused the already-cached `ir_datasets`
local data (fast, no fresh download); `mixed/legal-clerc-200` re-fetched its collection stream fresh (the same
run that produced the reproducibility diff above). Corpus *content* is unchanged (confirmed: the
`corpus_signature` for `mixed/miracl-de-2k` is byte-identical to the third pass's,
`d6f4026b4b25ac0d117353b830022d77ef3b863b15187907d512d645fae607a1`) — only the metadata fields changed, so
the previously-measured nDCG@10 numbers and the leak-gate/`release.v1.json` baselines did not need
re-deriving.

**Verification:** `python -m pytest tests/test_corpus_fetch.py -q` (6 tests, 2 new regression guards) and the
full suite (`python -m pytest -q` → 1120 passed, the same 2 pre-existing unrelated failures) both green. All
three corpora reloaded via `corpora.load()` with `warnings.simplefilter('always')` produce zero warnings.
`git status` confirmed clean — nothing under `datasets/` ever staged.

No security or privacy issue was found during this review (no credentials, no user-controllable input reaches
the hardcoded HuggingFace fetch URLs, and CLERC's underlying documents are already-public US federal court
opinions).

## Future work: polish, extension, and practicality ideas (fifth pass, research only, 2026-07-01)

A research-only pass (no code changed) asking "now that this design exists and works, what could it become,
and where does it actually hurt a real user/contributor today." Grounded in direct experience implementing
and reviewing this tempdoc, plus external research (BEIR/MTEB reproducibility conventions, HuggingFace's
revision-pinning support) and a quick internal survey of the rest of the `mixed/` catalog. Nothing here is
required — all of it is optional, unprioritized, for whoever picks this up next.

### The single most important finding: no upstream revision pinning

Both fetchers always read whatever is *currently* at the source's `main`/latest state — `fetch_clerc_sample`
hits `huggingface.co/.../resolve/main/...`; `fetch_miracl_sample` goes through whatever `ir_datasets` itself
resolves today. Confirmed live: CLERC's HuggingFace repo (`jhu-clsp/CLERC`) exposes a concrete commit SHA via
its API (`ef042f8ab436f78704f17faa0a866d1b2b862f6f` as of this pass), and HuggingFace's URL scheme supports
`resolve/<sha>/<path>` instead of `resolve/main/<path>` — meaning the fetch *could* be pinned to an exact,
frozen snapshot. Right now it isn't. If CLERC's upstream repo is ever corrected, reorganized, or re-uploaded,
the *same recorded seed* could someday produce a *different* corpus, quietly invalidating the reproducibility
this whole tempdoc was built to establish — external research confirms real IR benchmarks (MTEB) explicitly
version-pin dataset revisions for exactly this reason. This is the one item worth treating as more than
"nice to have."

### Extension: at least two other `mixed/` corpora likely have the identical gap

- **`mixed/miracl-zh-2k`** is the same MIRACL family as the two corpora this tempdoc already fixed —
  `fetch_miracl_sample()` is already language-generic (`lang="zh"` is the only change needed), making this
  close to a one-line extension, not new design work.
- **`mixed/cord19-qddf`** is backed by CORD-19/TREC-COVID, confirmed live to also be natively registered in
  `ir_datasets` (`cord19`, `beir/trec-covid`, and per-round variants) — worth checking whether it has the same
  undocumented-provenance problem this tempdoc found for MIRACL, since the fix shape would likely be similar.
- **`mixed/enron-qa`** already has a logged observation (this tempdoc's third pass) — the natural closing move
  is a `fetch_enron_qa_sample()` following the same pattern (fetch from `MichaelR207/enron_qa_0922` on
  HuggingFace), which would also let `release.v1.json` recompose as one clean cohort instead of carrying over
  a stale entry with a disclosure note.
- Generalizing `fetch_miracl_sample` into a dataset-id-parameterized `fetch_ir_datasets_sample(dataset_id,
  ...)` would turn "the MIRACL fix" into "the mechanism for this whole class of corpus," closer to what
  tempdoc 664's ninth pass gestured at with the (recognized, still-undecided) "canonical corpus catalog" idea.

### Practicality: what actually hurts a new contributor today

- **The first-run cost is real and currently invisible.** A fresh MIRACL language fetch is a genuine
  ~19-minute, ~7GB one-time download; CLERC's is ~10-15 minutes with zero progress output the entire time
  (confirmed directly — `ir_datasets`' own downloads show `tqdm` progress bars, but this project's CLERC
  fetch is silent, so a contributor watching it for 10 minutes with no output would reasonably suspect it had
  hung). A single up-front line ("fetching ~Xk docs, expect ~N minutes") plus periodic progress logging during
  the CLERC stream would close this gap cheaply.
- **A fresh checkout gives a confusing raw error.** `datasets/` is gitignored by design, so *every* fresh
  clone starts with zero corpora materialized — confirmed live: `corpora.load('mixed/miracl-zh-2k')` on an
  unfetched corpus raises a bare `FileNotFoundError: Dataset directory not found`, with no hint that a
  `corpus-fetch-*` command exists or which one to run. A friendlier message (reading the matching
  `666-corpora/<name>/recipe.json` if present, and naming the exact command to run) would meaningfully lower
  the barrier for a new contributor's very first `jseval run`.
- **No retry/resume on a dropped connection.** Both fetches are single, unbroken streams; an interruption at
  minute 14 of a 15-minute CLERC fetch means starting over from zero. Not a correctness bug, but a real
  fragility for anyone on a slower or less reliable connection.
- **Regenerating an existing corpus means re-typing its own recorded recipe by hand.** The exact class of
  mistake the fourth pass's critical-analysis caught (an incomplete, hand-typed command in the register) is
  structurally invited by the current design: the recipe is already committed to
  `666-corpora/<name>/recipe.json`, but the CLI still requires every parameter to be re-typed rather than
  read from that file. A `--from-recipe` mode (or making bare `--name` sufficient, reading lang/seed/sizes
  from the committed recipe) would remove this whole error class at the source rather than relying on careful
  documentation.

### Lower-priority polish

- A `jseval corpus-fetch --all` (or similar) that walks every `666-corpora/*/recipe.json` and rebuilds
  everything in one pass would help onboarding, since a fresh contributor currently has no single command to
  "get every corpus this project's evals use."
- A `--verify`/`--check` mode (re-fetch into a temp dir, diff against what's materialized) would turn the
  fourth pass's one-time manual reproducibility diff into something CI or a future contributor can re-run on
  demand — the institutionalized version of the ad hoc check this tempdoc's own fourth pass had to do by hand.
- Surfacing recipe/provenance status inline in `jseval datasets`'s existing output (e.g. "regenerable via
  recipe: yes/no") would make it visible at a glance which corpora are reproducible vs. still static, without
  needing to separately consult the register.
- The two fetch functions' sampling logic (reservoir sampling for MIRACL, plain `rng.sample` for CLERC query
  IDs) could be unified behind one shared "deterministic sample" helper — a code-cleanliness simplification,
  not a functional gap.

None of the above was implemented this pass — this is deliberately a research/ideation record, matching the
user's request that this stay documentation-only.
