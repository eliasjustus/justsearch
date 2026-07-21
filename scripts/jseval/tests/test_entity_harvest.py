"""Tests for the offline entity harvester + committed entity-bank artifact (tempdoc 767).

Pure-function / fixture tests — NO host corpus, NO dev stack. Table-driven in the style
of ``test_corpus_governance.py``; the cross-process determinism test mirrors
``test_corpus_governance.py::test_generate_is_deterministic_across_processes``.
"""

from __future__ import annotations

import json
import random
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

# tempdoc 767 split the module in two: `entity_bank` is the BUILD-PATH half (schema
# constants, quality filters, load/validate/sample/mint) and `entity_harvest` is the
# offline harvester. The build path imports only the former, so these tests exercise
# each surface through its own module rather than through the harvester's re-exports.
from jseval import entity_bank as eb
from jseval import entity_harvest as eh


# ---------------------------------------------------------------------------
# Sample text — hand-written, shaped like the real hosts (CLERC opinions, Enron mail)
# ---------------------------------------------------------------------------

LEGAL_SAMPLE = (
    "Before PLAGER, CLEVENGER, and BRYSON, Circuit Judges. "
    "GORDON J. QUIST, District Judge. "
    "The motion is governed by Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986), "
    "and by Celotex Corp. v. Catrett, 106 S.Ct. 2548. "
    "Application Ser. No. 07/673,967 was filed on March 28, 1996 in New York. "
    "The United States Patent and Trademark Office issued No. 03-30625. "
    "Notice went to accounts@plansvc.com covering 1,250,000 shares."
)

EMAIL_SAMPLE = (
    "Subject: APX Comments on Letter to the Governor.\n"
    "Sender: dhunter@smithandkempton.com\n"
    "From: Edward Cazalet\n"
    "To: Ginger Dernehl\n"
    "Sent: 08/25/2000 in San Francisco.\n"
    "Please forward to jeff.dasovich@enron.com before August 25, 2000.\n"
    "Enron Energy Services holds 1,500 contracts."
)


def _surfaces(text: str, *, domain: str, entity_type: str) -> list[str]:
    return sorted({s for t, s in eh.extract(text, domain=domain) if t == entity_type})


# ---------------------------------------------------------------------------
# Per-type extraction
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    ("domain", "sample", "entity_type", "expected"),
    [
        ("legal", LEGAL_SAMPLE, "PER", "PLAGER"),
        ("legal", LEGAL_SAMPLE, "PER", "GORDON J. QUIST"),
        ("legal", LEGAL_SAMPLE, "CASE", "Anderson v. Liberty Lobby, Inc."),
        ("legal", LEGAL_SAMPLE, "CITATION", "477 U.S. 242, 248 (1986)"),
        ("legal", LEGAL_SAMPLE, "CITATION", "106 S.Ct. 2548"),
        ("legal", LEGAL_SAMPLE, "DOCKET", "Ser. No. 07/673,967"),
        ("legal", LEGAL_SAMPLE, "DOCKET", "No. 03-30625"),
        ("legal", LEGAL_SAMPLE, "DATE", "March 28, 1996"),
        ("legal", LEGAL_SAMPLE, "LOC", "New York"),
        ("legal", LEGAL_SAMPLE, "ORG", "United States Patent and Trademark Office"),
        ("legal", LEGAL_SAMPLE, "EMAIL", "accounts@plansvc.com"),
        ("legal", LEGAL_SAMPLE, "CARDINAL", "1,250,000"),
        ("email", EMAIL_SAMPLE, "PER", "Edward Cazalet"),
        ("email", EMAIL_SAMPLE, "PER", "Ginger Dernehl"),
        ("email", EMAIL_SAMPLE, "PER", "Jeff Dasovich"),
        ("email", EMAIL_SAMPLE, "EMAIL", "dhunter@smithandkempton.com"),
        ("email", EMAIL_SAMPLE, "LOC", "San Francisco"),
        ("email", EMAIL_SAMPLE, "DATE", "08/25/2000"),
        ("email", EMAIL_SAMPLE, "DATE", "August 25, 2000"),
        ("email", EMAIL_SAMPLE, "ORG", "Enron Energy Services"),
        ("email", EMAIL_SAMPLE, "CARDINAL", "1,500"),
    ],
)
def test_extracts_expected_entity(domain, sample, entity_type, expected):
    assert expected in _surfaces(sample, domain=domain, entity_type=entity_type)


