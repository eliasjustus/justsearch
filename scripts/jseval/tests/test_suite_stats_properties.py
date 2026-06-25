"""Value-law floor for the jseval stats aggregation (tempdoc 554; hypothesis).

Law class: bounds / monotonicity / permutation-invariance. Oracle class: free — the invariants are
checkable without re-deriving the statistics. These are algebraic properties of the aggregation, NOT
the statistical quality of any metric (which is not an invariant and stays in the eval harness).
"""

from __future__ import annotations

from hypothesis import given
from hypothesis import strategies as st

from jseval.suite_stats import compute_stats, percentile

_finite = st.floats(min_value=-1e6, max_value=1e6, allow_nan=False, allow_infinity=False)


@given(st.lists(_finite, min_size=1, max_size=50), st.floats(min_value=0, max_value=100))
def test_percentile_within_bounds(values: list[float], p: float) -> None:
    sv = sorted(values)
    r = percentile(sv, p)
    assert sv[0] <= r <= sv[-1]


@given(
    st.lists(_finite, min_size=1, max_size=50),
    st.floats(min_value=0, max_value=100),
    st.floats(min_value=0, max_value=100),
)
def test_percentile_monotonic_in_p(values: list[float], p1: float, p2: float) -> None:
    sv = sorted(values)
    lo, hi = (p1, p2) if p1 <= p2 else (p2, p1)
    assert percentile(sv, lo) <= percentile(sv, hi)


@given(st.lists(_finite, min_size=1, max_size=50))
def test_percentile_endpoints_are_min_and_max(values: list[float]) -> None:
    sv = sorted(values)
    assert percentile(sv, 0.0) == sv[0]
    assert percentile(sv, 100.0) == sv[-1]


@given(st.lists(_finite, min_size=1, max_size=50))
def test_compute_stats_orders_and_brackets(values: list[float]) -> None:
    s = compute_stats(values)
    # min <= median <= max (rounding is monotone, so ordering is preserved).
    assert s["min"] <= s["median"] <= s["max"]
    if s["ci_lower_95"] is not None:
        assert s["ci_lower_95"] <= s["ci_upper_95"]
        assert s["stddev"] >= 0


@given(st.lists(_finite, min_size=1, max_size=50), st.permutations(list(range(50))))
def test_compute_stats_permutation_invariant(values: list[float], perm: list[int]) -> None:
    shuffled = [values[i] for i in perm if i < len(values)]
    assert compute_stats(values) == compute_stats(shuffled)


def test_compute_stats_empty_is_safe() -> None:
    s = compute_stats([])
    assert s["median"] is None
    assert s["min"] is None
    assert s["ci_lower_95"] is None


def test_compute_stats_single_value() -> None:
    s = compute_stats([4.0])
    assert s["min"] == s["median"] == s["max"] == 4.0
    assert s["stddev"] is None
