---
title: "Shared, cross-worktree dataset-fetch cache: dedupe multi-GB corpus downloads across worktrees"
type: tempdocs
status: "implemented — compile/import clean, full jseval unit suite green (1603 passed, only the 2 pre-existing unrelated `test_correction_probe.py::TestLoadManifest` failures remain, per `expected-state.v1.json`); live network verification (an actual CLERC/MIRACL refetch) intentionally NOT run per task constraints — the network seam is fully mocked in tests"
created: 2026-07-10
author: agent (Fable orchestration), founder-directed fix
category: eval-infrastructure / dev-tooling / corpus-fetch / worktree-sharing
related:
  - 666-mixed-corpus-reproducibility          # owns the recipe/fetch/licensing pattern this cache sits underneath
  - 635-contamination-resistant-eval-corpus    # the "unverified cache -> silent nDCG-0.0" lesson this design's integrity check is built to avoid repeating
  - 704-measurement-substrate-correct-data-program  # program frame; this is dev-tooling supporting it, not a pillar itself
---

# 709 — Shared dataset-fetch cache across worktrees

## Problem

Every eval dataset lives under `datasets/` (wholesale gitignored) and
`scripts/jseval/tmp/eval-corpora/` (also gitignored) — both worktree-local, both deleted on
worktree teardown, per this project's universal "fetch fresh, never commit/redistribute"
corpus-licensing policy (see `corpus_fetch.py`'s module docstring and the "Corpus provenance
note" entries in `docs/reference/search-quality-register.md`). Concrete cost observed
2026-07-10: the CLERC fetch (`jseval corpus-fetch-clerc`, a GB-scale collection stream scanned
for 198 sampled docs, ~15+ min) ran twice in one day in two different worktrees, because
neither worktree could see the other's already-downloaded copy.

Model files already solved this exact class of problem: `shared_models_dir()`
(`scripts/jseval/jseval/_paths.py`, tempdoc 644 Axis 1) resolves worktree eval runs against the
MAIN checkout's `models/` directory (which holds the actual LFS binaries) instead of a
worktree's pointer-only copy — mirrored in `scripts/dev/dev-runner.cjs`. This tempdoc gives the
dataset-FETCH layer the same shared-cache pattern.

## Pinned constraints (founder-ratified; unchanged by this design)

a. **Licensing posture unchanged.** The cache is gitignored, never committed, and purely a
   network-trip deduplication. The "never redistribute fetched content" policy is untouched —
   the cache lives under `scripts/jseval/tmp/`, which is already wholesale gitignored
   (`.gitignore:218`), so no new gitignore entry was needed.
b. **Shared location resolved like models.** Default cache root is under the MAIN checkout
   (via the same worktree-to-main resolution `shared_models_dir()`/`main_repo_root()` already
   provide in `_paths.py`). `JUSTSEARCH_DATASET_CACHE` env overrides; empty string or `"0"`
   disables caching entirely. When the main checkout is not resolvable (or the cache root is
   unwritable), the design fails OPEN to a direct, uncached, ephemeral fetch — a cache must
   never make a fetch fail that would have succeeded without it.
c. **Integrity-verified cache hits.** A cache hit is verified via `corpus_identity.corpus_signature`
   (its explicit `files=` mode) against a `signature.json` recorded at write time. A mismatch,
   missing signature file, or missing member file is treated as a MISS — refetch and overwrite,
   never a silent stale/corrupted read (the 635 lesson: an unverified cache once produced a
   silent nDCG-0.0 run).
d. **Concurrency-safe.** Writes are atomic (populate into a temp dir under the cache root, then
   a single `os.replace`); a concurrent reader either sees a complete entry or a miss.
   Last-writer-wins on identical deterministic content is acceptable and is exactly what this
   design does when a second writer races the final publish rename.
e. **Coverage decision: cache the RAW upstream artifacts, not the sampled output.** See
   "Design decisions" below.
f. **Zero behavior change when disabled or absent.** All 1603 pre-existing jseval tests pass
   unmodified; a new autouse fixture (`tests/conftest.py`) forces `JUSTSEARCH_DATASET_CACHE=0`
   for every test by default so a worktree's own test run never writes into the real main
   checkout's cache directory — verified live (see "Verification" below).

## Design

### Cache root resolution

`scripts/jseval/jseval/dataset_cache.py::cache_root()`:

- `JUSTSEARCH_DATASET_CACHE` env wins if set: empty/`"0"` → `None` (disabled); any other value
  → used verbatim as the cache root (e.g. a shared CI cache mount).
- Otherwise: `main_repo_root() / "scripts" / "jseval" / "tmp" / "dataset-fetch-cache"` — the
  exact same worktree-to-main resolution `shared_models_dir()` uses. `main_repo_root()` walks
  a linked worktree's `.git` *file* (`gitdir: <main>/.git/worktrees/<name>`) back to the main
  checkout; falls back to `REPO_ROOT` (today's worktree-local behavior) on any parse failure —
  already exercised in production for models, reused verbatim here, no new mechanism invented.
- Never raises. A downstream write failure (permissions, read-only mount, disk full) is caught
  at the `cached_dir()` call site and falls back to a direct uncached fetch.

### Cache key

`(fetcher, recipe-identity params)`, hashed to a 32-char directory name
(`_entry_key`). For CLERC's raw fetch, the key is `{"base": <HF resolve URL>, "task_variant":
"single-removed/direct"}` — deliberately **not** `seed`/`n_queries`/`n_docs`, because the raw
bytes (qrels tsv, queries tsv, the several-GB document collection) are the same regardless of
how a caller later samples them. Caching at this layer means a *different* seed or sample size
in a *different* worktree still gets a cache hit — strictly better than caching one seed's
sampled output, which the task brief itself flagged as the right call.

### Verification

`lookup()` reuses `corpus_identity.corpus_signature(entry_dir, files=[...])` (its existing
explicit-`files=` mode, already used for non-golden reference corpora) to hash the cached
member files in a fixed order and compares against the `signature` recorded in
`signature.json` at write time. Any of: missing `signature.json`, a `JSONDecodeError`, a
missing member file, or a hash mismatch → treated as a miss. `store()` on a miss always
refetches, and (see the bug found below) always ends with the new content in place, not the
stale/corrupted content.

### Atomicity / concurrency

`store()` populates a temp directory (`tempfile.mkdtemp(dir=fetcher_dir)`) via the caller's
`populate(tmp_dir)` callback, writes `signature.json`, then publishes:

1. If `final_dir` already exists (a stale/corrupted entry being refetched, or a genuinely
   concurrent writer that finished first), it is atomically moved aside
   (`os.replace(final_dir, stale_dir)`) and deleted — this always happens, unconditionally,
   not only in the concurrency case.
2. `os.replace(tmp_dir, final_dir)` publishes the new entry. If a second writer raced into the
   slot between steps 1 and 2, this `os.replace` raises (both POSIX and Windows reject
   replacing a directory that reappeared non-empty); that specific case is treated as
   "last-writer-wins, discard mine."

**Bug found and fixed during implementation** (this is exactly why `slice-execution.md`'s
bidirectional pass and this project's "audit-driven fixes need a test" rule exist): the first
version of `store()` did NOT move `final_dir` aside before attempting `os.replace(tmp_dir,
final_dir)`. On a signature-mismatch refetch, `final_dir` already existed (holding the
corrupted content), so the plain `os.replace` raised `OSError` (Windows rejects replacing a
non-empty directory), which the "last-writer-wins, discard ours" catch swallowed —
**silently keeping the corrupted content in place instead of overwriting it with the
refetch.** `test_signature_mismatch_is_treated_as_miss_and_refetches` caught this immediately
(asserted the refetched content, got the corrupted content back). Fixed by unconditionally
moving any existing `final_dir` aside before publishing, so a refetch always lands the new
content; the "last-writer-wins" `except OSError` is now reached only by a genuinely
concurrent second writer racing between the move-aside and the publish, not by ordinary
overwrite-an-existing-entry.

A second correctness fix during implementation: `cached_dir()`'s original draft wrapped
`yield store(...)` in a bare `try/except OSError`, which — because a `@contextlib.
contextmanager` generator's `yield` suspends *inside* that `try` block while the caller's
`with`-body runs — would have mis-caught an `OSError` raised by the *caller's own code* while
using the cached directory (e.g. a genuine read error unrelated to cache-write failure) as if
it were a cache-unavailability signal, silently re-running `populate()` a second time. Fixed
by computing `entry_dir = store(...)` (catching only `store()`'s own `OSError`) *before* any
`yield`, so the caller's body is never inside that except's scope.

### Coverage

| Source | Layer cached | Mechanism |
|---|---|---|
| CLERC (`corpus_fetch.fetch_clerc_sample`) | Raw upstream artifacts: `qrels-doc.test.direct.tsv`, `test.single-removed.direct.tsv`, `collection.doc.tsv.gz` (the GB-scale one) | `dataset_cache.cached_dir("clerc-raw", ...)` — custom integrity-verified cache (this module) |
| MIRACL (`corpus_fetch.fetch_miracl_sample`) + all BEIR (`corpora._load_beir`) | ir_datasets' own download cache | **Config-only**: `dataset_cache.apply_ir_datasets_home()` sets `IR_DATASETS_HOME` (if unset) to a dir under the shared cache root. Confirmed via `ir_datasets.util.home_path()` source: `Path(os.environ.get('IR_DATASETS_HOME', Path.home() / '.ir_datasets'))` — `ir_datasets` already does its own on-disk caching + verification once this env var points somewhere shared, so no new caching/signature code was needed for this source. |
| `scripts/search/fetch-realdocs-corpus.py` (govdocs1/NapierOne pinned zips) | Downloaded zip archives | **Config-only, trivially in-seam**: this script already treats `CACHE_DIR` as a persistent on-disk cache (`download()` skips a zip that's already present) — the only gap was that `CACHE_DIR` defaulted to this checkout's own `datasets/.download-cache`. Added a ~25-line `_resolve_download_cache_dir()` honoring the same `JUSTSEARCH_DATASET_CACHE` convention, defaulting to a dir under the main checkout via the same gitdir-file-walk `_paths.main_repo_root()` uses (duplicated locally rather than importing the `jseval` package, since this standalone script has no other jseval dependency). This script already has its own per-file sha256 manifest-verification (a *different*, pre-existing integrity mechanism, unrelated to `dataset_signature`), so no new verification code was needed here either. |

### Behavior change accepted, and why it's fine

CLERC's original code had an early-`break` optimization for the `n_docs=None` (qrelled-only,
no distractors) path: stop scanning the collection stream once every wanted doc is found,
potentially avoiding a full GB-scale read. Caching raw artifacts requires materializing the
*entire* collection file to disk on a cache miss (the early break can't apply during
`populate()`, since the cache doesn't know yet which docs a *future* call might want). This is
a real, accepted cost — but it doesn't change the dominant real-world case: the actual
production recipe (`666-corpora/legal-clerc-200/recipe.json`, `n_docs: 198`) already sets
`n_docs`, and the early-break optimization only fires when `n_docs is None` — confirmed via
`corpus.py`'s CLI wiring, `n_docs` defaults to `None` only when the caller omits `--n-docs`,
which the shipped recipe does not do. So the early break was already dead in the recipe that
matters; the first fetch's cost is unchanged in practice, and every subsequent fetch (any
seed, any worktree) drops to zero network cost.

## Files changed

- `scripts/jseval/jseval/dataset_cache.py` (new) — the cache module: `cache_root()`,
  `lookup()`, `store()`, `cached_dir()` (context manager), `apply_ir_datasets_home()`.
- `scripts/jseval/jseval/corpus_fetch.py` — CLERC's raw fetch (qrels/queries/collection) now
  goes through `dataset_cache.cached_dir("clerc-raw", ...)`; sampling logic split out into
  `_sample_clerc_from_raw()` (pure function over already-fetched raw files, no network access
  of its own) so the cache-eligible fetch and the seed-dependent sampling are two separate
  concerns. `fetch_miracl_sample` calls `dataset_cache.apply_ir_datasets_home()` before
  `ir_datasets.load()`.
- `scripts/jseval/jseval/corpora.py` — `_load_beir` calls
  `dataset_cache.apply_ir_datasets_home()` before `ir_datasets.load()`.
- `scripts/jseval/tests/conftest.py` — new autouse fixture
  `_disable_shared_dataset_cache_by_default` forces `JUSTSEARCH_DATASET_CACHE=0` for every
  test, so the suite never writes into the real main checkout merely by running.
- `scripts/jseval/tests/test_dataset_cache.py` (new) — 20 tests: miss→populate, hit→no-
  repopulate, different-params→different-entries, signature-mismatch→refetch (the bug-catching
  test), missing-signature-file→miss, missing-member-file→miss, atomic-layout (no leftover tmp
  dirs), populate-failure→cleanup+raise, disabled-mode passthrough (with population still
  happening, just not cached), empty-string-disables, main-checkout-unresolvable→fail-open,
  cache-root-unwritable→fail-open, `apply_ir_datasets_home` enabled/disabled/respects-override.
- `scripts/jseval/tests/test_corpus_fetch.py` — 2 new tests: a second `fetch_clerc_sample` call
  (different seed/n_queries/n_docs) hits the cache with `urlopen` patched to explode if called;
  `fetch_miracl_sample` sets `IR_DATASETS_HOME` when the cache is enabled.
- `scripts/jseval/tests/test_corpora.py` — 2 new tests: `_load_beir` sets/doesn't-set
  `IR_DATASETS_HOME` depending on cache enablement.
- `scripts/search/fetch-realdocs-corpus.py` — `_resolve_download_cache_dir()` (config-only,
  see Coverage table above).

No CLI command surface changed (no new/renamed `jseval` commands), so
`jseval/commands/inventory.generated.json` did not need regeneration.

## Verification

- `PYTHONPATH=. python -m pytest tests/test_dataset_cache.py tests/test_corpus_fetch.py
  tests/test_corpora.py -q` → 41 passed (first run surfaced the `store()` overwrite bug above;
  fixed, then green).
- Full suite: `PYTHONPATH=. python -m pytest -q` from `scripts/jseval` →
  **1603 passed, 2 failed** — the 2 failures are exactly
  `tests/test_correction_probe.py::TestLoadManifest::{test_loads_default,
  test_has_typo_and_control_queries}`, the pre-existing, unrelated, documented-red failures
  (`expected-state.v1.json`'s `correction-eval-queries-missing` entry — a missing data file,
  nothing to do with this change). No new failures anywhere in the suite.
- Confirmed live (not just asserted): after the full suite run, `F:\justsearch-public\scripts
  \jseval\tmp\dataset-fetch-cache` does **not** exist — the autouse fixture actually prevented
  the worktree's test run from writing into the real main checkout, not just in theory.
- `python -m py_compile scripts/search/fetch-realdocs-corpus.py` — syntax-clean.
- Confirmed the worktree-to-main resolution actually resolves correctly in this environment
  (not just in unit-mocked form): `main_repo_root()` and the duplicated resolver in
  `fetch-realdocs-corpus.py` both independently resolved to `F:\justsearch-public` (the real
  main checkout, per `git worktree list`) when run live from this worktree.
- No live network fetch (an actual CLERC/MIRACL refetch) was run, per the task's explicit
  constraint — the network seam (`urlopen`/`Request`/`ir_datasets.load`) is fully mocked in
  every test that exercises the cache.

## Follow-ups (left out by the pinned constraints, not silently dropped)

- The CLERC "early break for n_docs=None" micro-optimization is now dead code for any
  caller that omits `--n-docs` on a cache MISS (it still works, just never triggers, since the
  full collection is always materialized during `populate()`) — not removed, since it's cheap,
  harmless, and correctly still narrows the *sampling* loop's CPU work even when reading from
  a local cached file instead of a live network stream.
- `store()`'s `except OSError` in `cached_dir()` cannot currently distinguish "the cache
  directory itself is unwritable" from "populate()'s own network fetch failed with an OSError
  subclass" (e.g. `urllib.error.URLError`) — both fall back to a second, ephemeral, direct
  fetch attempt. This is safe (fail OPEN is preserved either way; a genuine permanent network
  failure still surfaces, just after one extra redundant attempt) but is a minor inefficiency
  worth tightening later if it turns out to matter in practice.
- Not investigated: whether NapierOne/govdocs1's *ETag*-based staleness check (already present
  in `fetch-realdocs-corpus.py` for a different purpose) could also serve as an additional
  integrity signal for the shared cache; the existing per-file sha256 manifest verification
  already covers correctness, so this would be belt-and-suspenders, not a gap.