def test_case_caption_wins_the_overlap_over_its_party_names():
    """Priority resolution: a CASE span must not also be emitted as two PER spans."""
    spans = eh.extract("Anderson v. Liberty Lobby, Inc. held otherwise.", domain="legal")
    assert ("CASE", "Anderson v. Liberty Lobby, Inc.") in spans
    assert not [s for t, s in spans if t == "PER" and s in ("Anderson", "Liberty Lobby")]


def test_citation_pincite_does_not_swallow_the_next_reporter_volume():
    """"477 U.S. 317, 323, 106 S.Ct. 2548" must stop at 323 — not eat "106" or "10"."""
    cites = _surfaces("See 477 U.S. 317, 323, 106 S.Ct. 2548 (1986).",
                      domain="legal", entity_type="CITATION")
    assert "477 U.S. 317, 323" in cites
    assert not [c for c in cites if c.endswith(", 10") or c.endswith(", 7")]


def test_unknown_domain_is_rejected():
    with pytest.raises(ValueError):
        eh.extract("text", domain="medical")


# ---------------------------------------------------------------------------
# Quality filters
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    ("surface", "entity_type", "ok", "why"),
    [
        ("A", "PER", False, "shorter than min_length"),
        ("Jo", "PER", False, "only 2 alphabetic chars"),
        ("Ann", "PER", True, "exactly min_alpha_chars"),
        ("U. S.", "PER", False, "2 alphabetic chars — the junk case the filter targets"),
        (".", "ORG", False, "no alphabetic chars"),
        ("x" * 101, "ORG", False, "longer than max_length"),
        ("x" * 100, "ORG", True, "exactly max_length"),
        # Structural types are NOT alpha-filtered: the anchored regex is their filter.
        ("477 U.S. 242, 248 (1986)", "CITATION", True, "2 alpha chars but a valid citation"),
        ("1,250,000", "CARDINAL", True, "0 alpha chars but a valid cardinal"),
        ("08/25/2000", "DATE", True, "0 alpha chars but a valid date"),
        ("No. 03-30625", "DOCKET", True, "2 alpha chars but a valid docket"),
    ],
)
def test_quality_filter(surface, entity_type, ok, why):
    assert eb.passes_quality(surface, entity_type) is ok, why


def test_alpha_filter_scope_is_exactly_the_alphabetic_types():
    """Regression guard: a global min-alpha filter would silently empty 5 of 9 types."""
    assert set(eb.ALPHA_FILTERED_TYPES) == {"CASE", "LOC", "ORG", "PER"}
    assert set(eb.ALPHA_FILTERED_TYPES) <= set(eb.ENTITY_TYPES)


@pytest.mark.parametrize(
    ("text", "rejected"),
    [
        ("The Cong. Rec reports it.", "Cong. Rec"),
        ("Under the Supreme Court’s view", "Supreme Court’s"),
        ("Before the Circuit Judge sat", "Circuit Judge"),
        ("Appellants Morris argued", "Appellants Morris"),
    ],
)
def test_junk_capitalized_runs_are_not_persons(text, rejected):
    assert rejected not in _surfaces(text, domain="legal", entity_type="PER")


def test_trailing_sentence_period_is_trimmed_but_abbreviations_survive():
    assert "United States" in _surfaces("It occurred in the United States.",
                                        domain="legal", entity_type="LOC")
    assert "Celotex Corp. v. Catrett" in _surfaces("See Celotex Corp. v. Catrett here.",
                                                   domain="legal", entity_type="CASE")


# ---------------------------------------------------------------------------
# Bank construction + document frequency
# ---------------------------------------------------------------------------

def _sample_docs() -> list[tuple[str, str]]:
    return [
        ("d1", "From: Edward Cazalet\nTo: Ginger Dernehl\nSent 08/25/2000."),
        ("d2", "From: Edward Cazalet\nTo: Susan Mara\nSent 08/26/2000."),
    ]


