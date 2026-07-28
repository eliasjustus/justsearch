"""tempdoc 789 Phase 1 item 3 — the acceptance bar, wired as a test.

`experiments/replay_behavioral_789.py` replays the shipped behavioral classifiers
over the 2026-07-28 window-2 hero eval logs and asserts they reproduce the hand
census EXACTLY (name-pivot.v1.json / wrongness.v1.json / hop1-stopping.v1.json).

Those logs are a ~10 MB local campaign artifact under a sibling worktree; they are
not in the repository and CI will never have them. This test therefore SKIPS with an
explicit reason when any input root is missing, and FAILS -- never skips -- when they
are present and a number disagrees. Point it elsewhere with:

    JSEVAL_789_RUN_ROOT / JSEVAL_789_DATASETS / JSEVAL_789_CENSUS
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiments"))

import replay_behavioral_789 as replay_module  # noqa: E402

RUN_ROOT = Path(os.environ.get("JSEVAL_789_RUN_ROOT", replay_module.DEFAULT_RUN_ROOT))
DATASETS = Path(os.environ.get("JSEVAL_789_DATASETS", replay_module.DEFAULT_DATASETS))
CENSUS = Path(os.environ.get("JSEVAL_789_CENSUS", replay_module.DEFAULT_CENSUS))

_MISSING = [
    f"{label}={path}"
    for label, path in (("run_root", RUN_ROOT), ("datasets", DATASETS), ("census", CENSUS))
    if not path.exists()
]

pytestmark = pytest.mark.skipif(
    bool(_MISSING),
    reason=(
        "tempdoc 789 replay inputs absent (expected off this machine and in CI -- the "
        "window-2 hero logs and the hand census are local campaign artifacts, not "
        f"repository content): missing {', '.join(_MISSING)}"
    ),
)


@pytest.fixture(scope="module")
def result():
    return replay_module.replay(RUN_ROOT, DATASETS)


def test_replay_reproduces_the_window2_census_exactly(result):
    failures = replay_module.check(result, CENSUS)
    assert not failures, "\n".join(failures)


def test_replay_headline_numbers_are_the_charter_ones(result):
    # The charter's own acceptance numbers, spelled out so a regression names itself
    # rather than surfacing as a generic census disagreement.
    per = result["per_stratum_arm"]
    assert (per["en-email-enron-raw-1k-verbose|A"]["name_pivot"],
            per["en-email-enron-raw-1k-verbose|B"]["name_pivot"]) == (27, 16)
    assert (per["en-email-enron-raw-10k-verbose|A"]["name_pivot"],
            per["en-email-enron-raw-10k-verbose|B"]["name_pivot"]) == (23, 22)
    assert (per["en-legal-clerc-1k-verbose|A"]["name_pivot"],
            per["en-legal-clerc-1k-verbose|B"]["name_pivot"]) == (17, 20)
    assert (per["en-email-enron-raw-1k-verbose|A"]["abstained"],
            per["en-email-enron-raw-1k-verbose|B"]["abstained"]) == (11, 21)
    assert (per["en-email-enron-raw-1k-verbose|A"]["fabricated_specific"],
            per["en-email-enron-raw-1k-verbose|B"]["fabricated_specific"]) == (19, 19)
    for stratum in replay_module.STRATA:
        assert per[f"{stratum}|B"]["searched_before_grep"] == 60
