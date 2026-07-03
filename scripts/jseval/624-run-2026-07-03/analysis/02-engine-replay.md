# 02 — Engine-side replay verdict (agent report, 2026-07-03, verbatim)

## 0. Confirmation of the null's mechanism (measured)

Independently re-verified before replaying: across all four certified log files (520 samples), the
string `mcp__` occurs **zero** times; B-cell tool traffic is exclusively `Grep/Read/Bash/PowerShell/
Glob` (en-B: Grep 996, Read 966, Bash 928, PS 103, Glob 25; de-B similar).
`tmp/624-run-2026-07-03/mcp.json` is `{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:33221/mcp"}}}`
— no `"type":"http"`. Certified accuracies for reference: en A 81.5% / B 74.6%; de A 56.2% / B 57.7% —
B's numbers contain no engine contribution at all. Step 1 of the original brief (extract agent-issued
MCP queries) correctly yields the empty set.

## 1. Method (measured)

Per corpus, one hermetic cycle: `start_backend(port=33221, clean=True)` → ingest
`datasets/golden/battlefield-*/corpus-dir` (asserted 391 files = 390 docs + sentinel; readiness
passed) → full enrichment (embedding & SPLADE 100%) → POST `/api/knowledge/search`
`{"query","mode":"hybrid","limit":10}` for 78 queries (26 verbatim questions + 2 analyst-constructed
variants each: `v1_descriptor` = the installation descriptor phrase alone; `v2_fragment` =
type+location keywords) → `stop_backend`. Corpora never co-resident. Doc-id resolution via
`resolve_doc_id` (`scripts/jseval/jseval/retriever.py:222`). Ground truth verified: `qrels/test.tsv`
marks only the hop-1 doc; `queries.json` `evidence_ids` is the ordered 3-doc chain; the answer token
appears in the final chain doc only, for all 52 questions.

## 2. Hit rates (measured; n=26 per cell; chain@k ≡ hop1@k — only the hop-1 doc was ever retrieved)

| Corpus | Formulation | top-3 | top-10 |
|---|---|---|---|
| en | verbatim question | 18/26 (69%) | 20/26 (77%) |
| en | v1 descriptor | 23/26 (88%) | 26/26 (100%) |
| en | v2 fragment | 15/26 (58%) | 20/26 (77%) |
| de | verbatim question | 18/26 (69%) | 21/26 (81%) |
| de | v1 descriptor | 24/26 (92%) | 24/26 (92%) |
| de | v2 fragment | 12/26 (46%) | 20/26 (77%) |
| **pooled** | **verbatim** | **36/52 (69%)** | **41/52 (79%)** |
| **pooled** | **v1 descriptor** | **47/52 (90%)** | **50/52 (96%)** |
| **pooled** | **v2 fragment** | **27/52 (52%)** | **40/52 (77%)** |

Best-of-3 formulations per question: en 24/26 top-3, 26/26 top-10; de 25/26 both; pooled **49/52 (94%)
top-3, 51/52 (98%) top-10**.

Answer-doc (hop-3) rank: 0/52 in top-10 for every formulation — **by construction, not an engine
defect**: the answer doc shares no surface or semantic form with the question. Reaching it requires
follow-up queries on hop-2 entity names — exact tokens a lexical search handles trivially (inferred;
no agent-issued follow-ups exist to replay).

## 3. Exemplars

**Served-well** (chain doc top-3):
- en q1 verbatim: "…the stargazing facility in the ridge to the east, the second installation?" →
  top-3 `[brelker4, zelwick307, druric271]`, hop-1 at rank 1.
- en q0 v1: "the power station in the upper wetlands, the first installation" →
  `[druven1, cavker223, faldell79]`, rank 1 (the verbatim question missed top-10 — question
  boilerplate hurt it).
- de q0 v2: "Kraftwerk oberes Feuchtgebiet" → `[druven1, vexfen226, limker316]`, rank 1.
- de q2 verbatim: "…Standort Getreidemühle, Krümmung des Flusses…" →
  `[cavdac337, harnven7, quenfen67]`, rank 2.

**Served-poorly** (no chain doc in top-10):
- en q9 verbatim & v2: "coastal beacon stony promontory" → `[orrstone346, zelpost145, olmby262]`;
  gold doc `brelthorn28` reads *"The lighthouse in the rocky headland"* — deliberate paraphrase;
  "coastal beacon"/"stony promontory" occur nowhere in the corpus (measured). v1 recovered it at
  rank 5.
- de q16 (the one total miss): "Metallwerk, trockene Mulde" vs gold `orrdell49` = *"Gießerei,
  Wüstenbecken"* — same mechanism; all 3 formulations missed.
- en q4 verbatim: "metal-casting works in the quarter to the west" → missed; v1 rank 5, v2 rank 1.

Misses are explainable: `semantic: true` generation means zero lexical overlap between query
descriptor and gold doc; failures cluster where the paraphrase gap is widest. No case of the engine
failing a query with a direct lexical match.

## 4. Verdict

**If a future B arm actually reaches the engine with reasonable queries, retrieval is not the
bottleneck.** Verbatim question paste — the laziest plausible agent behavior — gets the correct chain
entry-point in top-3 for ~69% and top-10 for ~79% of questions (both languages, identical 69% top-3).
A competent one-step reformulation reaches 90% top-3 / 96% top-10 pooled; across a couple of attempts,
94% / 98%. The residual 2-6% are the hardest paraphrase gaps of an intentionally "hard"-band corpus
(fidelity nDCG@10 0.4143). DE tracks EN closely, consistent with the locale-invariant multilingual
stack. Completing a question still requires 2 chain-following searches after the entry point; those
are exact-entity-token lookups (inferred).

## 5. Caveats (honesty ledger)

- Replay ≈ approximation: fresh index rebuilt at main `5db0614`; the certified run was at `3f9f57f`
  (dirty tree). Engine code and index state may differ from what a hypothetical working B arm would
  have seen.
- Single replay run; hybrid fusion has residual nondeterminism — ranks near cutoff could flip between
  rebuilds; no envelope measured.
- v1/v2 variants are analyst constructions of "plausible agent queries" (modeled on the grep phrases
  agents actually used), not agent-issued.
- Parts (b)/(c) of the original brief (cells that failed despite good engine results) are unanswerable
  as posed — no B cell ever received an engine result.

Artifacts: `scratchpad/replay-{en,de}.json`, `replay_corpus2.py`, `analyze_replay.py` (session
scratchpad, discarded).