def test_document_frequency_counts_documents_not_occurrences():
    entities, counts, n_docs = eh.harvest_documents(_sample_docs(), domain="email")
    assert n_docs == 2
    by_surface = {r["s"]: r for r in entities["PER"]}
    assert by_surface["Edward Cazalet"]["df"] == 2
    assert by_surface["Ginger Dernehl"]["df"] == 1
    assert counts["PER"]["unique"] == len(entities["PER"])


def test_bank_entities_are_sorted_and_length_annotated():
    bank = eh.build_bank(_sample_docs(), domain="email", host_corpus="fixture")
    for by_length in bank["exemplars"].values():
        for length, surfaces in by_length.items():
            assert surfaces == sorted(surfaces)
            assert all(len(s) == int(length) for s in surfaces)


def test_empty_types_are_omitted_not_emitted_as_empty_lists():
    bank = eh.build_bank(_sample_docs(), domain="email", host_corpus="fixture")
    assert "CASE" not in bank["exemplars"]
    assert set(bank["exemplars"]) <= set(eb.MINTABLE_TYPES)


# ---------------------------------------------------------------------------
# Length-band matching helper (the BUILD-PATH surface)
# ---------------------------------------------------------------------------

def test_length_band_ratios():
    assert eb.length_band(10) == (3.0, 30.0)
    assert eb.in_length_band(3, 10) and eb.in_length_band(30, 10)
    assert not eb.in_length_band(2, 10)
    assert not eb.in_length_band(31, 10)


def _bank_with(lengths: list[int]) -> dict:
    """A minimal v2 bank whose PER pool holds exactly one surface at each given length."""
    return {
        "exemplars": {"PER": {str(n): ["x" * n] for n in lengths}},
        "length_weights": {"PER": {str(n): 1 for n in lengths}},
    }


def test_sample_matched_accepts_an_in_band_entity():
    bank = _bank_with([12])
    got = eb.sample_matched(bank, type="PER", target_length=10, rng=random.Random(0))
    assert got is not None and got["len"] == 12


def test_sample_matched_rejects_after_max_resamples_when_all_out_of_band():
    bank = _bank_with([200, 201, 202])
    assert eb.sample_matched(bank, type="PER", target_length=10, rng=random.Random(0)) is None


def test_sample_matched_draws_exactly_max_resamples_before_rejecting():
    """Guard the "up to 5 resamples then reject" contract, not just the None result."""
    draws: list[int] = []

    class _CountingRng:
        def randrange(self, n: int) -> int:
            draws.append(n)
            return 0

    assert eb.sample_matched(_bank_with([200]), type="PER", target_length=10,
                             rng=_CountingRng()) is None
    # Two draws per attempt in the v2 format: a length from the real length distribution,
    # then a surface uniformly among the exemplars kept at that length.
    assert len(draws) == 2 * eb.MAX_RESAMPLES == 10


def test_sample_matched_is_reproducible_for_a_given_seed():
    bank = _bank_with([8, 9, 10, 11, 12, 13])
    a = [eb.sample_matched(bank, type="PER", target_length=10, rng=random.Random(1234))
         for _ in range(20)]
    b = [eb.sample_matched(bank, type="PER", target_length=10, rng=random.Random(1234))
         for _ in range(20)]
    assert a == b


@pytest.mark.parametrize("missing", ["ORG", "CASE"])
def test_sample_matched_returns_none_for_an_absent_type(missing):
    assert eb.sample_matched(_bank_with([10]), type=missing, target_length=10,
                             rng=random.Random(0)) is None


def test_sample_matched_returns_none_for_a_nonpositive_target():
    assert eb.sample_matched(_bank_with([10]), type="PER", target_length=0,
                             rng=random.Random(0)) is None


# ---------------------------------------------------------------------------
# Bank commitment + validation (mirrors corpus_certify._validate_commitment)
# ---------------------------------------------------------------------------

