---
status: "partial (2026-07-29) — suite BUILT + tiers P/S MEASURED; tier D (in-corpus) scripted and DEFERRED to a serialized compute slot after a machine-wide thermal event. No engine change proposed, no default flipped. Implements tempdoc 788 §3.B.10."
created: 2026-07-29
updated: 2026-07-29
---

# 796 — Paraphrase-bridging reliability: the tool's unique value, made measurable

## Why this exists

The 2026-07-28 hero campaign (F-043, tempdoc 782 §I) produced an adoption-only verdict and, more
usefully, a **mechanism**. Buried in that mechanism was the one thing the engine did that no file
tool could: the census's "purest tool win" (cell `en-email-enron-raw-1k-verbose|B|q16|s2`) reached
gold in two searches and five turns because the first query performed a paraphrase mapping
`coin-striking works → mint`, `arid hollow → desert basin` that the A arm could not `grep` for.

And the same campaign showed that capability is **unreliable**: `power station → reactor` (q0) failed
in every cell of both arms.

Two other campaign facts make this the load-bearing axis rather than a curiosity:

- Agents provably **do not reformulate across the gap**. The 789 census measured query-token
  containment 0.74 against the question and 0.18 against the gold text — agents re-type the
  question's vocabulary, they do not guess the corpus's.
- No standing metric existed for bridge reliability at all. Tempdoc 788 §3.B.10 named the gap:
  *"a synonym-pair probe suite measuring bridge reliability by paraphrase distance."*

This tempdoc builds that suite and reports its first run.

## What made it measurable without authoring anything

The fabricated-chain corpora ship their paraphrase mapping **by construction**.
`jseval.corpus_generate` renders the head document with the doc-side member of a synonym pair and the
query with the query-side member (`_SEM_TYPE` / `_SEM_PLACE` / `_SEM_QUAL`, and their German
siblings), and a standing invariant (`test_sem_pools_are_root_disjoint`, tempdoc 767 §I.3) guarantees
the two members share **no token**.

So the pair set is *extractable*, and — the part that matters for the design — the **lexical arm is a
control that must fail by construction**, not a baseline that happens to be weak.

`scripts/jseval/experiments/paraphrase_bridge_suite.py pairs` imports the pools from the generator
module itself (never a hand-typed copy) and joins them to the committed 781 corpora by surface match:
for every gold query it locates the pool pair whose *query-side* member occurs in the question and
whose *doc-side* member occurs in the head document, and requires both halves to resolve to the
**same pool index** or records a loud mismatch.

**Pair census (2026-07-29):**

| | EN | DE |
|---|---|---|
| pool pairs (21 type + 44 place + 25 qual) | 90 | 90 |
| exercised by the committed 781 corpora | **65** | 0 |
| observations (8 members × 50 queries × 2 axes) | **800** | 0 |
| **mismatches** | **0** | 0 |
| pairs with a shared token or shared stem | **0** | **0** |

Three facts fall out of the census itself:

1. **The join is exact.** 800 observations, zero mismatches — and an independent cross-check
   (`test_surface_join_agrees_with_generator_index_arithmetic`) confirms the surface join reproduces
   `_sem_for`'s `(g % 21, g % 44)` index arithmetic without using it.
2. **The committed corpora exercise the two-axis regime only.** 50 chains sit far under the 924
   two-axis ceiling, so all 25 *qualifier* pairs are unobserved in shipped corpora. They are measured
   here anyway (tier P), which is how we know they are the *easiest* axis — see below.
3. **The German pools are entirely unexercised** by any committed corpus. Tier P measures them for
   free and the answer is not reassuring (below).

## The three tiers

Measuring "does it bridge" needs a stated candidate set, and the honest answer differs by how much
of the real task you include. The suite therefore reports three tiers, each a strictly weaker ceiling
claim than the next:

