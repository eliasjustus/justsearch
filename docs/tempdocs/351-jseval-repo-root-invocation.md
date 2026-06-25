---
title: "351: jseval CWD-Independent Path Resolution"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-24
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 351: jseval CWD-Independent Path Resolution

## Goal

jseval produces identical behavior regardless of invocation CWD. No `cd`
required, no CWD leaks into the shell session.

## Root Cause

Six default path values in CLI options and internal functions used bare
relative strings (`"tmp/eval-results"`, `Path("tmp/bench/claim-a")`, etc.)
that resolved against CWD. Meanwhile, three modules already had correct
`_repo_root()` functions using `Path(__file__).resolve().parents[3]` — the
issue was just that the defaults didn't use them.

When agents ran `cd scripts/jseval && python -m jseval run ...`, the CWD
change persisted in the shell session, breaking subsequent Gradle builds
and model file paths that assume repo root as CWD.

## Motivation

Tempdoc 334: after `cd scripts/jseval` for jseval runs, model file
paths (`models/splade/...`) broke.

## What Was Done

### 1. Shared path constants (`jseval/_paths.py`)

Created `jseval/_paths.py` with:
- `REPO_ROOT` — single source of truth, replaces 3 duplicate `_repo_root()` functions
- `DEFAULT_EVAL_RESULTS`, `DEFAULT_BENCH_CLAIM_A`, `DEFAULT_BENCH_TRACK_G` — absolute paths
- `default_corpus_dir(dataset_name)` — absolute corpus materialization path

### 2. Anchored all CWD-relative defaults

| File | Before (CWD-relative) | After (absolute) |
|------|----------------------|-------------------|
| `cli.py` (3 places) | `"tmp/eval-results"` | `str(DEFAULT_EVAL_RESULTS)` |
| `ingest.py` | `Path(f"tmp/eval-corpora/{name}")` | `default_corpus_dir(name)` |
| `gradle_bench.py` | `Path("tmp/bench/claim-a")` | `DEFAULT_BENCH_CLAIM_A` |
| `gradle_bench.py` | `Path("tmp/bench/track-g")` | `DEFAULT_BENCH_TRACK_G` |

### 3. Deduplicated repo root resolution

| File | Before | After |
|------|--------|-------|
| `backend.py` | `_find_repo_root()` (own impl) | Delegates to `REPO_ROOT`; kept as deprecated alias for test compat |
| `gradle_bench.py` | `_repo_root()` (own impl) | Replaced with `REPO_ROOT` import |
| `rag_eval.py` | `_repo_root()` (own impl) | Delegates to `REPO_ROOT` |
| `corpora.py` | Inline `parents[3]` | Imports `REPO_ROOT` |

### 4. Updated test

`test_ingest.py::test_prepare_corpus_beir_materializes` — replaced
`monkeypatch.chdir(tmp_path)` workaround with `monkeypatch.setattr` on
`default_corpus_dir`, decoupling the test from filesystem state.

## Items Not Pursued (and why)

- **`--repo-root` CLI flag / env var resolution chain**: The `parents[3]`
  approach is deterministic — jseval lives at a fixed depth in the repo.
  A configurable resolution chain adds complexity for a problem that doesn't
  exist.
- **MSYS2 path normalization**: The original tempdoc 334 issue was about
  model paths passed as shell arguments, not jseval's internal resolution.
  Orthogonal concern.
- **Repo-root invocation (`python -m scripts.jseval...`)**: Once defaults
  are absolute, `cd scripts/jseval && python -m jseval` works without CWD
  side effects. Package restructuring for marginal convenience isn't justified.

## Verification

321/321 jseval tests pass. No `monkeypatch.chdir` workarounds remain.

### End-to-end CWD-independence verified

Path constants resolve identically from three different CWDs:

| CWD | `REPO_ROOT` | `DEFAULT_EVAL_RESULTS` |
|-----|-------------|----------------------|
| `scripts/jseval/` | `D:\code\JustSearch` | `D:\code\JustSearch\scripts\jseval\tmp\eval-results` |
| repo root | `D:\code\JustSearch` | `D:\code\JustSearch\scripts\jseval\tmp\eval-results` |
| `/tmp` | `D:\code\JustSearch` | `D:\code\JustSearch\scripts\jseval\tmp\eval-results` |

`jseval trend --dataset scifact --mode lexical` produces identical output
(`{"status": "insufficient_data", "comparable_runs": 0}`) from all three
directories. Previously, invocation from repo root or `/tmp` would have
resolved `tmp/eval-results` against CWD and either crashed or returned
wrong results.

## Dependencies

- **334 (Single-Pass Enrichment):** Path issues during experiments — resolved.
- **335 (jseval Pipeline Observability):** Parent jseval improvements.