def _write_good_bank(root: Path) -> dict:
    bank = eh.build_bank(
        _sample_docs(), domain="email", host_corpus="fixture-enron",
        source_revision="ef042f8ab436f78704f17faa0a866d1b2b862f6f",
        raw_source_signature="a23d916b1f06e039dcdd5c2679d62f7f6e34446a7476ec94bed758a6aa8f3c35",
        corpus_sig="0" * 64,
    )
    eh.write_bank(bank, root)
    return bank


def test_validate_passes_on_a_freshly_written_bank(tmp_path):
    _write_good_bank(tmp_path)
    result = eb.validate_entity_bank(tmp_path)
    assert result["passed"] is True, result.get("reason")
    assert len(result["manifest_sha256"]) == 64
    assert len(result["bank_sha256"]) == 64


def test_validate_ties_the_manifest_digest_to_the_actual_bank_bytes(tmp_path):
    _write_good_bank(tmp_path)
    manifest = json.loads((tmp_path / eb.COMMITMENT_FILENAME).read_text(encoding="utf-8"))
    assert manifest["files"][eb.BANK_FILENAME] == eb.validate_entity_bank(tmp_path)["bank_sha256"]


@pytest.mark.parametrize(
    ("tamper", "reason_fragment"),
    [
        (lambda root: (root / eb.COMMITMENT_FILENAME).unlink(), "missing commitment"),
        (
            lambda root: (root / eb.COMMITMENT_FILENAME).write_bytes(
                json.dumps({"schema": "wrong.v9", "files": {}}).encode("utf-8")),
            "unsupported commitment schema",
        ),
        (
            lambda root: (root / eb.COMMITMENT_FILENAME).write_bytes(
                json.dumps({"schema": eb.COMMITMENT_SCHEMA, "files": {}}).encode("utf-8")),
            "file matrix is incomplete",
        ),
        (
            lambda root: (root / eb.COMMITMENT_FILENAME).write_bytes(
                json.dumps({"schema": eb.COMMITMENT_SCHEMA,
                            "files": {eb.BANK_FILENAME: "0" * 64}}).encode("utf-8")),
            "digest mismatch",
        ),
    ],
)
def test_validate_fails_on_a_tampered_commitment(tmp_path, tamper, reason_fragment):
    _write_good_bank(tmp_path)
    tamper(tmp_path)
    result = eb.validate_entity_bank(tmp_path)
    assert result["passed"] is False
    assert reason_fragment in result["reason"]


def test_validate_fails_when_the_bank_bytes_are_edited_after_commitment(tmp_path):
    """The skeptic's check: change one entity surface, the sha no longer matches."""
    _write_good_bank(tmp_path)
    bank = eb.load_bank(tmp_path)
    bank["exemplars"]["PER"]["10"] = ["Tampered"]
    (tmp_path / eb.BANK_FILENAME).write_bytes(eb.canonical_bytes(bank))
    result = eb.validate_entity_bank(tmp_path)
    assert result["passed"] is False
    assert "digest mismatch" in result["reason"]


@pytest.mark.parametrize(
    ("mutate", "reason_fragment"),
    [
        (lambda b: b.__setitem__("schema", "nope.v1"), "unsupported bank schema"),
        (lambda b: b.__setitem__("harvester_version", ""), "missing harvester_version"),
        (lambda b: b["host"].pop("raw_source_signature"), "incomplete host provenance"),
        (lambda b: b["host"].__setitem__("domain", "medical"), "unknown host domain"),
        (lambda b: b.__setitem__("exemplars", {}), "bank contains no exemplars"),
        (lambda b: (b["exemplars"].__setitem__("BOGUS", {"4": ["abcd"]}),
                    b["length_weights"].__setitem__("BOGUS", {"4": 1})), "unknown entity type"),
        (lambda b: b["exemplars"]["PER"].__setitem__("4", ["Abcd"]),
         "exemplar lengths disagree"),
        (lambda b: b["exemplars"]["PER"].__setitem__("10", ["Susan Marathon"]),
         "length disagrees with surface"),
        (lambda b: b["exemplars"]["PER"]["14"].reverse(), "not sorted"),
        (lambda b: b["exemplars"]["PER"]["14"].pop(), "exemplar counts disagree with the cap"),
        (lambda b: b["length_weights"]["PER"].__setitem__("14", 99),
         "length_weights disagree with harvested counts"),
        (lambda b: b["parameters"].__setitem__("exemplars_per_length", 7),
         "exemplar cap disagrees"),
        (lambda b: b["collision_index"].__setitem__("count", 999),
         "count disagrees with its chunks"),
        (lambda b: b["collision_index"].__setitem__("algorithm", "md5-lol"),
         "unsupported collision-index algorithm"),
        (lambda b: b["collision_index"]["chunks"].__setitem__(
            0, b["collision_index"]["chunks"][0][6:] + b["collision_index"]["chunks"][0][:6]),
         "collision index is not sorted"),
    ],
)
def test_validate_fails_on_a_malformed_bank(tmp_path, mutate, reason_fragment):
    _write_good_bank(tmp_path)
    bank = eb.load_bank(tmp_path)
    mutate(bank)
    # Re-commit so the digest matches: this isolates the BANK checks from the sha check.
    eh.write_bank(bank, tmp_path)
    result = eb.validate_entity_bank(tmp_path)
    assert result["passed"] is False
    assert reason_fragment in result["reason"]


