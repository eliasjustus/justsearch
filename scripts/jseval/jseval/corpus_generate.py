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

import base64
import hashlib
import io
import json
import math
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
    # -- tempdoc 624 scale-corpus: appended 12->21 to widen the triple space (append-only,
    # existing indices/entries above are untouched so committed corpora regenerate identically).
    ("granary", "grain depot"), ("shipyard", "vessel works"),
    ("brewery", "ale house"), ("tannery", "hide-processing works"),
    ("mint", "coin-striking works"), ("smithy", "blacksmith works"),
    ("greenhouse", "glasshouse nursery"), ("chapel", "small place of worship"),
    ("windmill", "grain-milling tower"),
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
    # -- tempdoc 624 scale-corpus: appended 26->44 to widen the triple space (append-only).
    ("limestone caverns", "chalky underground hollows"), ("iron bridge", "metal river crossing"),
    ("floodplain village", "lowland riverside settlement"), ("terraced hillside plots", "layered slope embankments"),
    ("abandoned rail yard", "disused switching grounds"), ("chalk downs", "white escarpment hills"),
    ("tidal flats", "mudflat shallows"), ("border checkpoint", "frontier crossing post"),
    ("derelict airstrip", "disused landing ground"), ("underground cistern", "buried water reservoir"),
    ("shipwreck cove", "sunken-vessel inlet"), ("thermal spring basin", "hot-water hollow"),
    ("sandstone crags", "reddish rock escarpment"), ("ferry landing", "boat crossing point"),
    ("collapsed mineshaft", "caved-in excavation shaft"), ("sunlit overlook", "sun-facing viewing ledge"),
    ("brackish lagoon", "salt-tinged inlet"), ("derelict watchtower", "abandoned lookout post"),
]

