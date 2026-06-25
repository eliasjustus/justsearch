---
title: Native multilingual by construction — the language-diversity invariant and its enforcement design
type: tempdocs
status: active
created: 2026-06-15
updated: 2026-06-15
---

# 581 — Native multilingual, no per-language levers

> **Nature of this doc.** A *design invariant* (a standing constraint), not a feature
> plan. It states the principle that the engine must be language-diverse **natively** —
> good retrieval in any language with **zero per-language engineering or maintenance** —
> and uses that principle as a *classifier* over the search-quality backlog: it
> structurally rejects a class of "cheap win" levers that quietly smuggle in per-language
> work. Spun out of tempdoc 580 (the relevance-freeze reconciliation), which surfaced
> several per-language levers as "open" without recognizing they conflict with this goal.
> Per `rule:tempdocs-are-dated-history`: reflects 2026-06-15 `main`.

## 1. The invariant

**The engine is multilingual by construction, and stays that way without per-language
work.** Adding support for, or improving quality in, a new language must require **no new
per-language artifact to author or maintain** — no language-specific stemmer, analyzer,
stopword list, spelling dictionary, or hand-curated synonym set. A contributor must never
have to "do the German work," then "the French work," then "the Chinese work."

This is a deliberate product stance: the cost of per-language levers is **O(languages)
maintenance forever**, and it degrades silently (a stemmer nobody updates, a locale field
nobody routes to). The engine trades a few points of monolingual peak for **uniform,
zero-maintenance coverage across all languages at once.**

## 2. This is already the architecture (it's not aspirational)

The current pipeline is built almost entirely from language-agnostic primitives:

| Layer | Primitive | Why it's language-agnostic |
|---|---|---|
| Tokenization | **ICU tokenizer** | Unicode segmentation rules, not per-language token rules |
| Normalization | **NFC** + lowercase | Unicode-level, script-neutral |
| Lexical | **BM25** | Term-frequency statistics; no linguistic model of any language |
| Dense | **gte-multilingual-base** (768d) | One multilingual encoder covers all languages |
| Learned sparse | **opensearch-neural-sparse-encoding-multilingual** (SPLADE) | One multilingual model |
| Reranking | **gte-multilingual-reranker-base** | One multilingual cross-encoder |

The only per-language-shaped thing in the pipeline is the optional `SynonymGraph`, and it
is not required for baseline quality. **(Source-verified 2026-06-15 — see §8. The reality
is even stronger: that `SynonymGraph` is currently empty and inert, and the *queried*
`content` field does not use it at all, so the multilingual quality below is achieved with
effectively zero per-language processing active.)**

**The principle is measured, not assumed.** The register's **F-007** found German, French,
and Chinese each retain **90%+** of their isolated retrieval quality when mixed into one
index with English — no per-language tuning. MIRACL de/fr/zh all score well through the
*same* pipeline (de full 0.734, fr full 0.706, zh full 0.691; register Canonical Baselines).
That cross-language robustness **is the dividend** of building from multilingual encoders
instead of per-language components.

## 3. The classifier — three buckets, not two

The invariant cuts the lever space into three, and the middle bucket is the subtle one:

**A. Per-language COMPONENTS — REJECTED.** Anything that requires a *distinct authored
artifact per language*. These violate the invariant by construction:
- Language-specific **stemmers** (Porter / Snowball / KStem) — one algorithm per language.
- **Locale-aware analyzer fields** routed by detected language (`content_de` with a German
  analyzer).
- Per-language **stopword lists**, **spelling dictionaries**, hand-curated **synonym sets**.

**B. Language as a SIGNAL to a uniform policy — ALLOWED.** Using *detected language* as one
**input feature** to a single, language-agnostic policy is fine, because it adds no
per-language artifact to maintain. Example: the general recipe-weight system (580 §10) may
take language as one feature of its fit/learned weight function — that's a uniform function
reading a signal, **not** a hand-tuned table with a row per language. The test is:
*does adding language N require a human to author something for language N?* If no, it's
bucket B and it's allowed.

**C. Language-agnostic levers — FAIR GAME.** Anything that has nothing to do with language:
quantization, fusion strategy, corpus-length-regime weighting, chunking, ANN parameters.
These are evaluated on their own merits, untouched by this invariant.

## 4. Reclassification of the surfaced "open" levers (the actionable part)

Tempdoc 580 + the register reconciliation (commit `73cf332d5`) listed several levers as
"still open." Through this invariant:

| Lever | Bucket | Disposition under the invariant |
|---|---|---|
| **FW-006 — English (per-language) stemming** | A | **WON'T-DO.** Per-language by nature; also separately blocked by the fuzzy-correction incompatibility (tempdoc 223). |
| **Q-004 — locale-aware BM25 routing** (`content_de`/`content_en`) | A | **WON'T-DO.** Routing to per-language analyzer fields *is* the per-language maintenance the invariant forbids. (Corrects 580 §2.4 / the register's "a cheap win" framing — it is not a win under this stance.) |
| Per-language **synonym** tuning | A | **WON'T-DO** as a per-language program. The optional `SynonymGraph` stays optional and is not to be grown into curated per-language lists. |
| **FW-002 — spell correction** (Lucene `DirectSpellChecker`) | mostly C | **SURVIVES — decide on own merits.** It builds its candidate dictionary **from the index's own terms** by edit distance, with **no per-language dictionary or config** — so it does *not* violate the invariant. Caveat: char-edit-distance is weaker for CJK / agglutinative scripts (a *quality* limit, not a *maintenance* one). Do not lump it with stemming. |
| **FW-008 — Int8 vector quantization** | C | Untouched by this invariant; open on its own merits (nDCG cost unmeasured). |
| General **recipe-weight system** (FW-001 successor) | C (may read B) | Untouched; keys on corpus regime / doc length and *may* read language as a bucket-B signal. |