# ---------------------------------------------------------------------------
# Determinism — the load-bearing property of a committed artifact
# ---------------------------------------------------------------------------

def test_harvest_is_deterministic_in_process():
    a = eh.build_bank(_sample_docs(), domain="email", host_corpus="fixture")
    b = eh.build_bank(_sample_docs(), domain="email", host_corpus="fixture")
    assert eb.canonical_bytes(a) == eb.canonical_bytes(b)


def test_canonical_bytes_use_lf_newlines_and_utf8(tmp_path):
    """Path.write_text would emit CRLF on Windows and break byte-reproducibility."""
    _write_good_bank(tmp_path)
    raw = (tmp_path / eb.BANK_FILENAME).read_bytes()
    assert b"\r\n" not in raw
    assert raw.endswith(b"\n")
    raw.decode("utf-8")


def test_harvest_is_deterministic_across_processes(tmp_path):
    """Mirrors test_corpus_governance.py::test_generate_is_deterministic_across_processes.

    This project never pins PYTHONHASHSEED, so `set` iteration order and `hash()` are
    per-process-randomized (PEP 456) — invisible to an in-process test because both are
    stable *within* one process. Two separate interpreters must produce byte-identical
    bank bytes, or the committed artifact is not reproducible by a skeptic.
    """
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "a.txt").write_bytes(LEGAL_SAMPLE.encode("utf-8"))
    (docs_dir / "b.txt").write_bytes(EMAIL_SAMPLE.encode("utf-8"))

    script = textwrap.dedent(
        """
        import sys
        from jseval import entity_bank as eb
        from jseval import entity_harvest as eh
        bank = eh.build_bank(
            eh.iter_text_files(sys.argv[1]), domain="legal", host_corpus="probe")
        sys.stdout.buffer.write(eb.canonical_bytes(bank))
        """
    )
    outputs = []
    for _ in range(2):
        proc = subprocess.run(
            [sys.executable, "-c", script, str(docs_dir)],
            capture_output=True, check=True,
            env={**_child_env(), "PYTHONUTF8": "1"},
        )
        outputs.append(proc.stdout)
    assert outputs[0] == outputs[1], "bank bytes differ between two same-input processes"
    assert b'"PER"' in outputs[0], "probe harvested nothing — the test would be vacuous"


# ---------------------------------------------------------------------------
# The committed fixture bank (tempdoc 767) — the build-path input every
# `corpus_generate.generate()` test call site mints against
# ---------------------------------------------------------------------------

FIXTURE_BANK = Path(__file__).resolve().parent / "fixtures" / "entity-bank-fixture"


def test_committed_fixture_bank_validates():
    result = eb.validate_entity_bank(FIXTURE_BANK)
    assert result["passed"] is True, result
    bank = eb.load_bank(FIXTURE_BANK)
    assert set(bank["exemplars"]) >= set(eb.MINTABLE_TYPES), \
        "the minter needs PER/ORG/LOC pools"


