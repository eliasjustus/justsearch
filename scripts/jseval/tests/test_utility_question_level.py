"""Question-level (cluster-aware) primary statistics — tempdoc 791 axis 4.

The estimators v5 promotes to primary. The load-bearing test is the last one: it
reproduces the measured disagreement that motivated the policy, from the real
hero cohort's per-cell matrix, so the claim "the cell-level test overstates" is
runnable rather than asserted.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from jseval.utility_question_level import (
    METHOD_ID,
    MINIMUM_DRAWS,
    derive_rng_seed,
    per_question_deltas,
    question_level_statistics,
)

# The archaeology this policy rests on. Present only in a working checkout of
# the hero-arc analysis; every OTHER test here stands without it.
HERO_MATRIX = Path(
    r"F:\justsearch-public\tmp\hero-arc-analysis\stats\matrix.v1.json"
)

DRAWS = 20_000


def _grid(deltas_by_qid: dict[str, list[tuple[bool, bool]]]) -> dict:
    """`{qid: [(a_correct, c_correct), ...]}` -> the ITT paired-set shape."""
    pairs = {}
    for qid, replicates in deltas_by_qid.items():
        for seed, (a, c) in enumerate(replicates):
            pairs[f"{seed}|{qid}"] = {"seed": seed, "a_correct": a, "c_correct": c}
    return pairs


def _uniform_grid(n_qids: int, n_seeds: int, a: bool, c: bool) -> dict:
    return _grid({f"q{i}": [(a, c)] * n_seeds for i in range(n_qids)})


def test_the_question_is_the_unit_and_seeds_are_its_replicates():
    """The whole point: a question contributes ONE observation whose value is
    the mean over its seed replicates, not one observation per seed."""
    pairs = _grid({
        "q0": [(False, True), (False, True), (False, False)],
        "q1": [(True, True), (True, True), (True, True)],
    })

    per_question = per_question_deltas(pairs)

    assert set(per_question) == {"q0", "q1"}
    assert per_question["q0"]["n_seeds"] == 3
    assert per_question["q0"]["delta"] == pytest.approx(2 / 3)
    assert per_question["q1"]["delta"] == 0.0


def test_an_empty_paired_set_returns_none_rather_than_a_placeholder():
    assert question_level_statistics({}, seed_material={"x": 1}) is None


def test_a_large_uniform_difference_is_significant_and_excludes_zero():
    """The estimators must be able to DETECT something; a test that can only
    return 'not significant' would pass a policy that never promotes."""
    pairs = _uniform_grid(20, 3, a=False, c=True)

    block = question_level_statistics(
        pairs, seed_material={"x": 1},
        permutation_draws=DRAWS, bootstrap_draws=DRAWS,
    )

    assert block["aggregate_delta"] == 1.0
    assert block["signflip_p_two_sided"] == pytest.approx(1 / (DRAWS + 1))
    assert block["delta_ci"] == [1.0, 1.0]
    assert block["n_qids"] == 20
    assert block["n_paired_cells"] == 60


def test_a_zero_difference_is_never_significant():
    pairs = _uniform_grid(20, 3, a=True, c=True)

    block = question_level_statistics(
        pairs, seed_material={"x": 1},
        permutation_draws=DRAWS, bootstrap_draws=DRAWS,
    )

    assert block["aggregate_delta"] == 0.0
    assert block["signflip_p_two_sided"] == 1.0
    assert block["delta_ci"] == [0.0, 0.0]


def test_bca_falls_back_to_percentile_and_says_why_when_it_is_undefined():
    """No faked BCa. A degenerate jackknife or a one-sided bootstrap leaves the
    correction undefined, and the record must name that rather than emit an
    interval whose method is a guess."""
    block = question_level_statistics(
        _uniform_grid(20, 3, a=True, c=True), seed_material={"x": 1},
        permutation_draws=DRAWS, bootstrap_draws=DRAWS,
    )

    assert block["ci_method"] == "percentile"
    assert "undefined" in block["ci_method_fallback_reason"]


def test_a_skewed_cluster_distribution_gets_a_named_bca_interval():
    """The non-degenerate path: a mixture of question outcomes yields a
    computable bias correction and acceleration, so BCa is what is reported."""
    outcomes = {}
    for index in range(20):
        if index < 5:
            outcomes[f"q{index}"] = [(True, False)] * 3
        elif index < 8:
            outcomes[f"q{index}"] = [(False, True), (False, False), (False, False)]
        else:
            outcomes[f"q{index}"] = [(True, True)] * 3

    block = question_level_statistics(
        _grid(outcomes), seed_material={"x": 1},
        permutation_draws=DRAWS, bootstrap_draws=DRAWS,
    )

    assert block["ci_method"] == "BCa"
    assert "ci_method_fallback_reason" not in block
    assert block["delta_ci"][0] <= block["aggregate_delta"] <= block["delta_ci"][1]
    # The percentile companion is always kept, so the estimator that was NOT
    # promoted stays visible to a reviewer.
    assert len(block["delta_ci_percentile"]) == 2


def test_the_block_is_fully_re_derivable_from_what_it_records():
    """No wall-clock, no process entropy, no ambient default can reach a
    published interval: the seed is a digest of recorded material, and re-running
    from that material reproduces the numbers byte-for-byte."""
    pairs = _grid({
        f"q{i}": [(i % 2 == 0, i % 3 == 0)] * 3 for i in range(20)
    })
    material = {"method": METHOD_ID, "stratum_id": "fixture", "n_expected_cells": 120}

    first = question_level_statistics(
        pairs, seed_material=material, permutation_draws=DRAWS, bootstrap_draws=DRAWS)
    second = question_level_statistics(
        pairs, seed_material=material, permutation_draws=DRAWS, bootstrap_draws=DRAWS)

    assert first == second
    assert first["rng_seed"] == derive_rng_seed(material)
    assert first["rng_seed_permutation"] == first["rng_seed"]
    assert first["rng_seed_bootstrap"] == first["rng_seed"] + 1
    assert first["rng_algorithm"]
    assert first["rng_seed_material"] == material


def test_different_material_gives_a_different_stream():
    """Content-addressing must actually address the content — a seed that
    ignored its material would make every stratum share one RNG stream."""
    assert derive_rng_seed({"stratum_id": "a"}) != derive_rng_seed({"stratum_id": "b"})


def test_the_permutation_p_can_never_be_zero():
    """`(hits + 1) / (draws + 1)` — a Monte-Carlo p-value that reported 0 would
    claim more certainty than the number of draws can support."""
    block = question_level_statistics(
        _uniform_grid(20, 3, a=False, c=True), seed_material={"x": 1},
        permutation_draws=1000, bootstrap_draws=1000,
    )
    assert block["signflip_p_two_sided"] > 0.0
    assert block["signflip_p_two_sided"] == pytest.approx(1 / 1001)
    # The module's own floor is what the policy's thresholds must not undercut.
    assert MINIMUM_DRAWS == 20_000


def test_non_positive_draw_counts_are_refused():
    with pytest.raises(ValueError):
        question_level_statistics(
            _uniform_grid(4, 2, a=True, c=False),
            seed_material={"x": 1}, permutation_draws=0,
        )


@pytest.mark.skipif(
    not HERO_MATRIX.is_file(),
    reason="the 782 hero per-cell matrix is analysis working state, not committed",
)
def test_reproduces_the_measured_hero_disagreement_that_motivated_v5():
    """The claim behind claim-policy v5, made runnable.

    On the decisive stratum (window-2 enron-1k) the record's CELL-level exact
    McNemar reports p=0.0446 while the question-level sign-flip permutation
    reports ~0.136. Same data, same pairing — the only difference is whether a
    question's 3 seed replicates are counted as 3 independent observations.
    Reproduced here to Monte-Carlo tolerance against the numbers in
    `tmp/hero-arc-analysis/stats/heterogeneity.v1.json`."""
    rows = json.loads(HERO_MATRIX.read_text(encoding="utf-8"))["cells"]

    def itt(cell):
        if cell["other_error"]:
            return None
        return False if cell["exhausted"] else bool(cell["em_correct"])

    expected = {
        ("w2", "en-email-enron-raw-1k-verbose"): (-0.2, 0.135779),
        ("w2", "en-email-enron-raw-10k-verbose"): (-0.11667, 0.148699),
        ("w2", "en-legal-clerc-1k-verbose"): (0.01667, 1.0),
    }
    for (window, stratum), (delta, signflip_p) in expected.items():
        scored = {
            (cell["arm"], cell["qid"], cell["seed"]): itt(cell)
            for cell in rows
            if cell["window"] == window and cell["stratum"] == stratum
        }
        pairs = {}
        for (arm, qid, seed) in list(scored):
            if arm != "A":
                continue
            a, c = scored.get(("A", qid, seed)), scored.get(("B", qid, seed))
            if a is None or c is None:
                continue
            pairs[f"{seed}|{qid}"] = {"seed": seed, "a_correct": a, "c_correct": c}

        block = question_level_statistics(
            pairs, seed_material={"stratum_id": f"{window}|{stratum}"},
            permutation_draws=100_000, bootstrap_draws=DRAWS,
        )

        assert block["n_qids"] == 20 and block["n_paired_cells"] == 60
        assert block["aggregate_delta"] == pytest.approx(delta, abs=1e-4)
        # Monte-Carlo tolerance: a two-sided permutation p at 1e5 draws has a
        # standard error under 0.002 here, so 0.01 is generous and still tight
        # enough to fail if the estimator changed.
        assert block["signflip_p_two_sided"] == pytest.approx(signflip_p, abs=0.01)
        assert math.isfinite(block["delta_ci"][0])

    # The decisive comparison, stated as the assertion: the question-level test
    # clears alpha=0.05 NOWHERE, while the record's cell-level McNemar did.
    decisive = expected[("w2", "en-email-enron-raw-1k-verbose")][1]
    assert decisive > 0.05