**Net:** the invariant closes **FW-006**, **Q-004**, and per-language synonyms as won't-do;
it leaves spell correction and the language-agnostic levers open.

## 5. The honest boundary (when the invariant could be revisited)

The invariant is a strong default, not a law of physics. It would be worth re-opening only if:
- A measured, **large** monolingual gap appears that multilingual models cannot close (e.g.,
  a script the encoders handle poorly), **and**
- the fix can be expressed as **one uniform mechanism** rather than an O(languages) program.

Absent both, per-language levers stay out. "Language N retrieval is a bit weak" is **not**
sufficient cause to start the per-language treadmill — improve the multilingual encoder or
the language-agnostic levers instead.

## 6. Relationship to other docs

- **Tempdoc 580** — surfaced FW-006/Q-004 as open levers; this doc reclassifies them. 580's
  general-recipe-weight thesis (§10) is compatible: it may use language as a *bucket-B
  signal*, not a per-language component.
- **Register `search-quality-register.md`** — F-007 is the measured evidence; FW-006/FW-008/
  Q-004 rows were reconciled in `73cf332d5`. **Follow-up:** record this invariant as a
  register **Decision D-003** (so future agents stop re-surfacing stemming/locale-routing as
  "cheap wins"), and reclassify Q-004 from Open Question → won't-do with a pointer here.
- **`docs/explanation/23-search-pipeline-overview.md`** — the ingestion/retrieval stages
  already embody the invariant (ICU, multilingual encoders); no change needed, optional
  one-line pointer.

## 7. Status / next steps

- [x] Invariant stated; backlog reclassified (§4).
- [x] **Source-verbatim verification of the invariant's load-bearing claims** (§8, 2026-06-15
      takeover). All architecture claims in §2 confirmed against `main`; the per-language
      surface is even more inert than §2 stated (empty synonym files, dead `content_{en,de}`
      fields). The reclassification (§4) survives verification and is *strengthened*.
- [x] Drafted register **D-003** wording (§11.A) and the Q-004 flip. **User decision
      2026-06-15: HOLD — keep as a tempdoc-only proposal, do not edit the canonical register
      yet.** Ready to land verbatim when the user gives go-ahead.
- [x] Surfaced the dead per-language scaffolding (§9.1) and its disposition options (§11.B).
      **User decision 2026-06-15: tempdoc-only for now — do not touch the SSOT catalogs.**
- [x] Surfaced the prose-only → structural-gate enforcement option (§9.3 / §11.C).
      **User decision 2026-06-15: tempdoc-only for now — no gate built.**
- [ ] (optional / deferred) One-line pointer from explanation/23 to this invariant —
      deferred with the canonical-register hold above (a canonical-doc edit).

- [x] **Long-term enforcement design theorized (§12, 2026-06-15).** The invariant is mapped
      onto tempdoc 576's enforcement-strength ladder: analysis → **rung 1 (unrepresentable)**
      via a closed analyzer-provider enum + collapse of the per-locale machinery; retrieval →
      **rung 1 channel + rung 5 judgment**; reasoning → an **ADR** with D-003 as its index.
      The three §11 held items are unified as the three rungs of one design. No code changed.

> **Net of the 2026-06-15 takeover:** the invariant was source-verified and *strengthened*
> (§8–§10), then given a **long-term enforcement architecture** (§12). All canonical/code
> actions it implies (the rung-1 collapse, the rung-2 gate, D-003/ADR, explanation/23 pointer)
> are **recorded as a ready, sequenced design and intentionally held** at the user's direction.
> Nothing outside this tempdoc was changed. §12 is the target structure; §11 is the near-term
> pick-up list whenever the hold is lifted.

---

## 8. Source-verbatim verification of the invariant (2026-06-15 takeover)

Per `rule:audit-driven-fixes-need-test` / `rule:critical-analysis-pass`, the §1–§4 claims
were re-checked verbatim against `main` rather than trusted. Result: **every architecture
claim holds, and the per-language surface is *more* inert than §2 claimed.**

| §2 / §4 claim | Verified at | Verdict |
|---|---|---|
| ICU tokenizer + NFC + lowercase, language-agnostic core | `SsotAnalyzerRegistry.createIcuAnalyzer` (`adapters-lucene/.../analyzers/SsotAnalyzerRegistry.java:158-172`): `ICUTokenizer → ICUNormalizer2Filter → LowerCaseFilter → (optional) SynonymGraphFilter` | **CONFIRMED.** The pipeline core is exactly as described. |
| Multilingual encoders (dense/sparse/reranker) | `models/onnx/gte-multilingual-base/`, `models/splade/...multilingual...`, `models/onnx/reranker/` + register Canonical Baselines | **CONFIRMED.** gte-multilingual-base covers **~70–75 languages** (Alibaba mGTE), not "all languages" — a finite, model-bounded set (see §10, counterargument 2). |
| No stemmer / stopword / spell-checker authored in code | `grep Snowball\|Porter\|KStem\|Stemmer\|Stopword\|StopFilter` over `**/*.java` → only `BioTagDecoder` (NER subword, unrelated) + one test. `grep DirectSpellChecker\|SpellChecker` → **no production code** (only docs/register). | **CONFIRMED.** FW-006 and FW-002 are genuinely unbuilt. |
| F-007 90%+ cross-language retention; MIRACL de 0.734 / fr 0.706 / zh 0.691 | register `search-quality-register.md` (F-007; Canonical Baselines) | **CONFIRMED** as the register's own measured evidence (note its confidence: MIRACL rows A; the mixed-corpus retention number is **B-confidence**, subsample-based — directionally strong, not airtight; see §10 counterargument 3). |
| Q-004: `content_{de,en}` indexed but never routed at query time | `TextQueryOps` builds a `DisjunctionMaxQuery` over `content`+title+author+entity only (`combineMultiField`, `TextQueryOps.java:126,171-196`); no `content_de`/`content_en` disjunct | **CONFIRMED and extended** — see §9.1: those fields are not just un-queried, they are **never written**. |