def test_committed_fixture_bank_reproduces_from_its_host_docs():
    """The fixture bank is a *derived* artifact: re-harvesting its committed host documents
    must reproduce its committed bytes exactly. Without this, the bank could silently drift
    from the harvester and the sha pinned in every corpus's provenance would mean nothing."""
    rebuilt = eh.build_bank(
        eh.iter_text_files(FIXTURE_BANK / "host-docs"),
        domain="legal", host_corpus="entity-bank-fixture",
        source_revision="fixture", raw_source_signature="fixture",
    )
    committed = (FIXTURE_BANK / eb.BANK_FILENAME).read_bytes()
    assert eb.canonical_bytes(rebuilt) == committed


# ---------------------------------------------------------------------------
# The trimmed committed format (tempdoc 767 §O)
# ---------------------------------------------------------------------------

def _wide_docs(n: int = 200) -> list[tuple[str, str]]:
    """Enough distinct PER surfaces of one length to exceed the exemplar cap."""
    names = [f"{a}{b}rown Smith" for a in "ABCDEFGHJKLMNPQRSTUVWXYZ" for b in "aeiou"][:n]
    return [(f"d{i}", f"From: {name}\nTo: {name}\n") for i, name in enumerate(names)]


def test_exemplars_are_capped_per_length_but_length_weights_are_not():
    """The trim's central claim: surfaces are capped, the length distribution is whole."""
    bank = eh.build_bank(_wide_docs(), domain="email", host_corpus="fixture")
    weights = bank["length_weights"]["PER"]
    exemplars = bank["exemplars"]["PER"]
    assert sum(weights.values()) == bank["counts"]["PER"]["unique"] > eb.EXEMPLARS_PER_LENGTH
    for length, count in weights.items():
        assert len(exemplars[length]) == min(eb.EXEMPLARS_PER_LENGTH, count)
    assert any(count > eb.EXEMPLARS_PER_LENGTH for count in weights.values()), \
        "fixture must actually exercise the cap or the test is vacuous"


def test_capped_exemplars_are_spread_not_a_head_slice():
    """A head slice would keep only 'Aa…'-initial names; the stride keeps the alphabet."""
    bank = eh.build_bank(_wide_docs(), domain="email", host_corpus="fixture")
    length, surfaces = max(bank["exemplars"]["PER"].items(), key=lambda kv: len(kv[1]))
    assert len(surfaces) == eb.EXEMPLARS_PER_LENGTH
    initials = {s[0] for s in surfaces}
    assert len(initials) > eb.EXEMPLARS_PER_LENGTH // 4, initials


def test_length_draws_reproduce_the_real_length_distribution():
    """Capping exemplars must not shift length statistics — the draw uses the weights."""
    bank = eh.build_bank(_wide_docs(), domain="email", host_corpus="fixture")
    weights = {int(k): v for k, v in bank["length_weights"]["PER"].items()}
    total = sum(weights.values())
    rng = random.Random(4242)
    drawn: dict[int, int] = {}
    for _ in range(20000):
        length = eb.draw_length(bank, "PER", rng)
        drawn[length] = drawn.get(length, 0) + 1
    for length, weight in weights.items():
        assert abs(drawn.get(length, 0) / 20000 - weight / total) < 0.02


def test_collision_exclusion_is_exact_against_a_real_surface():
    """The load-bearing property: a candidate equal to a REAL bank entity is rejected.

    Constructed as the brief's skeptic would: take a surface that is actually in the host
    corpus, hand the minter's acceptance test exactly that string, and assert refusal —
    for every type, including the ones the trimmed bank keeps no surfaces for.
    """
    docs = [("d1", LEGAL_SAMPLE), ("d2", EMAIL_SAMPLE)]
    full = eh.build_full_harvest(docs, domain="legal", host_corpus="fixture")
    bank = eh.trim_bank(full)
    minter = eb.Minter(bank)

    checked = 0
    for entity_type, rows in full["entities"].items():
        for row in rows:
            assert not minter._acceptable(row["s"], entity_type, len(row["s"])), \
                f"minted {entity_type} {row['s']!r} collides with a real surface"
            checked += 1
    assert checked > 20, "sample too small to be meaningful"


