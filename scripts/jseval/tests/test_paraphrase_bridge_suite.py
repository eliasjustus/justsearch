"""tempdoc 788 §3.B.10 — extraction + scoring logic of the paraphrase-bridging suite.

`experiments/paraphrase_bridge_suite.py` measures how reliably the semantic stack
bridges a query-side paraphrase to the document-side surface it was generated
from.  Everything the *measurement* rests on is checked here and runs in CI: the
pairs come from the generator (never hand-typed), the pairs are token-disjoint
(which is what makes the lexical arm a valid control), the surface join to the
committed 781 corpora agrees with the generator's own index arithmetic, and the
ranking/curve helpers behave.

The ONNX arms are NOT exercised here — they need the model blobs, which this
repo never tracks.  Their inputs and outputs are the tiers' JSON artifacts.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiments"))

import paraphrase_bridge_suite as pbs  # noqa: E402

CORPORA = Path(__file__).resolve().parents[1] / "781-corpora"


# ---------------------------------------------------------------------------
# Pools come from the generator, not from this file
# ---------------------------------------------------------------------------

def test_pools_are_imported_from_the_generator_module():
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from jseval import corpus_generate as cg

    for lang, expected in (("en", (cg._SEM_TYPE, cg._SEM_PLACE, cg._SEM_QUAL)),
                           ("de", (cg._SEM_TYPE_DE, cg._SEM_PLACE_DE, cg._SEM_QUAL_DE))):
        pools = pbs.load_pools(lang)
        assert pools["type"] == list(expected[0])
        assert pools["place"] == list(expected[1])
        assert pools["qual"] == list(expected[2])


def test_pool_shapes_match_the_documented_regime():
    pools = pbs.load_pools("en")
    assert (len(pools["type"]), len(pools["place"]), len(pools["qual"])) == (21, 44, 25)
    assert all(len(p) == 2 and all(isinstance(s, str) for s in p)
               for pool in pools.values() for p in pool)


@pytest.mark.parametrize("lang", ["en", "de"])
def test_every_pair_is_token_and_stem_disjoint(lang):
    """The control's validity rests on this: if any pair shared a token (or even
    a stem — the leak class tempdoc 767 closed), the lexical arm could bridge it
    by exact match and would no longer be a control.  Any future pool edit that
    breaks disjointness must fail HERE, not silently inflate the lexical arm."""
    for axis, pool in pbs.load_pools(lang).items():
        for i, (doc_s, query_s) in enumerate(pool):
            px = pbs.lexical_proxies(doc_s, query_s)
            assert px["token_jaccard"] == 0.0, (lang, axis, i, doc_s, query_s)
            assert px["stem_jaccard"] == 0.0, (lang, axis, i, px["shared_stems"])


# ---------------------------------------------------------------------------
# The surface join to the committed corpora
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def observed():
    return pbs.extract_observed_pairs(CORPORA, "en")


def test_committed_corpora_join_without_a_single_mismatch(observed):
    """The join asserts the query-side synonym and the doc-side surface resolve
    to the SAME pool index.  A non-empty mismatch list means the suite is
    attributing an outcome to the wrong pair."""
    assert observed["mismatches"] == []
    assert observed["n_members"] == 8
    assert len(observed["observations"]) == 8 * 50 * 2  # members x queries x {type, place}


def test_committed_corpora_exercise_the_two_axis_regime_only(observed):
    """50-chain corpora sit under the 924 two-axis ceiling, so the generator
    never engages the qualifier axis — 21 type + 44 place pairs are exercised
    and all 25 qualifier pairs are unobserved.  If this flips, the pair census
    in the tempdoc is stale."""
    by_axis = {"type": set(), "place": set(), "qual": set()}
    for o in observed["observations"]:
        by_axis[o["axis"]].add(o["index"])
    assert by_axis["type"] == set(range(21))
    assert by_axis["place"] == set(range(44))
    assert by_axis["qual"] == set()
    assert sum(1 for p in observed["pairs"] if p["observed_in_committed_corpora"]) == 65


def test_surface_join_agrees_with_generator_index_arithmetic(observed):
    """Independent cross-check of the join: `_sem_for`'s two-axis gold branch
    assigns chain g the pair (g % T, g % P).  The suite deliberately does NOT
    use that arithmetic (it matches surfaces instead), so agreement is real
    corroboration rather than a tautology."""
    n_type, n_place = 21, 44
    for o in observed["observations"]:
        g = o["qidx"]
        expected = g % n_type if o["axis"] == "type" else g % n_place
        assert o["index"] == expected, o


def test_anchor_pairs_resolve_to_the_hero_surfaces(observed):
    """The two pinned hero cases must keep pointing at the surfaces the campaign
    observed — a pool edit that renumbered them would silently re-anchor the
    regression rows."""
    by_id = {p["pair_id"]: p for p in observed["pairs"]}
    assert (by_id["en:type:00"]["doc_surface"], by_id["en:type:00"]["query_surface"]) \
        == ("reactor", "power station")
    assert (by_id["en:type:16"]["doc_surface"], by_id["en:type:16"]["query_surface"]) \
        == ("mint", "coin-striking works")
    assert (by_id["en:place:16"]["doc_surface"], by_id["en:place:16"]["query_surface"]) \
        == ("desert basin", "arid hollow")
    for anchor in pbs.ANCHORS:
        for pid in anchor["pair_ids"]:
            assert by_id[pid]["observed_in_committed_corpora"], pid


def test_head_evidence_document_is_the_descriptor_bearing_one(observed):
    """The suite scores `evidence_ids[0]` as the bridge target.  That is only
    right if the first evidence document is the chain HEAD (the one carrying the
    doc-side descriptor); the join found the doc-side surface in it for every
    observation, which is exactly that property."""
    assert all(o["head_evidence_id"] for o in observed["observations"])
    heads = {(o["member"], o["qidx"]): o["head_evidence_id"] for o in observed["observations"]}
    assert len(heads) == 8 * 50


# ---------------------------------------------------------------------------
# Distance proxies
# ---------------------------------------------------------------------------

def test_analyze_is_nfc_lowercase_unicode_word_split():
    assert pbs.analyze("Gießerei, Metall-Werk") == ["gießerei", "metall", "werk"]
    assert pbs.analyze("Äbc") == pbs.analyze("Äbc")  # NFC-folded


def test_identical_phrases_score_maximum_on_every_proxy():
    px = pbs.lexical_proxies("clock tower", "clock tower")
    assert px["token_jaccard"] == 1.0
    assert px["char3_jaccard"] == 1.0
    assert px["norm_edit_similarity"] == 1.0
    assert px["word_count_delta"] == 0


def test_shared_stem_is_detected_where_token_disjointness_is_not_enough():
    """The 767 leak class: token-disjoint but rooted on the same distinctive
    modifier.  `stem_jaccard` is the proxy that sees it; `token_jaccard` does
    not, which is why both are recorded."""
    px = pbs.lexical_proxies("carpathian highlands", "carpathian upland")
    assert px["token_jaccard"] < 1.0
    assert "carpathian" in px["shared_content_tokens"]
    px2 = pbs.lexical_proxies("printing house", "printers houses")
    assert px2["token_jaccard"] == 0.0
    assert px2["stem_jaccard"] > 0.0


def test_function_words_do_not_count_as_shared_content():
    px = pbs.lexical_proxies("river bend", "curve of the watercourse")
    assert px["shared_content_tokens"] == []
    assert px["content_token_jaccard"] == 0.0


# ---------------------------------------------------------------------------
# Ranking + control arm
# ---------------------------------------------------------------------------

def test_bm25_ranks_the_lexically_matching_document_first():
    docs = [pbs.analyze(t) for t in
            ("the reactor in the northern marshlands", "the vineyard in the sunny valley",
             "a completely unrelated memorandum about pipeline scheduling")]
    bm = pbs.Bm25(docs)
    assert pbs.rank_of(bm.scores(pbs.analyze("northern marshlands reactor")), 0) == 1


def test_bm25_cannot_rank_a_token_disjoint_paraphrase():
    """The control, in miniature: the query names the head by synonym, so no
    query term occurs in any document and every score ties at zero."""
    docs = [pbs.analyze(t) for t in
            ("the reactor in the northern marshlands", "the vineyard in the sunny valley")]
    scores = pbs.Bm25(docs).scores(pbs.analyze("power station upper wetlands"))
    assert scores == [0.0, 0.0]
    assert pbs.rank_of(scores, 0) == 2  # pessimistic: a tie is never a bridge


def test_rank_of_is_pessimistic_on_ties():
    assert pbs.rank_of([5.0, 5.0, 5.0], 0) == 3
    assert pbs.rank_of([1.0, 3.0, 2.0], 1) == 1
    assert pbs.rank_of([1.0, 3.0, 2.0], 0) == 3


def test_bridge_flags_track_the_reported_ks():
    assert pbs.bridge_flags(1) == {"top1": True, "top3": True, "top5": True, "top10": True}
    assert pbs.bridge_flags(4) == {"top1": False, "top3": False, "top5": True, "top10": True}
    assert pbs.bridge_flags(99) == {f"top{k}": False for k in pbs.BRIDGE_KS}


# ---------------------------------------------------------------------------
# Curve + report helpers
# ---------------------------------------------------------------------------

def test_bucket_label_covers_the_declared_edges():
    assert pbs.bucket_label(0.0) == "[0.00,0.55)"
    assert pbs.bucket_label(0.549) == "[0.00,0.55)"
    assert pbs.bucket_label(0.55) == "[0.55,0.65)"
    assert pbs.bucket_label(1.0) == "[0.85,1.01)"


def test_reliability_curve_buckets_a_multi_pair_row_on_its_hardest_pair():
    """A query only bridges if EVERY descriptor axis it names bridges, so the
    row belongs in its lowest-cosine bucket — not its mean or its easiest."""
    rows = [
        {"pair_ids": ["a", "b"], "arms": {"dense": {"top10": True}}},
        {"pair_ids": ["b"], "arms": {"dense": {"top10": False}}},
    ]
    curve = pbs.reliability_curve(rows, {"a": 0.50, "b": 0.80}, "dense", k=10)
    by_bucket = {c["bucket"]: c for c in curve}
    assert by_bucket["[0.00,0.55)"]["n"] == 1  # bucketed on 'a', the harder pair
    assert by_bucket["[0.75,0.85)"]["n"] == 1


def test_reliability_curve_skips_rows_with_no_known_pair():
    curve = pbs.reliability_curve(
        [{"pair_ids": ["unknown"], "arms": {"dense": {"top10": True}}}], {"a": 0.5}, "dense")
    assert curve == []


def test_arm_summary_computes_rates_and_mrr():
    rows = [{"arms": {"dense": {"rank": 1, **pbs.bridge_flags(1)}}},
            {"arms": {"dense": {"rank": 4, **pbs.bridge_flags(4)}}}]
    s = pbs.arm_summary(rows)["dense"]
    assert s["n"] == 2
    assert s["top1_rate"] == 0.5
    assert s["top5_rate"] == 1.0
    assert s["mrr"] == round((1.0 + 0.25) / 2, 4)


def test_dataset_member_index_remaps_committed_names_to_dataset_dirs():
    """Tier D reads dataset dirs named `<corpus>-<scale>-<variant>`; the pair
    register is keyed by the committed `<corpus>/<n_docs>-<variant>`.  Same
    chains, different naming — the remap is what lets tier D reuse tier-P/S
    pair ids instead of re-deriving them."""
    remap = pbs.dataset_member_index({
        ("en-email-enron-raw/1000-verbose", 3): ["en:type:03"],
        ("en-legal-clerc/10000-short-natural", 7): ["en:place:07"],
    })
    assert remap[("en-email-enron-raw-1k-verbose", 3)] == ["en:type:03"]
    assert remap[("en-legal-clerc-10k-short-natural", 7)] == ["en:place:07"]


def test_query_forms_reproduce_the_shapes_the_hero_agents_actually_issued(observed):
    """The census recorded, verbatim, two of the query strings a hero cell sent
    for the failing q0: `power station in the upper wetlands` and
    `upper wetlands power station`.  Both must fall out of the pair register —
    if they don't, the suite is measuring a query shape no agent types."""
    pairs = {p["pair_id"]: p for p in observed["pairs"]}
    forms = pbs.query_forms("What is the value ...?", ["en:type:00", "en:place:00"], pairs)
    assert forms["descriptor"] == "power station in the upper wetlands"
    assert forms["keyword"] == "upper wetlands power station"
    assert forms["question"] == "What is the value ...?"