| tier | candidates | what it adds | cost |
|---|---|---|---|
| **P** pair-isolated | the other members of the same pool (21 / 44 / 25) | nothing — pure bridging with same-pool hard negatives | seconds |
| **S** sentence-isolated | the member's 100 fabricated head/tail sentences | question phrasing + chain structure | ~30 s/member |
| **D** in-corpus | the assembled dataset (injected sentences inside real host documents) | host-document dilution at production doc/chunk granularity — the setting the hero observation was made in | ~25 min/member (CPU) |

A pair that fails at tier P cannot bridge anywhere. A pair that succeeds at tier P may still fail at
tier D. Reading a tier-P or tier-S number as an engine capability claim is the mistake this table
exists to prevent.

**Arms** (all offline exact-NN / exact-scoring, no ANN, no fusion, no engine):

- `dense` — the shipped `gte-multilingual-base` ONNX under the production recipe: CLS pooling, no
  prefixes, 512/128 raw id windows with the tail-merge rule, unweighted mean of L2-normed windows
  (`OnnxEmbeddingEncoder`; the same W1 path 708 anchored on, with the shipped graph in place of
  708's HF mirror).
- `dense-chunk` — same encoder at `ChunkDocumentWriter` granularity (500-token chunks / 50 overlap),
  doc score = max chunk cosine. Tier D only.
- `splade` — `naver-splade-v3` ONNX both sides, `log1p`, `max_seq_len` 512 — the shipped
  `justsearch.splade.query_mode=onnx` default.
- `splade-idf` — the inference-free alternative query encoder (`query_mode=idf`): tokenize + IDF
  lookup. Included because it **cannot expand**, so it is a second, sharper control.
- `lexical` — BM25 over the ICU-ish analysis (NFC + lowercase + unicode word split). The control.

Ties rank **pessimistically** (a tie counts against the target), so a bridge is never credited to a
draw — this matters for the lexical arm, whose scores are all-zero on a token-disjoint descriptor.

## Result 1 — bridging is a steep step function of isolated pair cosine

The declared bucketing axis is `dense_pair_cosine`: the cosine between the two members of the pair,
measured by the incumbent encoder with **no corpus around it**. Other proxies (token/stem/char-3gram
Jaccard, normalized edit similarity, word-count delta, SPLADE pair dot) are recorded per pair —
deliberately no single magic scale — but the curve buckets on this one, and a row exercising several
pairs buckets on its **hardest** (lowest-cosine) pair, because a query only bridges if every
descriptor axis it names bridges.

**Tier P, EN, 90 pairs, bridge = correct partner ranked top-1 within its own pool:**

| bucket | n | dense | splade | splade-idf | lexical |
|---|---|---|---|---|---|
| [0.00, 0.55) | 3 | **0.00** | 0.33 | 0.00 | 0.00 |
| [0.55, 0.65) | 21 | **0.19** | 0.38 | 0.19 | 0.00 |
| [0.65, 0.75) | 50 | **0.92** | 0.64 | 0.42 | 0.00 |
| [0.75, 0.85) | 16 | **1.00** | 0.56 | 0.50 | 0.00 |

At top-3 the dense curve is 0.00 / 0.81 / 1.00 / 1.00. The knee is sharp and sits near **0.65**, and
the EN pair distribution straddles it (min 0.499, p25 0.639, median 0.699, max 0.816) — i.e. the
generator's own synonym pools contain pairs on both sides of the reliability cliff.

**The control behaves exactly as constructed: 0/180 pairs bridged at any k, in either language.**
BM25's MRR is 0.033 — the all-tie floor. This is the strongest single statement the suite makes:
against a token-disjoint paraphrase, lexical retrieval has *no* signal, and everything the engine
recovers here is the semantic stack's doing.

**Per-axis (EN, top-1):**

| axis | n | dense | splade | splade-idf |
|---|---|---|---|---|
| type (`reactor` ↔ `power station`) | 21 | 0.81 | 0.48 | 0.33 |
| place (`northern marshlands` ↔ `upper wetlands`) | 44 | **0.57** | 0.50 | 0.27 |
| qualifier (`unit seven` ↔ `the seventh installation`) | 25 | 0.96 | 0.72 | 0.56 |

The *place* axis is the weak one — multi-word locative descriptions ("mountain pass" ↔ "high col",
"chalk downs" ↔ "white escarpment hills") are where the encoder loses the mapping. Both of the only
two EN pairs the dense arm cannot reach even at top-10 are place pairs.

**German is measurably worse at the same task.** Tier P DE dense top-1 is 0.578 vs EN 0.733, and the
DE curve is shifted right (top-1 by bucket 0.00 / 0.07 / 0.68 / 1.00 / 1.00) — German needs a *higher*
isolated cosine to reach the same bridge rate. This is a fresh, cheap datapoint for Q-018 (the German
semantic-collapse question), measured on the pools rather than on a leaky corpus.

## Result 2 — SPLADE's shipped query mode matters more than SPLADE

`splade` (neural both sides) bridges 45.6% of pairs at top-1; `splade-idf` bridges 33.9%. Since
`justsearch.splade.query_mode` defaults to `onnx`
(`ResolvedConfigBuilder.java:1174`), the shipped configuration is the stronger one — but the gap is a
standing reminder that the `idf` query encoder, being a term lookup with no expansion, is
**structurally incapable of bridging**; it recovers pairs only when the doc-side expansion happens to
reach the query's literal terms.

## Result 3 — the two hero anchors do NOT fail at the pair or sentence tier

The suite pins both named hero cases as regression rows. Gate-0 discipline says a disagreement with
the behavioral outcome must be investigated before shipping, and there is one:

| anchor | pool | `dense_pair_cosine` | tier P rank | tier S rank (enron-1k-verbose) | hero outcome |
|---|---|---|---|---|---|
| `mint` ← `coin-striking works` (`en:type:16`) | 21 | 0.590 | **1** | — | bridged |
| `desert basin` ← `arid hollow` (`en:place:16`) | 44 | 0.629 | **2** | — | bridged |
| q16 (both, in corpus) | 100 | — | — | **dense 1**, splade 1 | bridged ✓ |
| `reactor` ← `power station` (`en:type:00`) | 21 | 0.618 | **2** | — | *unbridged 0/6* |
| `northern marshlands` ← `upper wetlands` (`en:place:00`) | 44 | 0.706 | **1** | — | *unbridged 0/6* |
| q0 (both, in corpus) | 100 | — | — | **dense 2**, splade 1 | *unbridged 0/6* ✗ |

So the works→mint anchor reproduces cleanly, and **the reactor anchor does not**: at the descriptor
level, `power station → reactor` bridges about as well as the case that succeeded. Whatever cost the
hero campaign six cells on q0, it is **not** a failure of the paraphrase pair.

That is the finding this suite was built to be able to state, and it re-points the q0 investigation
away from "the encoder can't bridge that pair."

## Result 4 — tier S: isolation is not where the difficulty lives

Over all 8 committed members (400 query rows, 100 candidates each), bridge@10 is **dense 0.995**,
`splade` 0.985, `splade-idf` 0.66, **lexical 0.035** — and the dense reliability curve is *flat*
across every pair-cosine bucket (1.00 / 0.99 / 1.00 / 1.00). Top-1 is where the members separate
(dense 0.82–0.88; short-natural queries beat verbose ones by ~0.06 on every corpus, consistent with
F-034's "dense needs sentence-shaped queries" pointing the *other* way once the descriptor is the
whole signal).

The reading: **at 100 same-shaped candidates the bridge essentially always succeeds**, so the tier-P
cliff is a property of same-pool discrimination and the interesting variance must come from
host-document dilution — which is exactly what tier D measures and what this session could not run.

## Deferred: tier D (in-corpus), and why

**Status: not run.** A machine-wide thermal event (100 °C core) during this session led to a
one-heavy-lane rule and the founder taking the compute slot; per that directive all encoder-probe
execution is deferred to a serialized slot. The tier-D run was stopped mid-encode and wrote **no**
output file (the artifact is written once at end-of-run, so there is no partial or corrupt JSON).
Tiers P and S had already completed and are verified intact (`pairs.v1.json` 90+90 pairs / 800
observations / 0 mismatches; `tier-p.v1.json` 180 rows; `tier-s.v1.json` 400 rows; all parse clean
with every arm present).

Partial progress that survives and will be reused: 7 checkpoint blocks (700/1000 documents) of
`dense-doc` for `en-email-enron-raw-1k-verbose` under `tmp/paraphrase-bridge/cache/`.

### The execution pass, fully specified

Preconditions: `(Get-CimInstance Win32_Processor).LoadPercentage` under 60; no other heavy lane
running; `tmp/paraphrase-bridge/pairs.v1.json` present (it is committed-reproducible — re-run the
`pairs` step, which needs no model, if the tmp dir was cleared).

```bash
cd F:/justsearch-public/.claude/worktrees/<this-worktree>
# ~25 min/member on CPU at 10 threads; resumes from cache/ if interrupted
python scripts/jseval/experiments/paraphrase_bridge_suite.py tier-d \
  --member en-email-enron-raw-1k-verbose,en-email-enron-raw-1k-short-natural \
  --datasets F:/justsearch-public/tmp/781-v2-datasets/mixed \
  --tag enron1k --threads 10 --out tmp/paraphrase-bridge

# legal-clerc docs are ~7x longer; budget ~2h, or drop --arms to dense,dense-chunk,lexical
python scripts/jseval/experiments/paraphrase_bridge_suite.py tier-d \
  --member en-legal-clerc-1k-verbose \
  --datasets F:/justsearch-public/tmp/781-v2-datasets/mixed \
  --tag clerc1k --threads 10 --out tmp/paraphrase-bridge

python scripts/jseval/experiments/paraphrase_bridge_suite.py report --out tmp/paraphrase-bridge
```

Expected outputs: `tier-d.enron1k.v1.json` / `tier-d.clerc1k.v1.json` — one row per
(member × query × query-form), each with `arms.{dense,dense-chunk,splade,splade-idf,lexical}` carrying
`rank`, `top1/3/5/10`, and `tail_rank` (the hop-2 document's rank, for context). `report.v1.json` gains
a `D:<tag>` tier with `summary`, `per_member`, `per_query_form`, `curves_by_form`, and the `anchors`
block. Sanity checks on the output before reading it: `n_rows == 3 × 50 × n_members` (three query
forms), `lexical` top-10 rate near zero on the descriptor/keyword forms, and `candidates == 1000`.

### The three query forms, and why they are the point

Tier D scores each query in **three shapes, all derived from the pair register** (`query_forms()`):

| form | construction | example (q0) |
|---|---|---|
| `question` | the corpus's own generated question | *"What is the value associated with the designer of the power station in the upper wetlands?"* |
| `descriptor` | `<type-synonym> in the <place-synonym>` | `power station in the upper wetlands` |
| `keyword` | `<place-synonym> <type-synonym>` | `upper wetlands power station` |

The last two are not inventions. The hero census's `queries.v1.json` records both **verbatim** as
strings a B cell actually issued for the failing q0, and records that agents type a median of **4**
content tokens — while F-034's secondary finding is that dense retrieval is markedly weaker on
keyword-shaped queries than on sentence-shaped ones. Measuring bridging only on the generated
question would therefore measure a shape no agent issues.

**The pre-registered hypothesis for the reactor anchor** (written before the run, so the result can
falsify it): given that `power station → reactor` bridges at tier P (rank 2/21) and tier S (rank
2/100), q0's 6/6 hero failure is *not* the pair. The candidates, in the order tier D can discriminate
them, are (a) **query shape** — the bridge survives `question` but not the 4-token `keyword`/
`descriptor` forms agents actually send; (b) **host dilution** — the injected sentence is one line
inside a real 2.5 KB email and the whole-doc representation drowns it (the F-031/F-040 mechanism);
(c) neither, in which case the failure is downstream of retrieval entirely (hop-2, or the agent
stopping at an intermediate fact — 788 §1's satisficing mechanism). If (a), the delivery-layer work
in 788 §3.A and this engine axis are the same problem seen from two ends.

## Honest limits

- **Offline exact-NN is a ceiling, not the engine.** Every number here bypasses ANN recall, fusion,
  the cross-encoder, and the chunk/branch machinery. F-033 and F-034 established the treatment: the
  load-bearing result of an offline probe is the **delta between its arms**, never its absolute
  level. F-040 measured the sharper version of the same caveat on these very strata — the shipped
  engine hybrid *beat* the offline passage exact-NN ceiling there — so an offline tier-D rank must
  not be read as "what the engine would return."
- **Synthetic-pair provenance.** These are procedurally generated synonym pairs chosen to be
  token-disjoint and domain-neutral. They are a clean instrument for *bridging*, and they are not a
  sample of how real users paraphrase. A real-corpus replication is the 788 §C.17 concern applied to
  this axis.
- **Two-axis regime only in shipped corpora.** All 25 qualifier pairs are unobserved by any committed
  member; their tier-P numbers are pool-only.
- **German is pool-only.** No committed corpus exercises the DE pools, so the DE tier-P result has no
  tier-S or tier-D counterpart.
- **Tier S is scale-invariant by construction.** The committed `fabricated-docs.jsonl` is
  byte-identical across the 1k and 10k members of a corpus (same seed, same 50 chains); only the
  assembly into hosts differs. The 10k tier-S rows therefore duplicate the 1k rows exactly — that is
  a property of the artifact, not a measurement.
- **CPU-only, single run.** All encoding ran on `CPUExecutionProvider` to avoid contending with a
  parallel worker's GPU job; no multi-seed, no variance estimate. One run was lost to an
  environment-level process kill mid-encode, which is why the expensive tier is now block-checkpointed.

## Reproduction

```bash
cd <repo>
python scripts/jseval/experiments/paraphrase_bridge_suite.py pairs   --langs en,de --out tmp/paraphrase-bridge
python scripts/jseval/experiments/paraphrase_bridge_suite.py tier-p  --langs en,de --out tmp/paraphrase-bridge
python scripts/jseval/experiments/paraphrase_bridge_suite.py tier-s  --out tmp/paraphrase-bridge
python scripts/jseval/experiments/paraphrase_bridge_suite.py tier-d \
    --member en-email-enron-raw-1k-verbose --datasets <datasets>/mixed --tag enron1k --out tmp/paraphrase-bridge
python scripts/jseval/experiments/paraphrase_bridge_suite.py report  --out tmp/paraphrase-bridge
```

Tiers P and S need only the repo + the shipped model blobs. Tier D additionally needs the local
781 v2 dataset dirs. Encoded blocks are checkpointed under `<out>/cache/<member>/<arm>/`, so a killed
run resumes and a re-run with different query forms costs only query encoding.

**Artifacts** (worktree `tmp/paraphrase-bridge/`, gitignored — this repo commits scripts and results
tables, not raw outputs, per 708's convention): `pairs.v1.json` (pair register + proxies +
observations), `tier-p.v1.json`, `tier-s.v1.json`, `tier-d.<tag>.v1.json`, `report.v1.json`.

**Code:** `scripts/jseval/experiments/paraphrase_bridge_suite.py`;
tests `scripts/jseval/tests/test_paraphrase_bridge_suite.py` (extraction, disjointness invariant,
join-vs-generator cross-check, proxies, control-arm behaviour, curve/bucketing, checkpoint resume,
query-form derivation — all CI-runnable, no model blobs needed).
