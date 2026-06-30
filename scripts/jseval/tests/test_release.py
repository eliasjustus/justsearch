"""Unit tests for the benchmark-release composer (tempdoc 623).

Pure-function tests over summary.json-shaped dicts (mirrors the
``test_relevance_gate.py`` inline-fixture style — no live run needed).
"""

from __future__ import annotations

import pytest

from jseval import release


# --- fixtures ---------------------------------------------------------------

# config-global commit_metadata fps — identical across corpora for one config.
_CONFIG_GLOBAL = {
    "schema_fp": "schema-AAA",
    "similarity_fp": "sim-AAA",
    "boosts_fp": "boost-AAA",
    "grammar_hash": "gram-AAA",
}
# corpus-dependent fps — DIFFER between scifact (BEIR) and the mixed corpora.
_SCIFACT_CORPUS_FPS = {
    "field_catalog_hash": "fc-scifact",
    "index_schema_fp": "is-scifact",
    "analyzer_fp": "an-scifact",
    "synonyms_hash": "syn-empty",
}
_MIXED_CORPUS_FPS = {
    "field_catalog_hash": "fc-mixed",
    "index_schema_fp": "is-mixed",
    "analyzer_fp": "an-mixed",
    "synonyms_hash": "syn-mixed",
}


def _manifest(*, git_sha="commitAAA", corpus_fps=None, embed_gpu=True,
              envelope=None, dataset="beir/scifact", reranker_gpu=True,
              realized_engines=("dense", "reranker", "splade")):
    cm = dict(_CONFIG_GLOBAL)
    cm.update(corpus_fps or _SCIFACT_CORPUS_FPS)
    return {
        "git_sha": git_sha,
        "dataset": dataset,
        "eval_protocol_hash": "evalproto-AAA",
        "policy_hash": "policy-AAA",
        "commit_metadata": cm,
        "model_fingerprints": {
            "embed_fingerprint": "embed-AAA",
            "splade_model_path": "/models/splade",
            "embed_gpu": embed_gpu,      # execution flag — must NOT affect key
            "splade_gpu": embed_gpu,
            "reranker_gpu": reranker_gpu,  # tempdoc 644: execution flag — must NOT affect key
            # tempdoc 644: the realized engine SET — startup-stable identity; CE-on vs CE-off
            # must form distinct cohorts so a degraded run can't be averaged with a CE-on baseline.
            "realized_engines": list(realized_engines),
        },
        "non_determinism_envelope": envelope,
        "env_fingerprint": {"gpu": {"name": "RTX 4070"}},
        "inference_status_snapshot": {
            "tier": "gpu_12gb_plus",
            "gpu": {
                "totalVramBytes": 12884901120,
                "nvmlDriverVersion": "610.47",
                "cudaVersion": "12",  # Head-side constant (no ORT init)
            },
        },
        # ORT version rides the worker effective_config (debug-only WorkerDebugView, tempdoc 623 U7),
        # retained un-hashed in /api/debug/state.
        "debug_state_snapshot": {
            "worker": {"effective_config": {"ort.version": "1.20.0"}}
        },
    }


def _summary(*, dataset="beir/scifact", git_sha="commitAAA", corpus_fps=None,
             modes=None, query_count=300, embed_gpu=True, envelope=None):
    """modes = {mode_name: {metric: value, ...}}"""
    modes = modes or {"hybrid": {"nDCG@10": 0.758, "P@1": 0.62, "R@10": 0.89}}
    per_mode = {
        m: {
            "aggregate_metrics": metrics,
            "comparable": True,
            "ann_proof_status": "PASS",
        }
        for m, metrics in modes.items()
    }
    return {
        "dataset": dataset,
        "query_count": query_count,
        "doc_count": 5183,
        "qrels_summary": {"relevance_mode": "binary", "query_count": query_count},
        "corpus_identity": {"signature": None},
        "per_mode": per_mode,
        "manifest": _manifest(
            git_sha=git_sha, corpus_fps=corpus_fps, embed_gpu=embed_gpu,
            envelope=envelope, dataset=dataset,
        ),
    }


_AT = "2026-06-21T00:00:00+00:00"


