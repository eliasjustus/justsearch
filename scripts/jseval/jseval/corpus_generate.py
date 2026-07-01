"""Procedural fabricated-corpus generator (tempdoc 635).

Generates the committed *source* (`docs.jsonl` + `queries.json` + `meta.json`) for a
clean, high-fidelity self-demo corpus, deterministically, with the de-risk-proven
difficulty levers as explicit parameters:

- **genuine multi-hop by construction** — questions are emitted FROM fabricated
  entity-relation *chains*, so the answer exists only by combining ≥2 docs (no
  single-doc shortcut; the §D.5 fidelity gate's shortcut sub-gate certifies this).
- **hard-negative distractors** — parallel fabricated chains (same vocabulary/shape,
  different entities, no answers), at a tunable ratio (de-risk U-B: ~5–10:1 to reach
  the realistic nDCG band).
- **long docs** — padded to > the 512-token chunk size so dense engages (de-risk U-C)
  and file-reads are expensive (the agent token-efficiency lever).
- **contamination-free** — all entities/facts are fabricated (the closed-book gate
  certifies the model can't answer from memory).

Per-axis renderers (R-3): `prose`, `code`, `tabular`, and a `lang` knob for the
multilingual member. Fully procedural + seeded → reproducible (no LLM needed; the LLM
patterns in `utility_judge` remain available for optional prose enrichment, unused here
to keep generation deterministic and gate-certifiable).
"""

from __future__ import annotations

import hashlib
import json
import random
import subprocess
import sys
from pathlib import Path

# Fabricated syllable pools — combined by the seeded RNG into invented, unguessable
# entity names (the closed-book gate certifies non-memorizability).
_SYL_A = ["zel", "quen", "vor", "mir", "tas", "brel", "kan", "olm", "vex", "dru",
          "pell", "harn", "sko", "lim", "rell", "cav", "nuth", "orr", "wend", "fal"]
_SYL_B = ["thorn", "by", "mire", "ven", "dac", "lun", "ric", "mond", "ash", "ker",
          "vale", "post", "wick", "dell", "grove", "fen", "stone", "reach", " holt".strip(), "crag"]
# Attribute values are fabricated UNIQUELY per chain (adjective + noun + the chain's
# unique uid), so an answer (e.g. "ochre ferrolite 0047") is uniquely determined by its
# chain and never appears in a distractor doc (review Issue-C: a shared pool let the same
# answer string recur across gold + distractors).
_ATTR_ADJ = ["ochre", "crimson", "azure", "umber", "verdant", "pallid", "russet", "indigo"]
_ATTR_NOUN = ["ferrolite", "lansk", "brannik", "skack", "vellum", "grist", "perrin", "quartzine"]
_FILLER = ("The surrounding district is known for long winters and quiet markets where "
           "traders gather to exchange goods and stories. Over the years many travellers "
           "passed through, leaving small monuments and the occasional inscription. Scholars "
           "studied the region's history at length, noting the slow rise of its institutions "
           "and the patient work of its builders. Records from the period are sparse but "
           "consistent, describing a community that valued careful measurement and steady craft. ")