def test_collision_exclusion_survives_normalization_variants():
    """Case and punctuation variants normalize onto a real surface and must also be refused."""
    docs = [("d1", LEGAL_SAMPLE), ("d2", EMAIL_SAMPLE)]
    full = eh.build_full_harvest(docs, domain="legal", host_corpus="fixture")
    minter = eb.Minter(eh.trim_bank(full))
    real = full["entities"]["PER"][0]["s"]
    for variant in (real.upper(), real.lower(), f"  {real}  ", real.replace(" ", "  ")):
        assert not minter._acceptable(variant, "PER", len(real)), variant


def test_collision_index_covers_every_harvested_surface():
    """Trimming drops surfaces from `exemplars`; it must drop none from the index."""
    docs = [("d1", LEGAL_SAMPLE), ("d2", EMAIL_SAMPLE)]
    full = eh.build_full_harvest(docs, domain="legal", host_corpus="fixture")
    bank = eh.trim_bank(full)
    present = set(eb.collision_digests(bank["collision_index"]))
    keys = {eb.normalize_surface(r["s"]) for rows in full["entities"].values() for r in rows}
    assert {eb.collision_digest(k) for k in keys} <= present
    assert bank["collision_index"]["count"] == len({eb.collision_digest(k) for k in keys})


def test_minted_entities_are_absent_from_the_full_harvest():
    """End to end: nothing the minter emits appears in the untrimmed real surface set."""
    docs = [("d1", LEGAL_SAMPLE), ("d2", EMAIL_SAMPLE)]
    full = eh.build_full_harvest(docs, domain="legal", host_corpus="fixture")
    minter = eb.Minter(eh.trim_bank(full))
    rng = random.Random(7)
    real = {eb.normalize_surface(r["s"]) for rows in full["entities"].values() for r in rows}
    minted = [minter.mint_entity(rng) for _ in range(60)]
    assert len({eb.normalize_surface(m) for m in minted}) == 60
    assert not {eb.normalize_surface(m) for m in minted} & real
    assert not {eb.normalize_surface(minter.mint_value(rng)) for _ in range(40)} & real


def test_full_harvest_is_not_loadable_as_a_committed_bank(tmp_path):
    """An analysis dump must never be mistaken for a generation input."""
    full = eh.build_full_harvest(_sample_docs(), domain="email", host_corpus="fixture")
    eh.write_full_harvest(full, tmp_path / eb.FULL_HARVEST_FILENAME)
    eh.write_bank(full, tmp_path)  # commit the FULL object under the bank filename
    result = eb.validate_entity_bank(tmp_path)
    assert result["passed"] is False
    assert "unsupported bank schema" in result["reason"]


def test_trim_is_deterministic_across_processes(tmp_path):
    """The trim adds sorting, hashing and striding — all must be interpreter-independent."""
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    for i, (_, text) in enumerate(_wide_docs(40)):
        (docs_dir / f"d{i:03d}.txt").write_bytes(text.encode("utf-8"))

    script = textwrap.dedent(
        """
        import sys
        from jseval import entity_bank as eb
        from jseval import entity_harvest as eh
        bank = eh.build_bank(
            eh.iter_text_files(sys.argv[1]), domain="email", host_corpus="probe")
        sys.stdout.buffer.write(eb.canonical_bytes(bank))
        """
    )
    outputs = []
    for _ in range(2):
        proc = subprocess.run(
            [sys.executable, "-c", script, str(docs_dir)],
            capture_output=True, check=True,
            env={**_child_env(), "PYTHONUTF8": "1"},
        )
        outputs.append(proc.stdout)
    assert outputs[0] == outputs[1], "trimmed bank bytes differ between two same-input processes"
    assert b'"collision_index"' in outputs[0] and b'"exemplars"' in outputs[0]


def _child_env() -> dict:
    import os
    env = dict(os.environ)
    # Ensure the child resolves the same `jseval` package this test imported, and do NOT
    # pin PYTHONHASHSEED — the whole point is that hash randomization must not matter.
    root = str(Path(eh.__file__).resolve().parents[1])
    env["PYTHONPATH"] = root + os.pathsep + env.get("PYTHONPATH", "")
    env.pop("PYTHONHASHSEED", None)
    return env