def test_query_forms_degrade_to_question_only_without_both_axes():
    assert pbs.query_forms("q", [], {}) == {"question": "q"}
    assert pbs.query_forms("q", ["en:type:00"],
                           {"en:type:00": {"axis": "type", "query_surface": "power station"}}) \
        == {"question": "q"}


def test_encode_blocks_reuses_checkpoints_and_only_encodes_the_missing_ones(tmp_path):
    """Tier D is hours of CPU encoding and this campaign lost a run to an
    environment-level kill at document ~300.  A resumed run must re-encode only
    the blocks that are not already on disk, and must reassemble them in
    document order."""
    texts = [f"doc-{i}" for i in range(250)]  # 3 blocks at CHECKPOINT_DOCS=100
    encoded: list[int] = []

    def encode(ts):
        encoded.append(len(ts))
        return list(ts)

    def load(p):
        f = p.with_suffix(".json")
        return __import__("json").loads(f.read_text(encoding="utf-8")) if f.exists() else None

    def save(p, block):
        p.with_suffix(".json").write_text(__import__("json").dumps(block), encoding="utf-8")

    first = pbs._encode_blocks("k", texts, tmp_path, "m", encode, load, save, 0.0)
    assert encoded == [100, 100, 50]
    assert [t for b in first for t in b] == texts

    encoded.clear()
    second = pbs._encode_blocks("k", texts, tmp_path, "m", encode, load, save, 0.0)
    assert encoded == []  # everything served from the checkpoint
    assert second == first

    (tmp_path / "m" / "k" / "block-0001.json").unlink()
    encoded.clear()
    third = pbs._encode_blocks("k", texts, tmp_path, "m", encode, load, save, 0.0)
    assert encoded == [100]  # only the deleted block re-encoded
    assert [t for b in third for t in b] == texts