# Semantic descriptors (review Issue A+B fix): the head doc describes the entity with
# one surface phrasing; the QUERY references it via SYNONYMS (low lexical overlap), so
# exact-match `Grep` / pure-BM25 fails at the entry point but dense/SPLADE bridges
# semantically — the only setup where JustSearch's retrieval can beat a grep-agent.
# (doc_noun, query_noun) type synonyms + (doc_place, query_place) place synonyms. The
# head's descriptor combines a type + a UNIQUE place per gold chain, so the query (synonyms
# of both) identifies exactly one head SEMANTICALLY — but shares no surface tokens with the
# doc, so grep/pure-BM25 fail at the entry. Distractors reuse the same vocabulary (other
# type+place combos) → hard negatives.
_SEM_TYPE = [
    ("reactor", "power station"), ("observatory", "stargazing facility"),
    ("watermill", "grain-grinding works"), ("archive", "records vault"),
    ("foundry", "metal-casting works"), ("telescope", "optical instrument"),
    ("tramway", "streetcar line"), ("aqueduct", "water channel"),
    ("printing house", "publishing works"), ("lighthouse", "coastal beacon"),
    ("vineyard", "wine estate"), ("bathhouse", "thermal spa"),
]
_SEM_PLACE = [
    ("northern marshlands", "upper wetlands"), ("eastern ridge", "ridge to the east"),
    ("river bend", "curve of the river"), ("old courthouse", "former justice building"),
    ("western district", "quarter to the west"), ("Carpathian highlands", "Carpathian uplands"),
    ("hill city", "city on the slopes"), ("southern hills", "hills to the south"),
    ("market square", "central marketplace"), ("rocky headland", "stony promontory"),
    ("sunny valley", "sunlit dale"), ("coastal cliffs", "shoreline bluffs"),
    ("pine forest", "evergreen woodland"), ("salt flats", "saline plains"),
    ("granite quarry", "stone pit"), ("harbour mouth", "port entrance"),
    ("desert basin", "arid hollow"), ("frozen lake", "iced-over tarn"),
    ("walled garden", "enclosed grounds"), ("clock tower", "belfry spire"),
    ("copper mine", "ore working"), ("windswept moor", "blustery heath"),
    ("river delta", "estuary fan"), ("mountain pass", "high col"),
    ("fishing wharf", "angling quay"), ("orchard slope", "fruit-grove hillside"),
]

# German synonym pools — the Invariant-#6 (ADR-0043) showcase: the doc descriptor and the
# query synonym share NO surface tokens, so grep/pure-BM25 fail, and the multilingual dense
# model must bridge German↔German semantically. Catalog phrasing ("Standort: <type>, <place>.")
# avoids gender/preposition agreement across all combinations.
_SEM_TYPE_DE = [
    ("Reaktor", "Kraftwerk"), ("Sternwarte", "Beobachtungsstation"),
    ("Wassermühle", "Getreidemühle"), ("Archiv", "Aktenlager"),
    ("Gießerei", "Metallwerk"), ("Teleskop", "Fernrohr"),
    ("Straßenbahn", "Trambahn"), ("Aquädukt", "Wasserleitung"),
    ("Druckerei", "Verlagshaus"), ("Leuchtturm", "Küstenfeuer"),
    ("Weingut", "Weinanbaugebiet"), ("Badehaus", "Thermalbad"),
]
_SEM_PLACE_DE = [
    ("nördliches Marschland", "oberes Feuchtgebiet"), ("östlicher Bergrücken", "Höhenzug im Osten"),
    ("Flussbiegung", "Krümmung des Flusses"), ("altes Gerichtsgebäude", "früheres Justizgebäude"),
    ("westlicher Bezirk", "Viertel im Westen"), ("Karpatenhochland", "Karpaten-Bergland"),
    ("Hügelstadt", "Stadt an den Hängen"), ("südliche Hügel", "Hügel im Süden"),
    ("Marktplatz", "zentrales Marktviertel"), ("felsige Landzunge", "steiniges Vorgebirge"),
    ("sonniges Tal", "lichtdurchflutete Senke"), ("Küstenklippen", "Steilküste am Ufer"),
    ("Kiefernwald", "immergrüner Forst"), ("Salzebene", "salzhaltiges Flachland"),
    ("Granitsteinbruch", "Steingrube"), ("Hafeneinfahrt", "Zugang zum Hafen"),
    ("Wüstenbecken", "trockene Mulde"), ("zugefrorener See", "vereister Bergsee"),
    ("ummauerter Garten", "eingefriedete Anlage"), ("Uhrturm", "Glockenturm"),
    ("Kupfermine", "Erzgrube"), ("windige Heide", "stürmisches Moor"),
    ("Flussdelta", "Mündungsfächer"), ("Gebirgspass", "hohes Joch"),
    ("Fischerkai", "Anglersteg"), ("Obsthang", "Obstgarten am Hang"),
]