# Third combinatorial descriptor axis (tempdoc 624 T.1): a numbered/ordinal qualifier,
# synonym-paired like type/place — the doc surface uses the cardinal ("unit seven"), the
# query synonym uses the ordinal ("the seventh installation"), zero token overlap per pair.
# It widens the achievable non-colliding descriptor space (12 types x 26 places = 312 combos
# was measured to produce 43-94% distractor-descriptor duplication at realistic corpus
# scale/distractor_ratio). Tempdoc 624 scale-corpus: it ALSO now carries gold-chain uniqueness
# jointly with type+place — the query references the full (type, place, qualifier) synonym
# triple, so the gold-chain ceiling is the triple-injectivity period lcm(T,P,Q) (see
# `_max_semantic_chains`), NOT the place-pool size the original single-axis cap used.
# Tempdoc 624 scale-corpus (pool-growth follow-up): the 12x26x20=6240-triple space was in turn
# too crowded at ~3000 docs (distractors sharing 2-of-3 descriptors with a gold query buried it
# below the retrieval-fidelity floor). Pools grown append-only to 21 types x 44 places x 25 quals
# = 23100 triples; math.lcm(21,44,25) == 23100 exactly since the three sizes are pairwise coprime
# (21=3*7, 44=2^2*11, 25=5^2), so the CRT injectivity argument in `_max_semantic_chains` still
# gives an exact (not just a lower-bound) gold-chain ceiling.
_SEM_QUAL = [
    ("unit one", "the first installation"), ("unit two", "the second installation"),
    ("unit three", "the third installation"), ("unit four", "the fourth installation"),
    ("unit five", "the fifth installation"), ("unit six", "the sixth installation"),
    ("unit seven", "the seventh installation"), ("unit eight", "the eighth installation"),
    ("unit nine", "the ninth installation"), ("unit ten", "the tenth installation"),
    ("unit eleven", "the eleventh installation"), ("unit twelve", "the twelfth installation"),
    ("unit thirteen", "the thirteenth installation"), ("unit fourteen", "the fourteenth installation"),
    ("unit fifteen", "the fifteenth installation"), ("unit sixteen", "the sixteenth installation"),
    ("unit seventeen", "the seventeenth installation"), ("unit eighteen", "the eighteenth installation"),
    ("unit nineteen", "the nineteenth installation"), ("unit twenty", "the twentieth installation"),
    # -- tempdoc 624 scale-corpus: appended 20->25 (append-only).
    ("unit twenty-one", "the twenty-first installation"), ("unit twenty-two", "the twenty-second installation"),
    ("unit twenty-three", "the twenty-third installation"), ("unit twenty-four", "the twenty-fourth installation"),
    ("unit twenty-five", "the twenty-fifth installation"),
]
_SEM_QUAL_DE = [
    ("Einheit eins", "die erste Anlage"), ("Einheit zwei", "die zweite Anlage"),
    ("Einheit drei", "die dritte Anlage"), ("Einheit vier", "die vierte Anlage"),
    ("Einheit fünf", "die fünfte Anlage"), ("Einheit sechs", "die sechste Anlage"),
    ("Einheit sieben", "die siebte Anlage"), ("Einheit acht", "die achte Anlage"),
    ("Einheit neun", "die neunte Anlage"), ("Einheit zehn", "die zehnte Anlage"),
    ("Einheit elf", "die elfte Anlage"), ("Einheit zwölf", "die zwölfte Anlage"),
    ("Einheit dreizehn", "die dreizehnte Anlage"), ("Einheit vierzehn", "die vierzehnte Anlage"),
    ("Einheit fünfzehn", "die fünfzehnte Anlage"), ("Einheit sechzehn", "die sechzehnte Anlage"),
    ("Einheit siebzehn", "die siebzehnte Anlage"), ("Einheit achtzehn", "die achtzehnte Anlage"),
    ("Einheit neunzehn", "die neunzehnte Anlage"), ("Einheit zwanzig", "die zwanzigste Anlage"),
    # -- tempdoc 624 scale-corpus: appended 20->25 (append-only), index-aligned with _SEM_QUAL above.
    ("Einheit einundzwanzig", "die einundzwanzigste Anlage"), ("Einheit zweiundzwanzig", "die zweiundzwanzigste Anlage"),
    ("Einheit dreiundzwanzig", "die dreiundzwanzigste Anlage"), ("Einheit vierundzwanzig", "die vierundzwanzigste Anlage"),
    ("Einheit fünfundzwanzig", "die fünfundzwanzigste Anlage"),
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
    # -- tempdoc 624 scale-corpus: appended 12->21 (append-only), index-aligned with _SEM_TYPE.
    ("Kornspeicher", "Getreidelager"), ("Werft", "Schiffsbauanlage"),
    ("Brauerei", "Bierhaus"), ("Gerberei", "Lederwerk"),
    ("Münzstätte", "Prägeanstalt"), ("Schmiede", "Eisenwerkstatt"),
    ("Gewächshaus", "Pflanzenhalle"), ("Kapelle", "kleines Gotteshaus"),
    ("Windmühle", "Getreidemahlturm"),
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
    # -- tempdoc 624 scale-corpus: appended 26->44 (append-only), index-aligned with _SEM_PLACE.
    ("Kalksteinhöhlen", "unterirdische Hohlräume"), ("Eisenbrücke", "Flussübergang aus Metall"),
    ("Überschwemmungsdorf", "tiefliegende Siedlung am Fluss"), ("terrassierte Hanglage", "gestufte Böschungsflächen"),
    ("stillgelegter Rangierbahnhof", "aufgegebenes Verschiebegelände"), ("Kreidehügel", "weiße Steilhänge"),
    ("Gezeitenwatt", "schlammige Untiefen"), ("Grenzposten", "Kontrollpunkt am Übergang"),
    ("verlassene Rollbahn", "ungenutztes Landefeld"), ("unterirdische Zisterne", "verborgener Wasserspeicher"),
    ("Wrackbucht", "Einbuchtung mit versunkenen Schiffen"), ("Thermalquellenbecken", "heiße Wassermulde"),
    ("Sandsteinfelsen", "rötliche Steilkante"), ("Fähranleger", "Übersetzstelle für Boote"),
    ("eingestürzter Schacht", "verschüttete Grabungsstätte"), ("sonniger Aussichtspunkt", "sonnenzugewandte Terrassenkante"),
    ("brackige Lagune", "salzige Meeresbucht"), ("verfallener Wachturm", "aufgegebener Beobachtungsposten"),
]