**Net:** the invariant is not aspirational hand-waving — it is the measured-and-verified
shape of the current pipeline. Nothing in §1–§5 needed retraction; §2's "the only
per-language thing is the optional SynonymGraph" needed *sharpening* (it is inert), done
above and detailed next.

---

## 9. Findings that sharpen the invariant (takeover investigation)

### 9.1 The entire per-language surface is dead scaffolding — three layers deep

§2 says the lone per-language-shaped thing is "the optional `SynonymGraph` … not required
for baseline quality." Verification found it is **inert on three independent layers**, which
is materially stronger than "optional":

1. **The synonym files are empty.** `SSOT/catalogs/synonyms.en.v1.txt` and `synonyms.de.v1.txt`
   (and their `config/synonyms_{en,de}.txt` sources) contain **only header comments and a
   commented-out example** — zero actual synonym rows. The prebuilt FSTs
   (`SSOT/artifacts/analyzers/synonyms.{en,de}.v1.fst`) are built from these empty inputs.
2. **The synonym filter is only wired to never-used analyzers.** `loadSynonyms()` returns
   `null` for locale `*` (`SsotAnalyzerRegistry.java:174-177`), so the `content_all`
   analyzer — and the `content` field, whose `"analyzer":"icu"` alias resolves to
   `content_all` — get **no `SynonymGraphFilter` at all**. Synonyms attach only to the
   `en`/`de` locale analyzers, i.e. only to the `content_en`/`content_de` fields.
3. **Those fields are never written or queried.** `grep content_en\|content_de` over
   `**/*.java` returns **zero production references**. The write path
   (`IndexingDocumentOps`) populates `SchemaFields.CONTENT` / `CONTENT_PREVIEW`, never the
   locale fields (`IndexingDocumentOps.java:142-143`); the read path queries `content`
   (§8). They exist only as rows in `fields.v1.json` (both the root and the classpath copy).

**Consequence for the thesis (positive):** the measured multilingual quality (§2, F-007)
is obtained with **no per-language processing executing anywhere** — the strongest possible
evidence for "multilingual by construction." The dividend is real and is paid entirely by
the multilingual *models*, not by any per-language lever.

**Consequence for the codebase (cleanup):** this is the per-language treadmill *already
started and abandoned* — `en` + `de` exist, `fr`/`zh`/everything-else do not (asymmetric by
construction), and none of it does anything. Under the invariant (and plain YAGNI/AHA) it
is dead weight that should be removed or explicitly frozen. Disposition is a user decision
(§11.B) because it touches the SSOT catalogs (dual-copy sync) and the field schema.

### 9.2 Bucket B is not hypothetical — it already exists

§3's bucket B ("language as a *signal* to a uniform policy") reads as forward-looking, but
the codebase already contains a clean instance: language is **detected by one uniform
script-counting heuristic** (`IndexingDocumentOps.detectLanguage`, `:482-…` — counts
Latin/Cyrillic/Han/Kana/Hangul/Arabic/Devanagari/Greek Unicode blocks; no per-language
profiles), written to `SchemaFields.LANGUAGE`, and exposed as a filter
(`QueryFilterBuilder`). That is exactly bucket B: one detector + one field + one uniform
filter, with **no artifact authored per language**. It is the concrete model the recipe-
weight system (580 §10) should follow if it ever reads language. Recommend §3 cite it.

### 9.3 The "stemmer = authored per-language component" argument is partly imprecise

§3.A lists "language-specific stemmers (Porter / Snowball / KStem) — one algorithm per
language" as if each were a JustSearch-authored artifact. That overstates the maintenance
cost for the languages Lucene already covers: `PorterStemFilter`, `EnglishAnalyzer`, and the
Snowball family (~15 languages) ship *inside Lucene*, upstream-maintained — adding one is
config, not authoring. The honest, stronger reason FW-006/Q-004 are won't-do is **not**
"you'd hand-write a stemmer," it is the *systemic* per-language tax that the routing brings:
(a) detect-language → pick-analyzer-per-language routing logic, (b) a `content_<lang>` field
per language in the schema, (c) per-language eval/validation, (d) **asymmetric coverage**
(Snowball ≈ 15 langs vs the dense models' ~75), and (e) the **fuzzy-correction conflict**
(tempdoc 223 — analyzer-level stemming breaks the zero-hit fuzzy retry because the analyzed
query token diverges in edit distance from the stemmed index term). Recommend §3.A reword to
lead with the *treadmill + 223 conflict*, not the (often false) "author a stemmer" claim.

---

## 10. Critical analysis — is the invariant itself right? (steelman + response)

The takeover brief asks to question assumptions. Three genuine counterarguments, and why
the invariant survives each (with the one scoping fix it needs):

**Counterargument 1 — "zero per-language engineering" is a sleight of hand; the per-language
work is enormous, just done by Alibaba/OpenSearch upstream.** *True, and the doc should say
so plainly.* The invariant is precisely **"no per-language engineering *by JustSearch*"** —
it outsources the O(languages) work to the model vendors and pays for it with a single model
swap (O(1) for us). §1 already implies this ("no new per-language artifact to *author or
maintain*", "a contributor must never have to do the German work"), but the headline "zero
per-language engineering" should be scoped to *in-repo* to be defensible. **Fix:** one-word
scoping in §1 — done in spirit by §8/§10; leave §1 prose, add this footnote.

**Counterargument 2 — "any language" is false; the models cover ~70–75 languages.** *True.*
A language outside the encoders' training set (a low-resource script the models handle
poorly) gets degraded retrieval, and the invariant offers no per-language remedy. **Response:
this is a feature, not a bug** — §5's honest boundary already says the escape hatch is "one
uniform mechanism" (a better multilingual model), explicitly *not* an O(languages) program.
The scoping fix is to say "any language **the multilingual models cover**" rather than "any
language." The bound is ~70–75 languages (gte-multilingual / mGTE), which covers essentially
all languages a desktop-search user is realistically indexing.

**Counterargument 3 — the invariant is most likely to be *wrong* for a single-dominant-
language deployment**, where a monolingual model could beat the multilingual one by more than
"a few points," and §5's "one uniform mechanism" is self-contradictory for a one-language
gap (a fix for one language is inherently language-specific). *Partly fair.* But the
sanctioned remedy is still uniform from the engine's perspective: **swap the deployment's
embedding/rerank model** (a config/model choice, bucket-C/B), not add per-language analyzer
fields. Choosing a German-optimised *model* for a German-only install is O(1) and authored by
nobody; authoring a German analyzer chain is the bucket-A treadmill. So the invariant holds;
§5 could add "…or selecting a better single model for the deployment" as a permitted uniform
move. Evidence caveat: the retention number underpinning "only a few points lost" is
B-confidence (§8) — the invariant is a sound *default*, and §5 already frames revisiting it
behind a *measured large* gap, which is the right bar.

**Verdict:** the invariant is correct and well-grounded. It needs three small scoping edits
(in-repo / model-covered-languages / model-swap-as-uniform-move), and §3.A needs the
reasoning sharpened per §9.3 — none of which change a single disposition in §4.

### 10.1 Should the invariant have teeth? (enforcement-strength)

Today the invariant is **prose-only** (~70% adherence per `tier-register.md`); its only
"teeth" are the register D-003 row future agents are *supposed* to read. Given this
codebase's gate culture (tempdoc 576 enforcement-strength ladder; dozens of `governance/`
gates), the natural upgrade is a **structural gate** that fails the build when a per-language
artifact is (re)introduced — e.g. a non-empty `synonyms.<lang>` / per-language dictionary
file, a Snowball/Porter/stem/stopword component in `analyzers.v1.json`, or a new
`content_<lang>` field. That moves the invariant prose-only → `gate` (~100% adherence) and
makes "don't start the treadmill" *structurally* true instead of advisory. This is the
highest-leverage option but the largest build; flagged as a decision (§11.C), not silently
added — authoring a gate + tier-register row is its own scope.