def test_encode_blocks_without_a_cache_dir_encodes_everything():
    calls = []
    out = pbs._encode_blocks("k", ["a", "b"], None, "m",
                             lambda ts: calls.append(ts) or list(ts),
                             lambda p: pytest.fail("must not load"),
                             lambda p, b: pytest.fail("must not save"), 0.0)
    assert calls == [["a", "b"]]
    assert out == [["a", "b"]]


def test_committed_pair_register_matches_a_fresh_extraction():
    """`scripts/jseval/796-paraphrase-pairs/paraphrase-pairs.v1.json` is committed so a later
    execution pass (or a reviewer) can read the pair set without re-deriving it.  It is a
    projection of the generator pools + the committed corpora, so it must never drift from
    them: an edit to `_SEM_*` or to a 781 member that is not reflected here fails HERE."""
    committed = json.loads(pbs.DEFAULT_REGISTER.read_text(encoding="utf-8"))
    assert committed["schema"] == "paraphrase-pairs.v1"
    for lang in ("en", "de"):
        fresh = pbs.extract_observed_pairs(CORPORA, lang)
        for p in fresh["pairs"]:
            p.update(pbs.lexical_proxies(p["doc_surface"], p["query_surface"]))
        assert committed["langs"][lang]["pairs"] == fresh["pairs"], (
            f"{lang}: committed register is stale — regenerate with "
            f"`paraphrase_bridge_suite.py pairs --langs en,de`")
        assert committed["langs"][lang]["mismatches"] == fresh["mismatches"]
        assert committed["langs"][lang]["n_observations"] == len(fresh["observations"])
        assert committed["langs"][lang]["n_members"] == fresh["n_members"]


def test_production_recipe_constants_match_the_java_side():
    """These are mirrors of shipped Java/config values, not tunables of this
    experiment.  Pinned so a drift shows up as a test failure rather than a
    silently non-production measurement."""
    assert (pbs.EMBED_WINDOW, pbs.EMBED_OVERLAP, pbs.EMBED_CTX) == (512, 128, 2048)
    assert (pbs.CHUNK_TOKENS, pbs.CHUNK_OVERLAP) == (500, 50)
    assert pbs.SPLADE_MAXSEQ == 512
    assert pbs.SPLADE_SKIP_IDS == {0, 100, 101, 102, 103}