# --- config_cohort_key ------------------------------------------------------

def test_config_key_ignores_corpus_dependent_fields_U1():
    """The U1 regression guard: scifact and a mixed corpus at the same config
    MUST share a config_cohort_key, even though their corpus-dependent
    commit_metadata fps differ. Otherwise no multi-corpus release composes."""
    scifact = _manifest(corpus_fps=_SCIFACT_CORPUS_FPS, dataset="beir/scifact")
    courtlistener = _manifest(corpus_fps=_MIXED_CORPUS_FPS, dataset="mixed/courtlistener-200")
    assert release.config_cohort_key(scifact) == release.config_cohort_key(courtlistener)


def test_config_key_ignores_gpu_execution_flags():
    """A GPU run and a CPU run of the same config share the key (flags excluded)."""
    gpu = _manifest(embed_gpu=True)
    cpu = _manifest(embed_gpu=False)
    assert release.config_cohort_key(gpu) == release.config_cohort_key(cpu)


def test_config_key_ignores_reranker_gpu_execution_flag():
    """tempdoc 644: the reranker DEVICE bit is execution-context (flaky pre-query) and is
    stripped from the key, exactly like embed_gpu/splade_gpu — a GPU reranker run and a CPU
    reranker run of the same config share the key."""
    gpu = _manifest(reranker_gpu=True)
    cpu = _manifest(reranker_gpu=False)
    assert release.config_cohort_key(gpu) == release.config_cohort_key(cpu)


def test_config_key_changes_on_realized_engine_set():
    """tempdoc 644: the realized engine SET (presence) IS cohort identity — a CE-on run and a
    CE-off (cross-encoder silently absent) run of the same commit MUST form distinct cohorts so
    the degraded run cannot be averaged with a CE-on baseline."""
    ce_on = _manifest(realized_engines=("dense", "reranker", "splade"))
    ce_off = _manifest(realized_engines=("dense", "splade"))
    assert release.config_cohort_key(ce_on) != release.config_cohort_key(ce_off)


def test_config_key_changes_on_real_config_change():
    """A different commit (or a config-global fp change) MUST change the key."""
    base = _manifest(git_sha="commitAAA")
    other = _manifest(git_sha="commitBBB")
    assert release.config_cohort_key(base) != release.config_cohort_key(other)
    changed_sim = _manifest()
    changed_sim["commit_metadata"]["similarity_fp"] = "sim-DIFFERENT"
    assert release.config_cohort_key(base) != release.config_cohort_key(changed_sim)


# --- compose ----------------------------------------------------------------

def test_compose_single_run():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert r["schema"] == "release.v1"
    assert r["schema_version"] == 1
    assert "beir/scifact" in r["measured"]
    assert r["measured"]["beir/scifact"]["metrics"]["nDCG@10"] == 0.758
    assert r["measured"]["beir/scifact"]["confidence_tier"] == "A"
    assert "does_not_measure" in r["coverage"]


def test_compose_multi_corpus_same_config_U1():
    """scifact + a mixed corpus at one config compose into one release."""
    runs = [
        _summary(dataset="beir/scifact", corpus_fps=_SCIFACT_CORPUS_FPS,
                 modes={"hybrid": {"nDCG@10": 0.758}}),
        _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS,
                 modes={"hybrid": {"nDCG@10": 0.620}}, query_count=200),
    ]
    r = release.compose(runs, default_mode="hybrid", composed_at=_AT)
    assert set(r["measured"]) == {"beir/scifact", "mixed/courtlistener-200"}
    assert r["measured"]["mixed/courtlistener-200"]["metrics"]["nDCG@10"] == 0.620
    # one cohort key
    assert len({release.config_cohort_key(s["manifest"]) for s in runs}) == 1


def test_compose_refuses_non_cohort_set():
    """Runs at different commits are not one cohort → ComposeError."""
    runs = [
        _summary(dataset="beir/scifact", git_sha="commitAAA"),
        _summary(dataset="mixed/courtlistener-200", git_sha="commitBBB"),
    ]
    with pytest.raises(release.ComposeError, match="not cohort-identical"):
        release.compose(runs, default_mode="hybrid", composed_at=_AT)