---

## 11. Open decisions for the user (gated work)

These are the items §7 leaves open because they edit canonical surfaces or are net-new scope.

> **Decision recorded 2026-06-15:** the user elected **tempdoc-only** for all three (A, B,
> C) — keep them as ready, drafted proposals here; do not edit the canonical register, the
> SSOT catalogs, or add a gate yet. The drafts below stand unchanged as the pick-up list.

### 11.A — Proposed register Decision D-003 (confirm wording before I write it)

Drafted for `docs/reference/search-quality-register.md` → **Decisions** section. Flipping
Q-004 (Open Questions → won't-do, pointer here) would land in the same edit.

> **D-003: Native multilingual, no per-language levers — REJECT per-language components**
> - **Choice:** The engine stays multilingual *by construction*. No per-language artifact
>   that a contributor must author or maintain — no language-specific stemmer/analyzer
>   field, stopword list, spelling dictionary, or hand-curated synonym set — is added. Using
>   detected *language as a signal* to a single uniform policy is allowed; language-agnostic
>   levers are unaffected.
> - **Rationale:** Per-language components cost O(languages) maintenance forever and degrade
>   silently; the multilingual model stack (gte-multilingual-base, opensearch-neural-sparse-
>   multilingual, gte-multilingual-reranker) already delivers 90%+ cross-language retention
>   (F-007) and strong MIRACL de/fr/zh through one uniform pipeline. Verified 2026-06-15:
>   the only per-language scaffolding present (empty synonym files, unused `content_{en,de}`
>   fields) is inert. Full reasoning + classifier: tempdoc 581.
> - **Closes:** FW-006 (stemming), Q-004 (locale-aware BM25 routing), per-language synonym
>   programs — all **won't-do**. Leaves FW-002 (spell correction, index-term-based, no
>   per-language dict) and language-agnostic levers (FW-008, recipe weights) open on their
>   own merits.
> - **Revisit when:** a *measured large* monolingual gap appears that a uniform mechanism
>   (better single model) cannot close (581 §5).

### 11.B — Disposition of the dead per-language scaffolding (§9.1)

Options: **(a) Remove** the empty `synonyms.{en,de}.v1.txt` + `config/synonyms_{en,de}.txt`
+ prebuilt FSTs + the `content_{en,de}` fields + `en`/`de` analyzer entries (SSOT dual-copy
sync; field-schema change → fingerprint/pinned-hash tests; **⚠️ forces a reindex — see §13**,
corrected from the earlier "no reindex" claim). **(b) Freeze** in place and add a one-line
"inert, do not grow" comment in the catalogs. **(c) Defer.** Removal best embodies the
invariant; freezing is the lower-risk move (no reindex). Needs a user call before touching SSOT
catalogs. **Note:** `content_all` the *field* is NOT removable — it is a live read surface
(§13). Only its provider changes.

### 11.C — Give the invariant teeth? (§10.1)

Add a structural `governance` gate (per-language-artifact reintroduction → build failure) +
tier-register row, moving the invariant prose-only → `gate`. Highest adherence, largest
build. Decision: build now / file as Future Work / decline.

> **Superseded framing:** §11.A–C list the three held items as *separate* near-term choices.
> §12 (below) is the long-term design that **unifies them into one coherent target**: §11.A
> (D-003) becomes the rung-5 index entry, §11.B (scaffolding removal) is reframed from
> "cleanup" into the rung-1 *enforcement mechanism*, and §11.C (the gate) becomes the rung-2
> *backstop*. Read §12 as the answer to "what should this look like long-term"; §11 stays the
> near-term pick-up list under the user's hold.

---

## 12. The long-term design — climbing the enforcement ladder (2026-06-15 design pass)