def _sem_for(idx, rng, *, gold, lang="en"):
    """Build a (doc_noun, query_noun, doc_place, query_place) tuple. Gold chains get a UNIQUE
    place by index (disambiguable); distractors get a random type+place (hard negatives).
    ``lang`` selects the English or German synonym pools."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    if gold:
        t = types[idx % len(types)]
        p = places[idx % len(places)]
    else:
        t, p = rng.choice(types), rng.choice(places)
    return (t[0], t[1], p[0], p[1])


def _name(rng: random.Random, uid: int) -> str:
    # Monotonic uid suffix guarantees uniqueness (the syllable space alone is too small
    # for a high distractor ratio → would otherwise collide and spin). Reads as a catalog id.
    return (rng.choice(_SYL_A) + rng.choice(_SYL_B)).capitalize() + str(uid)


def _pad(text: str, target_words: int) -> str:
    out = text
    while len(out.split()) < target_words:
        out += " " + _FILLER
    return out


# --- relation vocabulary per axis (kind, prose phrasing, question phrasing) ---
_RELATIONS = {
    "prose": [
        ("designed", "was designed by the engineer", "the designer of"),
        ("founded", "was founded by", "the founder of"),
        ("built", "was built by", "the builder of"),
        ("led", "was led by", "the leader of"),
    ],
}


def _chain(rng, hops, counter):
    """A fabricated chain: entities e0..e{hops} (globally-unique), ending in an attribute.

    ``counter`` is a single-element mutable list used as a monotonic id source so every
    entity across the whole corpus is unique (no collisions → no spin).
    """
    ents = []
    for _ in range(hops + 1):
        counter[0] += 1
        ents.append(_name(rng, counter[0]))
    # unique per chain: adjective + noun + the chain's last (unique) uid
    attr = f"{rng.choice(_ATTR_ADJ)} {rng.choice(_ATTR_NOUN)} {counter[0]:04d}"
    return ents, attr


def _render_prose(ents, attr, rels, target_words, lang="en", sem=None):
    """Render a chain as gold docs (one per hop link) + a multi-hop question.

    If ``sem`` is a `_SEM` tuple, the HEAD doc describes the entity by a descriptor and
    the QUERY references the head via SYNONYMS (low lexical overlap → grep fails, semantic
    retrieval bridges) — the Issue-A/B fix. The rest of the chain uses names as before.
    """
    docs = []
    for i in range(len(ents) - 1):
        rel = rels[i % len(rels)]
        if lang == "de":
            if sem and i == 0:
                # head doc: German descriptor (sem[0]/sem[2]); the query references it by
                # German SYNONYMS (sem[1]/sem[3]) → grep fails, multilingual dense bridges.
                body = (f"Standort: {sem[0]}, {sem[2]}. "
                        f"Das Objekt {ents[i]} ist mit {ents[i+1]} verknüpft. ")
                title = f"Standort {sem[0]}, {sem[2]}"
            else:
                body = f"Das Objekt {ents[i]} ist mit {ents[i+1]} verknüpft. "
                title = f"Über {ents[i]}"
        elif sem and i == 0:
            # head doc: surface descriptor (doc_noun/doc_place) + name + link
            body = f"The {sem[0]} in the {sem[2]}, designated {ents[i]}, {rel[1]} {ents[i+1]}. "
            title = f"The {sem[0]} in the {sem[2]}"
        else:
            body = f"The {ents[i]} {rel[1]} {ents[i+1]}. "
            title = f"The {ents[i]}"
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    if lang == "de":
        docs.append((last.lower(), f"Über {last}",
                     _pad(f"{last} ist mit dem Wert {attr} verbunden. ", target_words)))
        if sem:
            # reference the head by its German synonym descriptor (sem[1]/sem[3]), NOT its name
            q = (f"Folgt man den Verknüpfungen ausgehend vom Standort {sem[1]}, {sem[3]}, "
                 f"mit welchem Wert ist die letzte Entität verbunden?")
        else:
            q = (f"Folgt man den Verknüpfungen ausgehend von {ents[0]}, "
                 f"mit welchem Wert ist die letzte Entität verbunden?")
    else:
        docs.append((last.lower(), f"The {last}", _pad(f"{last} is associated with {attr}. ", target_words)))
        # head reference: SYNONYM descriptor (semantic) or the verbatim name (lexical)
        head_ref = f"the {sem[1]} in the {sem[3]}" if sem else ents[0]
        phrase = head_ref
        for i in range(len(ents) - 1):
            phrase = f"{rels[i % len(rels)][2]} {phrase}"
        q = f"What is the value associated with {phrase}?"
    evidence = [e.lower() for e in ents]
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": evidence}


def _render_code(ents, attr, target_words, idx, sem=None):
    """Render a chain as code files: fn e0 calls e1 calls ... returns attr. Multi-hop = call trace.

    If ``sem`` is set, the head function carries its purpose as a descriptor comment
    (sem[0]/sem[2]) and the QUERY references it via SYNONYMS (sem[1]/sem[3]) without naming
    the function — so grep/pure-BM25 fail at the entry and dense must bridge semantically.
    """
    docs = []
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head doc: descriptor in the TITLE + a module docstring (the high-signal fields
            # dense embeds), mirroring the prose member — sem[0]/sem[2] (doc side) so the query's
            # sem[1]/sem[3] synonyms stay zero-overlap (grep-defeating).
            title = f"the {sem[0]} in the {sem[2]}"
            body = (f'"""This module concerns the {sem[0]} in the {sem[2]}."""\n'
                    f"def {ents[i].lower()}():\n    return {ents[i+1].lower()}()\n\n"
                    + "# " + _FILLER.replace(". ", ".\n# "))
        else:
            title = f"{ents[i].lower()}.py"
            body = (f"def {ents[i].lower()}():\n    # module helper {idx}.{i}\n"
                    f"    return {ents[i+1].lower()}()\n\n" + "# " + _FILLER.replace(". ", ".\n# "))
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    body = (f"def {last.lower()}():\n    return {attr!r}\n\n" + "# " + _FILLER.replace(". ", ".\n# "))
    docs.append((last.lower(), f"{last.lower()}.py", _pad(body, target_words)))
    if sem:
        q = (f"What value is ultimately returned by the routine for the "
             f"{sem[1]} in the {sem[3]}?")
    else:
        q = f"What value does the function {ents[0].lower()}() ultimately return when called?"
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


def _render_tabular(ents, attr, target_words, idx, sem=None):
    """Render a chain as table rows requiring a join across docs.

    If ``sem`` is set, the head table carries a descriptor caption (sem[0]/sem[2]) and the
    QUERY references it via SYNONYMS (sem[1]/sem[3]) without naming the head entity — so
    grep/pure-BM25 fail and dense must bridge semantically.
    """
    docs = []
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head table: descriptor in the TITLE + a leading caption (high-signal), mirroring
            # the prose member — doc-side sem[0]/sem[2] keeps the query's sem[1]/sem[3] zero-overlap.
            title = f"the {sem[0]} in the {sem[2]}"
            caption = f"Table for the {sem[0]} in the {sem[2]}.\n"
        else:
            title = f"table_{ents[i].lower()}"
            caption = ""
        body = (f"{caption}| entity | linked_to |\n|---|---|\n| {ents[i]} | {ents[i+1]} |\n\n" + _FILLER)
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    body = (f"| entity | attribute |\n|---|---|\n| {last} | {attr} |\n\n" + _FILLER)
    docs.append((last.lower(), f"table_{last.lower()}", _pad(body, target_words)))
    if sem:
        q = (f"In the records for the {sem[1]} in the {sem[3]}, following the links, "
             f"what attribute is recorded for the final entity?")
    else:
        q = f"Following the links starting from {ents[0]}, what attribute is recorded for the final entity?"
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


def generate(out_dir, *, axis="prose", lang="en", n_chains=20, hops=2,
             distractor_ratio=6, doc_words=520, suite="635-self-demo-v1", seed=635,
             semantic=False):
    """Generate a fabricated corpus source into ``out_dir`` (docs.jsonl/queries.json/meta.json).

    distractor_ratio = distractor docs per gold doc (de-risk: ~5–10:1 to reach the band).
    semantic=True (all axes, en+de): the head is referenced in the query by SYNONYMS of its
    descriptor (low lexical overlap → grep/BM25 fail at the entry, semantic retrieval wins).
    code/tabular carry the descriptor in a head comment/caption; German uses the de synonym
    pools. Capped at the (lang-appropriate) place-pool size gold chains for unique descriptors.
    """
    # `hash(axis)` (a builtin str hash) is randomized per-process (PEP 456) unless
    # PYTHONHASHSEED is pinned, which this repo never does — so the "seeded -> reproducible"
    # claim above was false: two separate process invocations with the identical nominal `seed`
    # produced a completely different corpus (confirmed empirically, tempdoc 664 confidence pass:
    # 280/280 docs differed). A SHA-256 digest of `axis` is stable across processes, restoring
    # the determinism the docstring already promised.
    axis_offset = int(hashlib.sha256(axis.encode("utf-8")).hexdigest(), 16) % 1000
    rng = random.Random(seed + axis_offset)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    rels = _RELATIONS["prose"]
    # Semantic (synonym-bridge) queries are now supported on ALL axes (prose/code/tabular)
    # and both languages — the head doc carries a descriptor and the query references it via
    # zero-overlap synonyms, so grep/pure-BM25 fail and dense must bridge (the only setup where
    # JustSearch's retrieval beats a grep-agent → a real ceiling instead of a trivial nDCG 1.0).
    sem_active = bool(semantic)
    sem_places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    if sem_active:
        n_chains = min(n_chains, len(sem_places))  # one unique descriptor place per gold

    def render(e, a, sem):
        if axis == "prose":
            return _render_prose(e, a, rels, doc_words, lang, sem=sem)
        if axis == "code":
            return _render_code(e, a, doc_words, rng.randint(0, 999), sem=sem)
        return _render_tabular(e, a, doc_words, rng.randint(0, 999), sem=sem)

    counter = [0]  # monotonic unique-id source across gold + distractors
    all_docs, queries = [], []
    for g in range(n_chains):
        ents, attr = _chain(rng, hops, counter)
        sem = _sem_for(g, rng, gold=True, lang=lang) if sem_active else None
        docs, q = render(ents, attr, sem)
        for did, title, text in docs:
            all_docs.append({"_id": did, "title": title, "text": text})
        queries.append(q)

    # distractors: parallel fabricated chains (globally-unique entities), rendered the
    # same way, NOT referenced by any query → hard negatives.
    n_distract = int(len(all_docs) * distractor_ratio)
    made = 0
    while made < n_distract:
        ents, attr = _chain(rng, hops, counter)
        sem = _sem_for(0, rng, gold=False, lang=lang) if sem_active else None
        docs, _q = render(ents, attr, sem)
        for did, title, text in docs:
            if made >= n_distract:
                break
            all_docs.append({"_id": did, "title": title, "text": text})
            made += 1

    # tempdoc 664 (twelfth pass): gold and distractor docs were previously written in two
    # unbroken blocks (all gold first, all distractors after) -- the only real "positional
    # non-uniformity" this generator has (there is no NoLiMa-style in-document depth to vary;
    # each hop is a separate short document, not a buried fact within one long context).
    # Interleaving with the same seeded `rng` (not a fresh Random()) keeps this inside the
    # existing seed-derived determinism chain -- query evidence_ids reference doc _id strings,
    # not positions, so this has no effect on query correctness.
    rng.shuffle(all_docs)

    with (out_dir / "docs.jsonl").open("w", encoding="utf-8") as f:
        for d in all_docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    (out_dir / "queries.json").write_text(json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "meta.json").write_text(json.dumps({
        "version": "1.0", "type_axis": axis, "suite": suite,
        "contamination_class": "private-synthetic",
        # tempdoc 664 (seventh pass): `n_chains`/`doc_words` were missing here, so a corpus's own
        # recorded provenance could not reconstruct the exact `generate()` call that produced it —
        # a regeneration-determinism check needs the FULL parameter set, not a partial one.
        "generation_provenance": {"method": "procedural-fabricated", "axis": axis, "lang": lang,
                                  "seed": seed, "hops": hops, "distractor_ratio": distractor_ratio,
                                  "semantic": sem_active, "templated": True,
                                  "n_chains": n_chains, "doc_words": doc_words},
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"docs": len(all_docs), "gold_chains": n_chains, "queries": len(queries),
            "distractor_docs": made}


def regenerate_and_diff(out1, out2, *, axis, lang, seed, hops, distractor_ratio, semantic,
                         n_chains, doc_words, timeout=60) -> dict:
    """Spawn :func:`generate` twice, in two SEPARATE Python processes, into ``out1``/``out2`` with
    identical parameters, then diff ``docs.jsonl``/``queries.json`` (tempdoc 664, twelfth pass).

    Runs in separate processes deliberately: an in-process call would hide any per-process-random
    source (like the original ``hash(axis)`` non-determinism bug this technique found — confirmed
    empirically pre-fix: 280/280 docs differed between two "identical seed" runs) because such
    sources are stable *within* one process.

    Shared by :func:`jseval.corpus_certify.regeneration_determinism_report` (the certification-time
    check) and the pytest regression test with the same name — a single implementation of the
    subprocess-spawn-and-diff technique rather than two independent copies that could drift apart
    (the "projection vs fork" principle this tempdoc is about, applied to test code).

    ``out1``/``out2`` are caller-supplied directories, left populated on return (not cleaned up
    here) so a caller that needs to inspect the generated content afterward — the interleave-order
    regression test — can do so; a caller that only needs the verdict (certification) is expected to
    pass ephemeral `tempfile.TemporaryDirectory()`-backed paths and let its own context manager
    clean up.

    :returns: ``{"ok": True, "mismatched_files": [...]}`` or ``{"ok": False, "error": "..."}`` if a
      regeneration subprocess itself failed (not a mismatch — a hard error, e.g. bad parameters).
    """
    script = (
        "from jseval import corpus_generate as cg; "
        "cg.generate(sys.argv[1], axis=sys.argv[2], lang=sys.argv[3], seed=int(sys.argv[4]), "
        "hops=int(sys.argv[5]), distractor_ratio=int(sys.argv[6]), "
        "semantic=(sys.argv[7] == 'True'), n_chains=int(sys.argv[8]), doc_words=int(sys.argv[9]))"
    )
    args = [axis, lang, str(seed), str(hops), str(distractor_ratio),
            str(bool(semantic)), str(n_chains), str(doc_words)]

    for out in (Path(out1), Path(out2)):
        result = subprocess.run(
            [sys.executable, "-c", "import sys; " + script, str(out), *args],
            capture_output=True, text=True, timeout=timeout,
        )
        if result.returncode != 0:
            return {"ok": False, "error": f"regeneration subprocess failed: {result.stderr[-500:]}"}

    mismatches = [
        fname for fname in ("docs.jsonl", "queries.json")
        if (Path(out1) / fname).read_text(encoding="utf-8") != (Path(out2) / fname).read_text(encoding="utf-8")
    ]
    return {"ok": True, "mismatched_files": mismatches}