def _max_semantic_chains(lang="en"):
    """The largest gold-chain count for which every chain's (type, place, qualifier) index-triple
    is distinct — hence its synonym query identifies exactly one head.

    `_sem_for`'s gold branch assigns chain ``g`` the triple ``(g % T, g % P, g % Q)``. By the CRT
    that combined residue map is injective on ``[0, lcm(T, P, Q))``, so ``lcm`` is the EXACT ceiling.
    Tempdoc 624 scale-corpus: this replaces the old ``len(places)`` cap, which predated the query
    referencing the *full* triple — back then only the place index needed to be unique per gold, so
    the ceiling was the place-pool size (26). The rendered query now disambiguates a head by all
    three synonyms (`_render_prose`: "the {type} in the {place}, {qual}"), so two gold chains sharing
    one descriptor axis (e.g. the same place at ``g`` and ``g+P``) still differ on the other two and
    stay unambiguous — the true ceiling is the triple period lcm(T, P, Q), an order of magnitude
    larger, which is what lets a scale corpus carry hundreds of distinct queries.

    Tempdoc 624 scale-corpus (pool-growth follow-up): pools are now 21 types x 44 places x 25
    quals = 23100 triples, chosen pairwise-coprime (21=3*7, 44=2^2*11, 25=5^2) so
    lcm(21, 44, 25) == 23100 == the full triple-space size — no combination is wasted."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    return math.lcm(len(types), len(places), len(quals))


def _gold_descriptor_reservations(n_chains, lang="en"):
    """The (type_idx, place_idx, qual_idx) index-triples the gold chains in a `generate()` call
    will occupy — mirrors `_sem_for`'s gold branch exactly. Used to EXCLUDE those combinations
    from the distractor draw (tempdoc 624 T.1), so a distractor can never reproduce a gold
    descriptor by construction rather than merely being caught after the fact by
    `corpus_certify.descriptor_collision_report`."""
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    return {(g % len(types), g % len(places), g % len(quals)) for g in range(n_chains)}


def _sem_for(idx, rng, *, gold, lang="en", exclude=None):
    """Build a (doc_noun, query_noun, doc_place, query_place, doc_qual, query_qual) tuple.

    Gold chains get a deterministic (type, place, qualifier) triple cycled by index. The full
    triple carries uniqueness across gold chains within one `generate()` call — the rendered query
    references all three synonyms, so `generate()`'s semantic-mode cap is the triple-injectivity
    period `lcm(T, P, Q)` (`_max_semantic_chains`), not the place-pool size alone.

    Distractors draw UNIFORMLY AT RANDOM from the full type x place x qualifier space,
    EXCLUDING any index-triple already reserved by a gold chain this call (``exclude`` — see
    `_gold_descriptor_reservations`) via rejection sampling: ``exclude`` holds at most
    `n_chains` (<=26) entries against a pool of `len(types) * len(places) * len(quals)`
    (thousands of combinations with the qualifier axis), so this terminates in a handful of
    draws even in the worst case. ``lang`` selects the English or German synonym pools.
    """
    types = _SEM_TYPE_DE if lang == "de" else _SEM_TYPE
    places = _SEM_PLACE_DE if lang == "de" else _SEM_PLACE
    quals = _SEM_QUAL_DE if lang == "de" else _SEM_QUAL
    if gold:
        ti, pi, qi = idx % len(types), idx % len(places), idx % len(quals)
    else:
        reserved = exclude or set()
        while True:
            ti = rng.randrange(len(types))
            pi = rng.randrange(len(places))
            qi = rng.randrange(len(quals))
            if (ti, pi, qi) not in reserved:
                break
    t, p, q = types[ti], places[pi], quals[qi]
    return (t[0], t[1], p[0], p[1], q[0], q[1])


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
                # head doc: German descriptor (sem[0]/sem[2]/sem[4]); the query references it
                # by German SYNONYMS (sem[1]/sem[3]/sem[5]) → grep fails, multilingual dense bridges.
                body = (f"Standort: {sem[0]}, {sem[2]}, {sem[4]}. "
                        f"Das Objekt {ents[i]} ist mit {ents[i+1]} verknüpft. ")
                title = f"Standort {sem[0]}, {sem[2]}, {sem[4]}"
            else:
                body = f"Das Objekt {ents[i]} ist mit {ents[i+1]} verknüpft. "
                title = f"Über {ents[i]}"
        elif sem and i == 0:
            # head doc: surface descriptor (doc_noun/doc_place/doc_qual) + name + link
            body = (f"The {sem[0]} in the {sem[2]}, {sem[4]}, designated {ents[i]}, "
                    f"{rel[1]} {ents[i+1]}. ")
            title = f"The {sem[0]} in the {sem[2]}, {sem[4]}"
        else:
            body = f"The {ents[i]} {rel[1]} {ents[i+1]}. "
            title = f"The {ents[i]}"
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    if lang == "de":
        docs.append((last.lower(), f"Über {last}",
                     _pad(f"{last} ist mit dem Wert {attr} verbunden. ", target_words)))
        if sem:
            # reference the head by its German synonym descriptor (sem[1]/sem[3]/sem[5]), NOT its name
            q = (f"Folgt man den Verknüpfungen ausgehend vom Standort {sem[1]}, {sem[3]}, {sem[5]}, "
                 f"mit welchem Wert ist die letzte Entität verbunden?")
        else:
            q = (f"Folgt man den Verknüpfungen ausgehend von {ents[0]}, "
                 f"mit welchem Wert ist die letzte Entität verbunden?")
    else:
        docs.append((last.lower(), f"The {last}", _pad(f"{last} is associated with {attr}. ", target_words)))
        # head reference: SYNONYM descriptor (semantic) or the verbatim name (lexical)
        head_ref = f"the {sem[1]} in the {sem[3]}, {sem[5]}" if sem else ents[0]
        phrase = head_ref
        for i in range(len(ents) - 1):
            phrase = f"{rels[i % len(rels)][2]} {phrase}"
        q = f"What is the value associated with {phrase}?"
    evidence = [e.lower() for e in ents]
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": evidence}


def _render_code(ents, attr, target_words, idx, sem=None):
    """Render a chain as code files: fn e0 calls e1 calls ... returns attr. Multi-hop = call trace.

    If ``sem`` is set, the head function carries its purpose as a descriptor comment
    (sem[0]/sem[2]/sem[4]) and the QUERY references it via SYNONYMS (sem[1]/sem[3]/sem[5])
    without naming the function — so grep/pure-BM25 fail at the entry and dense must bridge
    semantically.
    """
    docs = []
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head doc: descriptor in the TITLE + a module docstring (the high-signal fields
            # dense embeds), mirroring the prose member — sem[0]/sem[2]/sem[4] (doc side) so the
            # query's sem[1]/sem[3]/sem[5] synonyms stay zero-overlap (grep-defeating).
            title = f"the {sem[0]} in the {sem[2]}, {sem[4]}"
            body = (f'"""This module concerns the {sem[0]} in the {sem[2]}, {sem[4]}."""\n'
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
             f"{sem[1]} in the {sem[3]}, {sem[5]}?")
    else:
        q = f"What value does the function {ents[0].lower()}() ultimately return when called?"
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


def _render_tabular(ents, attr, target_words, idx, sem=None):
    """Render a chain as table rows requiring a join across docs.

    If ``sem`` is set, the head table carries a descriptor caption (sem[0]/sem[2]/sem[4]) and
    the QUERY references it via SYNONYMS (sem[1]/sem[3]/sem[5]) without naming the head entity
    — so grep/pure-BM25 fail and dense must bridge semantically.
    """
    docs = []
    for i in range(len(ents) - 1):
        if sem and i == 0:
            # head table: descriptor in the TITLE + a leading caption (high-signal), mirroring
            # the prose member — doc-side sem[0]/sem[2]/sem[4] keeps the query's
            # sem[1]/sem[3]/sem[5] zero-overlap.
            title = f"the {sem[0]} in the {sem[2]}, {sem[4]}"
            caption = f"Table for the {sem[0]} in the {sem[2]}, {sem[4]}.\n"
        else:
            title = f"table_{ents[i].lower()}"
            caption = ""
        body = (f"{caption}| entity | linked_to |\n|---|---|\n| {ents[i]} | {ents[i+1]} |\n\n" + _FILLER)
        docs.append((ents[i].lower(), title, _pad(body, target_words)))
    last = ents[-1]
    body = (f"| entity | attribute |\n|---|---|\n| {last} | {attr} |\n\n" + _FILLER)
    docs.append((last.lower(), f"table_{last.lower()}", _pad(body, target_words)))
    if sem:
        q = (f"In the records for the {sem[1]} in the {sem[3]}, {sem[5]}, following the links, "
             f"what attribute is recorded for the final entity?")
    else:
        q = f"Following the links starting from {ents[0]}, what attribute is recorded for the final entity?"
    return docs, {"query": q, "answer": attr, "question_type": f"{len(ents)-1}_hop", "evidence_ids": [e.lower() for e in ents]}


# Degraded-scan defaults (tempdoc 624 §T.2 confidence pass): a live probe against
# Claude Code's own `Read` tool found a plain rendered scan is read correctly via
# multimodal vision (no block at all), but THIS band -- small font, low contrast,
# Gaussian blur, a several-degree rotation, and salt-and-pepper noise -- made `Read`
# correctly report it could see text but not read it clearly, and decline to answer.
# Tuned to defeat a casual multimodal read while (intended to) remain within reach of
# the production Tika/VLM extraction path -- the real-ingest half of this is the one
# unverified assumption named in §T.2 (item 7 of the confidence pass).
_SCAN_DEFAULTS = {
    "font_size": 13,
    "bg_gray": 210,
    "text_gray": 70,
    "blur_radius": 1.3,
    "rotation_deg": 6.5,
    "noise_ratio": 0.08,
}


# Sanity ceilings for `render_scan_image` (tempdoc 624 confidence-pass follow-up):
# unbounded width/font_size/text-length let `height` grow without limit before the
# PIL `Image` is allocated -- a latent resource-exhaustion risk if this function is
# ever reused with less-trusted input. Calibrated against the largest real caller
# (`635-corpora/synth-scan-v1`, doc_words=520): worst committed doc is 3,888 chars
# of title+text, rendered at the only width/font_size ever passed in this codebase
# (900px / 13pt) to a pre-rotation page height of 580px. Each ceiling below carries
# ~10x headroom over that real maximum -- generous enough that no existing caller
# will ever come close, while still bounding worst-case memory allocation.
MAX_SCAN_TEXT_CHARS = 40_000
MAX_SCAN_WIDTH_PX = 9_000
MAX_SCAN_FONT_SIZE = 130
MAX_SCAN_HEIGHT_PX = 6_000


class ScanRenderLimitExceeded(ValueError):
    """Raised by ``render_scan_image`` when an input would allocate an unreasonably
    large PIL Image (oversized text, width, font size, or the resulting page height)."""


def render_scan_image(text, *, width=900, font_size=13, bg_gray=210, text_gray=70,
                       blur_radius=1.3, rotation_deg=6.5, noise_ratio=0.08, seed=0) -> bytes:
    """Render ``text`` as a synthetic degraded scanned-page PNG (returns PNG bytes).

    Word-wraps onto a plain page, then applies (in order) a rotation, a Gaussian
    blur, and salt-and-pepper noise -- the degradation band confirmed live against
    Claude Code's own `Read` tool (see ``_SCAN_DEFAULTS``). Deterministic for a
    given ``seed`` (noise placement is the only randomized step).

    Raises ``ScanRenderLimitExceeded`` if ``text``, ``width``, ``font_size``, or the
    resulting wrapped page height would exceed the sanity ceilings above -- rather
    than silently truncating or allocating an unbounded image.
    """
    try:
        from PIL import Image, ImageDraw, ImageFilter, ImageFont
    except ImportError:
        raise ImportError(
            "Pillow is required for the 'scan' corpus axis. "
            "Install with: pip install jseval[scan]"
        )

    if len(text) > MAX_SCAN_TEXT_CHARS:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: text is {len(text)} chars, exceeds the "
            f"{MAX_SCAN_TEXT_CHARS}-char sanity ceiling"
        )
    if width > MAX_SCAN_WIDTH_PX:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: width={width}px exceeds the {MAX_SCAN_WIDTH_PX}px sanity ceiling"
        )
    if font_size > MAX_SCAN_FONT_SIZE:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: font_size={font_size} exceeds the "
            f"{MAX_SCAN_FONT_SIZE}pt sanity ceiling"
        )

    rng = random.Random(seed)
    font = ImageFont.load_default(size=font_size)
    margin = 24
    line_height = font_size + 6
    max_line_width = width - 2 * margin

    probe_img = Image.new("L", (10, 10))
    probe_draw = ImageDraw.Draw(probe_img)
    lines: list[str] = []
    cur = ""
    for word in text.split():
        trial = f"{cur} {word}".strip()
        if probe_draw.textlength(trial, font=font) <= max_line_width:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    if not lines:
        lines = [""]

    height = margin * 2 + line_height * len(lines)
    if height > MAX_SCAN_HEIGHT_PX:
        raise ScanRenderLimitExceeded(
            f"render_scan_image: wrapped page height={height}px ({len(lines)} lines) "
            f"exceeds the {MAX_SCAN_HEIGHT_PX}px sanity ceiling"
        )
    page = Image.new("L", (width, height), color=bg_gray)
    draw = ImageDraw.Draw(page)
    y = margin
    for line in lines:
        draw.text((margin, y), line, fill=text_gray, font=font)
        y += line_height

    page = page.rotate(rotation_deg, expand=True, fillcolor=bg_gray, resample=Image.BICUBIC)
    page = page.filter(ImageFilter.GaussianBlur(radius=blur_radius))

    pixels = page.load()
    w, h = page.size
    n_noise = int(w * h * noise_ratio)
    for _ in range(n_noise):
        x = rng.randrange(w)
        y2 = rng.randrange(h)
        pixels[x, y2] = 255 if rng.random() < 0.5 else 0

    buf = io.BytesIO()
    page.convert("RGB").save(buf, format="PNG")
    return buf.getvalue()


def render_scan_page(doc_id: str, title: str, text: str, *, degrade: dict | None = None) -> bytes:
    """Render one document's (title, text) as a degraded scan-page PNG, deterministic
    per ``doc_id`` (a stable SHA-256 seed, not the per-process-randomized `hash()`).

    Called at MATERIALIZE time (`corpus_build.build_golden`), not generation time --
    the ``axis="scan"`` corpus member's committed *source* (`docs.jsonl`) stays plain
    ground-truth text, identical in shape to every other axis; the image is a derived,
    regenerable *artifact* (same doc_id + text -> same PNG, always), never persisted in
    source control (tempdoc 624 §T.2 -- keeps the "single source -> two projections"
    pattern `corpus_build.py` already establishes: `datasets/` is gitignored precisely
    because materialized artifacts are always reconstructable from the committed source).
    """
    page_text = f"{title}\n\n{text}" if title else text
    # `hash()` is per-process-randomized (PEP 456) unless PYTHONHASHSEED is pinned,
    # which this repo never does (see the axis_offset comment in generate() below) --
    # a SHA-256 digest of the doc id is stable across processes instead.
    seed = int(hashlib.sha256(doc_id.encode("utf-8")).hexdigest(), 16) % (2**32)
    params = dict(_SCAN_DEFAULTS)
    if degrade:
        params.update(degrade)
    return render_scan_image(page_text, seed=seed, **params)


def materialize_doc_entry(doc: dict, type_axis: str | None) -> dict:
    """Build one document's `materialize.materialize()` input entry, applying the
    axis-aware scan-rendering decision exactly once (tempdoc 624 follow-up).

    `doc` is a plain BEIR-shape dict (`_id`/`title`/`text`) — a `docs.jsonl` source
    line or a golden/mixed `corpus.jsonl` line. When `type_axis == "scan"`, attaches a
    base64-encoded degraded-scan PNG (via `render_scan_page`) as `image_b64`, so
    `materialize.materialize()` writes a `.png` artifact instead of a `.txt`.

    This is the ONE place the `type_axis == "scan"` check + `render_scan_page` call are
    made. Both `corpus_build.build_golden` (materializing `datasets/golden/<name>/
    corpus-dir/`) and `ingest._materialize_into` (materializing an eval run's ingest
    directory from `corpus.jsonl`) call this helper instead of each re-implementing the
    check — a second independent copy is exactly how the axis got dropped silently on
    one of the two paths the first time.
    """
    entry = {"_id": doc["_id"], "title": doc.get("title", ""), "text": doc["text"]}
    if type_axis == "scan":
        png_bytes = render_scan_page(doc["_id"], doc.get("title", ""), doc["text"])
        entry["image_b64"] = base64.b64encode(png_bytes).decode("ascii")
    return entry


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
        # Cap at the (type, place, qualifier) triple-injectivity period, not the place-pool size:
        # the query disambiguates a gold head by the full synonym triple, so uniqueness holds up to
        # lcm(T, P, Q) chains (tempdoc 624 scale-corpus; see `_max_semantic_chains`).
        n_chains = min(n_chains, _max_semantic_chains(lang))
    # tempdoc 624 T.1: the exact (type, place, qualifier) index-triples the gold chains below
    # will occupy, so the distractor draw can EXCLUDE them — a gold/distractor descriptor
    # collision becomes structurally impossible rather than merely detected after the fact.
    gold_reserved = _gold_descriptor_reservations(n_chains, lang) if sem_active else None

    def render(e, a, sem):
        # `scan` reuses prose's text generation verbatim -- the axis only changes how a
        # document is later MATERIALIZED (`corpus_build.build_golden` renders each doc's
        # text as a degraded scan-page PNG at build time via `render_scan_page`), not how
        # its ground-truth text is composed. `docs.jsonl` for a scan-axis corpus is
        # therefore identical in shape to a plain prose source (tempdoc 624 §T.2).
        if axis in ("prose", "scan"):
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
    # same way, NOT referenced by any query → hard negatives. `exclude=gold_reserved` (tempdoc
    # 624 T.1) keeps a distractor's descriptor draw disjoint from every gold chain's, so a
    # distractor can never be textually indistinguishable from the query's actual answer head.
    n_distract = int(len(all_docs) * distractor_ratio)
    made = 0
    while made < n_distract:
        ents, attr = _chain(rng, hops, counter)
        sem = _sem_for(0, rng, gold=False, lang=lang, exclude=gold_reserved) if sem_active else None
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

    # newline="\n": 635 gold sources are git-committed (and flow verbatim into the 707
    # fabricated-* commitments) — platform-default CRLF here re-introduces the
    # unmatchable-manifest bake-in the 2026-07-14 repair removed (independent-review find).
    with (out_dir / "docs.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for d in all_docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    (out_dir / "queries.json").write_text(
        json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
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
    }, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
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

    A SECOND cross-process non-determinism source (found empirically: the three "deterministic
    across processes" tests pass when the pytest invocation's cwd happens to be `scripts/jseval/`,
    but fail reliably -- ``docs.jsonl``/``queries.json`` mismatched -- when invoked from the repo
    root, the realistic invocation form). Root cause: `sys.executable -c <script>` gives the child
    `sys.path[0] = ''` (its own cwd), searched *before* site-packages. Without an explicit `cwd`,
    the child inherits the PARENT's cwd. If that cwd doesn't directly contain a `jseval/`
    subdirectory (true for any cwd other than `scripts/jseval/` itself), import resolution falls
    through to whatever `jseval` happens to be `pip install -e`'d globally -- which, on a dev
    machine with a separate long-lived checkout on `PATH`/site-packages (`pip show jseval` ->
    `Editable project location: ...`), can be a *different, stale physical checkout* containing the
    pre-tempdoc-664 `hash(axis)` bug. Both spawned subprocesses then run that stale buggy code
    (not the current worktree's, already-fixed `corpus_generate.py`), and since `hash()` is
    per-process-randomized, they diverge -- reproducing the exact pre-664 symptom despite this
    file's own fix. Pinning `cwd` to this file's OWN package directory (`Path(__file__).parent`'s
    parent -- the dir that directly contains `jseval/`) makes the child's `sys.path[0]` shadow any
    ambient site-packages install, guaranteeing the subprocess imports the SAME code this function
    itself is running from, regardless of the caller's cwd.

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
    # The directory that directly contains `jseval/` (this file is
    # `<pkg_root>/jseval/corpus_generate.py`) -- passed as the subprocess's `cwd` so its
    # `sys.path[0] = ''` resolves to THIS package, not an ambient site-packages install.
    pkg_root = Path(__file__).resolve().parent.parent

    for out in (Path(out1), Path(out2)):
        result = subprocess.run(
            [sys.executable, "-c", "import sys; " + script, str(out), *args],
            capture_output=True, text=True, timeout=timeout, cwd=pkg_root,
        )
        if result.returncode != 0:
            return {"ok": False, "error": f"regeneration subprocess failed: {result.stderr[-500:]}"}

    mismatches = [
        fname for fname in ("docs.jsonl", "queries.json")
        if (Path(out1) / fname).read_text(encoding="utf-8") != (Path(out2) / fname).read_text(encoding="utf-8")
    ]
    return {"ok": True, "mismatched_files": mismatches}