> **Brief.** Theorize the *correct long-term structure* for the invariant and its remaining
> work — not short-term fixes, not the smallest thing. General, not implementation-level.
> Investigated first; extends existing substrate (tempdoc 576's ladder, the 530 discipline-
> gate kernel, the SSOT analyzer/field catalogs) rather than inventing. No code changed.

### 12.0 Thesis

The invariant is currently **prose-only — rung 5** on tempdoc 576's enforcement-strength
ladder (~70% adherence; its only "teeth" are a register row agents are *supposed* to read).
That is the wrong rung for a constraint whose entire job is to **stop a recurring
re-litigation** (Q-004 kept resurfacing precisely because nothing structural said no). 576's
governing principle is *"push every invariant as high as its domain allows, and never let it
slide down silently … prefer climbing to rung 1 (make it unrepresentable) over building a
better rung-2 detector."*

Applied here, the correct end-state is **not "add a gate"** (that settles for rung 2 and
repeats 576 §11's own honest-gap trap — shipping the detector but not the impossibility).
It is to **split the invariant into its two sub-domains and place each at the highest rung
its domain actually allows**:

| Sub-domain | The rule | Reachable rung | Mechanism |
|---|---|---|---|
| **Analysis / indexing** | Language is a **forbidden fork-axis** — tokenization/normalization is a pure function of Unicode, never of detected language | **Rung 1 (unrepresentable)** — genuinely occupiable here | Closed provider union + collapse the per-locale analyzer machinery (§12.2) |
| **Retrieval / scoring** | Language is a **permitted read-only signal** to a uniform policy (bucket B), never a per-language authored table (bucket A) | **Rung 1 for the *channel*, rung 5 for the *judgment*** | Single-authority language-signal seam + an honest oracle residue (§12.3) |
| **The reasoning itself** | *Why* per-language levers are rejected; the revisit boundary | **Rung 5 (canonical, not tempdoc)** | An ADR / canonical doc + register D-003 as its index (§12.4) |

This is the same shape 576 §6 walked for tokens (taxonomy at rung 1, contrast-matrix as the
rung-2 backstop): **the structural collapse is the fix; the gate is the backstop.**

### 12.1 What already exists (extend, not replace)

The investigation found the substrate is unusually favorable — rung 1 is *occupiable* here,
which 576's own worked examples often couldn't reach:

- **The analyzer `provider` is an open `string`** (`SSOT/schemas/indexing/analyzers-catalog.schema.json` — `provider: {type: string, minLength: 1}`), constrained only by a runtime `switch` that throws on unknown (`SsotAnalyzerRegistry.java:150-155`). Closing that to a language-agnostic **enum** is a real rung-1 move — a per-language provider becomes a *schema-validation error*, exactly analogous to 576 §3.1's "no `self` placeholder" discriminated union.
- **The per-locale behavior has exactly one seam.** `loadSynonyms(locale)` (`SsotAnalyzerRegistry.java:174-193`) is the *only* place `locale` changes analysis; it returns `null` for `*`. Remove that seam and `locale` is vestigial on content analyzers — there is no per-language analyzer slot left to fill.
- **The 530 discipline-gate kernel** is the home for the backstop, and **`ssot-catalog-sync` is a ready template** (`scripts/governance/gates/ssot-catalog-sync/`): a gate that reads a catalog, walks entries, and fails on out-of-set content, with the changeset/`no-silent-downgrade` protocol already wired. A new catalog-reading gate is a registry entry + enforcer + truth-table, not new infrastructure.
- **The language *signal* already exists, single-sourced** — `detectLanguage` (one uniform script heuristic) → `SchemaFields.LANGUAGE` → filter (§9.2). The bucket-B channel doesn't need building, only *governing*.
- **The model layer is already the "outsourced per-language engineering"** — one multilingual model per stage, language-agnostic at the interface. The invariant's enforcement must **not** touch it; the §5 escape hatch ("swap to a better single model") is a bucket-C model-config change, no per-language slot.

### 12.2 Analysis sub-domain → Rung 1 (make the fork unrepresentable)

The treadmill enters through *representable slots*: an open `provider`, a `locale` dimension
that switches behavior, and expressible `content_<lang>` fields. The rung-1 design removes
the slots rather than patrolling them:

1. **Close the provider set.** The analyzer-catalog schema constrains `provider` to a closed
   union of **language-agnostic** providers (today: `icu`, `keyword` — `icu+synonyms` folds
   out with the synonym seam below). A `snowball` / `stemmer` / per-language-`stopwords`
   provider is then *unrepresentable* in the catalog — a schema error, not a runtime surprise.
2. **Collapse the per-locale analyzer machinery.** Remove the `loadSynonyms(locale)` seam and
   the `locale`-keyed content analyzers; content analysis becomes one locale-invariant
   pipeline (`ICUTokenizer → NFC → lowercase`). The dead `content_{en,de}` fields and `en`/`de`
   analyzer entries go with it. **This reframes §11.B: removing the scaffolding is not
   cleanup — it *is* the rung-1 enforcement.** You cannot start the treadmill because the rail
   it ran on no longer exists. (Hand-curated synonyms are inherently per-language — English
   synonyms ≠ German — so a "global" synonym list is incoherent; the mechanism is removed, not
   globalized. A future *generated*, language-agnostic expansion lever, if ever wanted, would
   be designed fresh as bucket C, not via the per-locale file seam.)
3. **The rung-2 backstop (for the residue rung 1 can't reach).** The provider `switch` lives
   in *code*; nothing stops an agent adding `case "snowball"` to it *and* widening the schema
   enum in the same change. A governance gate (extending `ssot-catalog-sync`) closes that:
   every analyzer `provider` ∈ the closed set, **no** locale-suffixed `content_<lang>` text
   field, **no** non-empty per-language synonym/dictionary file. It *bites by construction*
   (set-membership is computed, not asserted) → genuine rung 2 with positive/negative
   self-test fixtures, not a rung-3 placeholder. Per 576 §5, deleting it or widening the enum
   is a **classified, accountable downgrade**, never silent.

The pairing is deliberate and matches 576's token lesson: **#1–#2 are the fix (rung 1); #3 is
the backstop (rung 2). Shipping only #3 would be the 576 §11 trap** — a detector that makes
the bad state *detectable* instead of *impossible*. The design names rung 1 as the target.

### 12.3 Retrieval sub-domain → Rung 1 channel + an honest rung-5 residue

Bucket B (language-as-signal) cannot be fully mechanized — distinguishing "a uniform function
reading language as one feature" (allowed) from "a hand-tuned table with a row per language"
(forbidden) is **576's Wall 2** (not all behavior is law-projectable). The design is honest
about this rather than pretending a gate can decide it:

1. **Rung 1 — single-authority signal channel.** There is exactly **one** detected-language
   source (`detectLanguage` → `LANGUAGE` field). Any language-dependent retrieval behavior
   *consumes that one signal* — it must not re-detect language or branch on raw script. A thin
   positive-coverage register (the `search-issuance` / `steering-surfaces` single-seam pattern)
   declares the sanctioned consumers, so a *second* language-detection site or a per-language
   routing fork is a build failure. This makes "language entered scoring through an
   un-governed back door" unrepresentable.
2. **Rung 5 — the irreducible judgment.** *"Is this consumer a uniform policy or a per-language
   fork?"* stays an oracle/audit call (the recipe-weight system of 580 §10 is the live test
   case). This is not a gap — 576 is explicit that the ladder *raises the floor within
   declared scope* and does **not** solve projection/discovery (Wall 1/2). D-003 + the
   authoring-time review are the rung-5 teeth here.

### 12.4 The permanent home (the half-life problem, fixed)

The critique (turn 2) named it: *an invariant living only in a tempdoc has the half-life of a
tempdoc.* The ladder-consistent fix separates **reasoning** from **enforcement** and gives
each its right home:

- **Reasoning → canonical.** The classifier (buckets A/B/C), the empirical dividend (F-007 /
  MIRACL), and the revisit boundary (§5) are *durable architecture with an explicit revisit
  condition* — the definition of an **ADR**, not a register footnote. The strongest home is a
  short ADR ("multilingual by construction; no per-language levers") that `explanation/23`
  points to. **Register D-003 becomes the domain-agent-facing *index* into that ADR**, not the
  primary statement. The tempdoc (this doc) stays dated design history per
  `rule:tempdocs-are-dated-history`.
- **Enforcement → the ladder** (§12.2–§12.3). The ~100% guarantee comes from the schema +
  gate + single-seam register, *not* from prose adherence. This is what graduates the invariant
  from "calling it an invariant is overselling" (prose at 70%) to a constraint the system
  actually holds.

The three §11 held items are exactly the three rungs of this home: **D-003 = rung-5 index;
scaffolding removal = rung-1 fix; the gate = rung-2 backstop.** They are one design, not three
chores.

### 12.5 No-silent-downgrade, applied (576 §5)

Once the invariant occupies rungs 1–2, the meta-invariant protects it: re-opening the provider
enum, re-introducing a `locale`-switched analyzer, deleting the backstop gate, or adding a
second language-detection site each becomes a **classified, accountable changeset** — never a
silent slide back to rung 5. This is the property that turns "we wrote it down once" into "the
system keeps holding it," and it is the precise defense against the agent-fleet failure mode
576 §5 names (*an agent widens whatever one-sided hole makes a gate green*).

### 12.6 Honest cost, sequencing, and the trap to avoid

Following 576 §8/§9 discipline (sequence by leverage **and** cost; name the leaky parts):

1. **Rung-1 collapse + closed enum (§12.2 #1–#2)** — the highest-leverage. Touches the SSOT
   catalogs (dual-copy sync via `ssot-catalog-sync`), the analyzer schema, fingerprint/
   pinned-hash tests (`SsotValidator`), and `SsotAnalyzerRegistry`. **⚠️ CORRECTED by §13: this
   DOES force a reindex** — changing the analyzer catalog changes the `analyzer_fp` parity key
   embedded in commit metadata, so existing indices fail `IndexMetadataParityGuard` on open.
   It is therefore re-sequenced to land **last**, behind a schema-version bump, ideally riding
   the next unavoidable reindex (§13). The data claim in §9.1 stands (the fields hold no data);
   the *fingerprint* is what changes.
2. **Rung-2 backstop gate (§12.2 #3)** — author *after* the collapse, so it guards the residue
   (the code-level provider switch) rather than scanning scaffolding that's about to be
   deleted. Standard `ssot-catalog-sync`-shaped gate + tier-register row (rung graduates
   prose-only → gate in `tier-register.md`).
3. **Language-signal single-seam register (§12.3 #1)** — lowest urgency; lands naturally
   when/if 580 §10's recipe-weight system first reads language (don't build the register for
   zero consumers — YAGNI; the channel is already single-sourced today).
4. **ADR + D-003 + explanation/23 pointer (§12.4)** — the permanent home; can land anytime
   (it's the rung-5 layer) and is the cheapest, but is gated on the user's §11 hold.

**The trap (from 576 §11, stated so a future agent doesn't fall in):** it is tempting to build
only the gate (#2) and call the invariant "enforced." That ships rung 2 and *skips rung 1* —
the bad state stays representable, merely detectable. The design's whole point is that here,
unusually, **rung 1 is reachable** (close the enum, delete the seam). Settle for the gate only
if the collapse is explicitly rejected.

### 12.7 What this design is NOT

- **Not a frontend / UI-i18n concern.** The invariant is **engine-scoped** — it governs the
  language of the indexed *corpus* and its retrieval (the SSOT analyzer/field catalogs, the
  worker analysis+retrieval path, the language *signal*), all backend. It does **not** touch
  `ui-web`, and none of §12's gates are presentation gates. UI localization — interface
  strings (`SSOT/messages/errors.*.json`), prompt templates (`SSOT/prompts/<lang>/…`), the
  `i18n-catalog` — is a *separate, legitimately per-language* surface and is explicitly **out
  of scope**: the invariant's logic ("a multilingual model absorbs the per-language work for
  free") has **no analog** there — nothing translates your UI for you, so per-language
  authoring *is* the feature, not a smuggled-in lever. Applying "no per-language levers" to UI
  i18n would forbid localizing the app — a category error. (The Head-never-touches-Lucene
  boundary reinforces this: the FE sends a query *string* and does no language routing.)
- **Not over-DRY.** The "forbidden fork-axis" shape is kin to 574's per-instance-vs-global
  scope axis, but per 547/AHA (*only unify what shares a reason to change*) language-analysis
  and UI-scope are **not** unified — they share a shape, not a reason. Each stays its own
  small mechanism.
- **Not a claim to solve discovery or projection.** Wall 1/2 stand: a genuinely novel
  per-language smell (bucket B vs A judgment) stays rung 5. The ladder raises the *declared*
  floor; it does not make human judgment obsolete.
- **Not touching the models.** The multilingual model stack is the language-agnostic *interface*
  the whole invariant rests on; the design governs the catalogs and seams *around* it, never
  the per-language work correctly outsourced to the model vendors.
- **Not a rewrite.** Every piece extends shipped substrate — the 530 kernel, the
  `ssot-catalog-sync` template, the existing schema/fingerprint validators, the single-seam
  register pattern. The only *removal* (the per-locale analyzer machinery) is removal of dead
  code, which is the strongest rung-1 move available.

---

## 13. De-risking outcomes (2026-06-15 read-only confidence pass)

A read-only pass (3 Explore agents on the highest-risk assumptions + direct reads) tested §12
against the code **before any implementation**, per the bidirectional-pass discipline. The
conceptual structure held; **one load-bearing claim was falsified** and one scope correction
landed. No code changed.

**✅ Confirmed (4 of 5):**
- **`content_{en,de}` are genuinely dead.** The write path is an *explicit field map*
  (`FieldMapper.toDocument` iterates the per-doc map, skips unknown fields), not a catalog
  iteration; `IndexingDocumentOps` writes only `CONTENT`/`CONTENT_PREVIEW`. Repo-wide grep
  (incl. proto / jseval / ui-web): only the two catalog definitions. No writer, no reader.
- **The schema `enum` is a *real* rung-1 constraint.** `SsotValidator` validates the analyzer
  catalog against the JSON schema, wired into `./gradlew check` (fail → exit 1). Closing
  `provider` to an enum genuinely fails the build — confirms §12.1's rung-1 claim is occupiable,
  not paper (the 576 §11 trap is avoided).
- **`icu+synonyms` folds out cleanly** — used only by the three content analyzers + one unit
  test + the registry switch.
- **The rung-2 gate is writable + wireable** (`ssot-catalog-sync` template; local vs manual-CI
  wiring understood). It is *narrower* than §12.2 #3 framed: provider closure is the schema's
  job, so the gate only needs the `content_<lang>`-field and non-empty-synonym-file checks.

**❌ Falsified — the surprise:**
- **The rung-1 collapse FORCES A REINDEX.** An `analyzer_fp` (hash over all analyzer ids +
  descriptors) is embedded in Lucene commit metadata (`SsotCommitMetadataSource.java:96`) and
  is a **parity key** (`ParityDiagnostics.PARITY_KEYS`); `IndexMetadataParityGuard.checkOnOpen`
  (`ComponentsFactory.java:108-120`) rejects a write-mode open on mismatch (`"Shard is
  read-only due to parity mismatch"`; override `justsearch.index.parity.allow_mismatch=true`).
  Removing the en/de analyzers **or** switching `content_all`'s provider changes `analyzer_fp`
  → existing indices fail the guard → rebuild. The §9.1 data finding stands; the error was
  conflating "no data in the field" with "fingerprint unchanged." **Cost is first-class, not
  catastrophic:** the repo has a blue/green migration (`KnowledgeServerMigrationOps`), a
  `RebuildIndexHandler` operation, and the `EmbeddingCompatibilityController` (model-change →
  reindex) precedent — so the collapse is a *supported schema-version migration*. **Design
  consequence: re-sequence the collapse to land last, behind a version bump, ideally riding the
  next unavoidable reindex rather than triggering a standalone rebuild to delete inert
  scaffolding.** Open sub-question for implementation time: is that rebuild automatic-on-upgrade
  or user-triggered?

**⚠️ Scope correction:**
- **`content_all` the field stays.** It is a live read surface — the AI fallback
  (`IntentJsonTemplate.java:37`) hardcodes it as the default search clause, and 17+ golden
  tests expect it. §12.2's "switch `content_all` → `icu` provider" is fine; *removing the
  field* is not. (Side finding, logged to `observations.md`: production never *writes*
  `content_all` — only benchmarks — so the AI fallback may query an empty field. Pre-existing,
  out of scope.)

**Re-sequenced plan (cheapest/zero-reindex first):** (1) rung-5 ADR + D-003 + explanation/23
pointer (no reindex); (2) rung-2 backstop gate + tier-register row (no reindex); (3) rung-1
schema closure **bundled atomically with** (4) the rung-1 collapse behind a schema-version bump
(reindex-forcing). The rung-1 signal seam (§12.3) stays deferred (zero consumers).

**Critical confidence: 7/10.** Structure validated, no blockers; the zero-risk pieces
(ADR/D-003, gate) are ~9/10 ready, the collapse ~6/10 (feasible, reindex cost now known, one
open sub-question). Matches 576's own post-de-risk 7/10.

---

## 14. Implementation outcome (2026-06-15)

The full design was implemented on `worktree-577-goal3-unify` (user chose **full collapse now**
+ **build graceful auto-rebuild first**). All four rungs landed; the open sub-question from §13
(automatic-vs-manual rebuild) was resolved by **building** the automatic path.

**Rung 1 — graceful auto-rebuild + collapse (reindex-forcing, now safe):**
- `IndexMetadataParityGuard` routes a mismatch on a **rebuild-requiring** parity key
  (`analyzer_fp` / `index_schema_fp` / `schema_ver`) into `IndexRuntimeIOException(SCHEMA_MISMATCH)`
  so the existing `REBUILD_BACKUP_FIRST` recovery rebuilds the index on upgrade instead of crashing
  read-only; query-time-only keys (`similarity_fp`/`boosts_fp`) still mark read-only.
  `ParityDiagnostics.REBUILD_REQUIRING_KEYS` + `requiresRebuild()` partition the keys.
- Collapse: removed the `content_{en,de}` fields + `en`/`de` analyzers (both SSOT copies),
  switched `content_all` → `icu` provider (fingerprint recomputed
  `ef02d975…`), closed the analyzer-provider schema to `enum: ["icu","keyword"]`, dropped the
  `icu+synonyms`/`loadSynonyms` path in `SsotAnalyzerRegistry`, deleted the synonym files/FSTs +
  `SynonymsCompiler` + the `ssotCompileSynonyms` task, retired the two `ssot-catalog-sync` synonym
  mirrors (mirror-retirement changesets), and kept `synonyms_hash` as the SHA-256 of the empty set
  (not a parity key; 10+ consumers).

**Rung 2 — backstop gate:** `scripts/ci/check-language-agnostic-analysis.mjs` +
`governance/language-agnostic-analysis.v1.json`, wired into `ci.yml` + `module-filter.yml` + the
CLAUDE.md pre-merge list; tier-register row 34 (`lint`) + prose-tier-register changeset; rule
anchor `<!-- rule:language-agnostic-analysis -->` (CLAUDE.md Hard Invariant 6).

**Rung 5 — permanent home:** **ADR-0043** (+ Decision Log row), register **D-003**, **Q-004 →
won't-do**, **FW-006 → won't-do**, explanation/23 analyzer-pipeline line updated + ADR-0013 marked
*partially superseded* (synonyms retired; its fingerprint-algorithm section remains in force).
Docs regenerated (`llmstxt-generate` + `skills-sync`).

**Verification:**
- ✅ `:modules:adapters-lucene:test` + `:modules:ssot-tools:test` green (incl. new
  `ParityGuardTest.parityGuardTriggersRebuildOnAnalyzerMismatch`, updated fingerprint pin,
  `InvariantSuiteIT` SCHEMA_MISMATCH).
- ✅ `./gradlew build -x test` green (compile + spotless + PMD + all integrationTests +
  `ssotValidate` — the schema enum is build-enforced).
- ✅ Gates: `language-agnostic-analysis` (passes; negative-tested with a temped-in `content_fr` →
  fails as designed), `ssot-catalog-sync`, `prose-tier-register`, SSOT validate — all green.
- ✅ Auto-rebuild mechanism unit-verified (guard → SCHEMA_MISMATCH → `REBUILD_BACKUP_FIRST` is the
  RecoveryIntegrationTest C2 path).
- ✅ **Live dev-stack + browser validation PASSED (post-merge, 2026-06-15).** The MCP dev stack
  runs from the **main checkout**, so verification ran after merging this branch to `main`.
  Findings on the merged main code:
  - **Fingerprint-mismatch routing live-confirmed + downstream behavior resolved (accuracy
    correction).** Restarting on the merged code against an index built with the OLD catalog logged
    `PARITY_DIFF key=analyzer_fp` + `index_schema_fp` — the rebuild-requiring keys my change routes
    into the existing `SCHEMA_MISMATCH` migration contract. **Correction to the earlier
    "auto-rebuild on upgrade" framing:** the downstream is policy-controlled
    (`index.schema_mismatch.policy`; see `docs/reference/index-schema-mismatch-reindex-noop.md`):
    **production defaults to `FAIL_CLOSED`** → the worker surfaces a deterministic "schema mismatch /
    migration required" via `/api/status` (covered by `SchemaMismatchStatusContractTest`), *not* a
    silent wipe; **dev defaults to `REBUILD_BACKUP_FIRST`** → backup + fresh empty index + reindex
    self-heal. So my change converts the prior **uncaught crash** on an analyzer-catalog change into
    the same well-defined, tested schema-migration path that field-schema changes already use — an
    improvement in *both* modes. (Dev forces `allow_mismatch=true`, which short-circuits the guard to
    WARN before the throw, so the REBUILD branch isn't dev-triggerable through the MCP stack; the
    routing is unit-verified by `parityGuardTriggersRebuildOnAnalyzerMismatch`, and the live run
    confirmed the trigger fires on real fingerprints.)
  - **Zero synonym loading:** worker log shows 0 `Loaded synonyms for locale` (main no longer ships
    synonym files) — the collapse is live-confirmed.
  - **Multilingual search works** on the locale-invariant pipeline (clean index): en
    (`Apollo moon landing` → en.txt 4.19 + de.txt), de (`Mondlandung Raumfahrt` → de.txt 5.86),
    zh (`月球 宇航员` → zh.txt 3.50); `language` still detected (en-US / zh — bucket-B signal intact).
  - **Browser UI:** the German query `Mondlandung` in the real shell-v0 search surface returned
    1 result (de.txt) rendered with Type/Format/Language facets + a "Why this result?" provenance
    expander — full stack ICU → BM25 → fusion → Lit render green.
  - (Unrelated pre-existing red logged to observations.md: `UnreferencedCodeTest` on `AgentSession`,
    a 577 R3 artifact. The parallel tempdoc-580 in-progress work found uncommitted on main was
    preserved in its own commit before the merge.)