def test_compose_refuses_incomparable_default():
    s = _summary()
    s["per_mode"]["hybrid"]["comparable"] = False
    with pytest.raises(release.ComposeError, match="not comparable"):
        release.compose([s], default_mode="hybrid", composed_at=_AT)


def test_compose_metric_family_pluggable():
    """The metrics map is NOT nDCG-specific — an extraction-style run composes."""
    s = _summary(modes={"hybrid": {"WER": 0.08, "CER": 0.03, "route_accuracy": 0.94}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    metrics = r["measured"]["beir/scifact"]["metrics"]
    assert metrics["WER"] == 0.08 and metrics["route_accuracy"] == 0.94
    assert "nDCG@10" not in metrics


def test_compose_records_ablations():
    """Non-default modes become labelled ablations, not the headline."""
    s = _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS,
                 query_count=200,
                 modes={"hybrid": {"nDCG@10": 0.620}, "full": {"nDCG@10": 0.925}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    assert r["measured"]["mixed/courtlistener-200"]["metrics"]["nDCG@10"] == 0.620
    abls = r["ablations"]["mixed/courtlistener-200"]
    assert any(a["config_mode"] == "full" and a["metrics"]["nDCG@10"] == 0.925 for a in abls)


def test_compose_tolerance_band_from_envelope():
    env = {"metrics": {"hybrid": {"nDCG@10": {"mean": 0.758, "stdev": 0.0011, "n": 5}}}}
    r = release.compose([_summary(envelope=env)], default_mode="hybrid", composed_at=_AT)
    band = r["measured"]["beir/scifact"]["tolerance_band"]
    assert band["nDCG@10"]["two_sigma"] == pytest.approx(0.0022)


def test_compose_tolerance_band_none_when_uncalibrated():
    r = release.compose([_summary(envelope=None)], default_mode="hybrid", composed_at=_AT)
    assert r["measured"]["beir/scifact"]["tolerance_band"] is None


def test_compose_hardware_projection():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    hw = r["cohort"]["hardware"]
    assert hw["gpu_name"] == "RTX 4070"
    assert hw["ort_version"] == "1.20.0"  # from worker health effective_config (U7)
    assert hw["cuda_version"] == "12"     # Head-side pinned constant
    assert hw["gpu_driver_version"] == "610.47"


def test_compose_empty_raises():
    with pytest.raises(release.ComposeError):
        release.compose([], default_mode="hybrid", composed_at=_AT)


def test_canonical_dataset_slug():
    # BEIR short name (the form `jseval run --dataset` + summary.dataset use) → slug.
    assert release.canonical_dataset_slug("scifact") == "beir/scifact"
    # already-slugged forms are untouched.
    assert release.canonical_dataset_slug("mixed/courtlistener-200") == "mixed/courtlistener-200"
    assert release.canonical_dataset_slug("beir/scifact") == "beir/scifact"
    assert release.canonical_dataset_slug(None) is None


def test_select_dominant_cohort():
    # P1 (tempdoc 623): a mid-sweep commit move splits the latest-per-dataset set across cohorts;
    # pick the cohort covering the most datasets, report the rest as excluded.
    chosen, excluded = release.select_dominant_cohort({
        "beir/scifact": "cohortA",
        "mixed/courtlistener-200": "cohortA",
        "mixed/miracl-de-2k": "cohortA",
        "mixed/enron-qa": "cohortB",      # landed at a different commit (the split)
    })
    assert chosen == "cohortA"
    assert excluded == ["mixed/enron-qa"]


def test_select_dominant_cohort_no_split_and_empty():
    chosen, excluded = release.select_dominant_cohort({"a": "k", "b": "k"})
    assert chosen == "k" and excluded == []
    assert release.select_dominant_cohort({}) == (None, [])


def test_compose_normalizes_short_beir_name_to_slug():
    """A real BEIR run emits summary.dataset='scifact'; the release must key it as
    'beir/scifact' so the projected ratchet floor matches `--dataset beir/scifact`."""
    s = _summary(dataset="scifact", modes={"hybrid": {"nDCG@10": 0.755}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    assert "beir/scifact" in r["measured"]
    assert "scifact" not in r["measured"]
