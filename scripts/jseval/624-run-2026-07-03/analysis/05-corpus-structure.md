# 05 — Corpus/query-design vs tooling attribution (agent report, 2026-07-03, verbatim)

**Data**: 260 samples/corpus (2 conditions × 26 qids × 5 epochs), outcome = `judge-overlay.json`
final scores. Corpus: 26 gold 2-hop chains, distractor_ratio=4 (104 distractor chains), same
generator seed=624 for both languages (`corpus_generate.py:539-555` — `axis_offset` depends only on
`axis`, not `lang`, so the (type,place,qual) index draws are **identical** between EN/DE). EN
doc_words=2500, DE=1300 (measured 2529.7 / 1305.5).

## 1. Per-query outcome profiles (measured)

| Class | EN | DE |
|---|---|---|
| stable-solved-both | 7 | 5 |
| stable-failed-both | 0 | 2 |
| **arm-differential** (stable in both arms, but disagree) | **0** | **0** |
| seed-unstable (≥1 arm flips across its 5 epochs) | 19 | 19 |

Zero arm-differential qids in either language — whenever a query has a deterministic outcome in both
arms, both arms agree. **73% of qids are seed-unstable** in at least one arm — dominated by within-arm
run-to-run noise. Exploratory: removing the top-3 most-unstable qids per corpus leaves the pooled
deltas essentially unchanged (EN −0.070, DE +0.009) — the near-null effect is spread across nearly
all queries, not driven by outliers.

## 2. Difficulty anatomy (measured)

Hop count constant (2 hops), answer-token distinctiveness constant by construction. Descriptor
confusability: 26/26 gold queries (both corpora, identical by construction) have ≥1 distractor
sharing type (avg 8.73) and ≥1 sharing place (avg 4.00). Correlation of confusability with combined
accuracy: type r=−0.07(EN)/−0.02(DE), place r=−0.10(EN)/−0.24(DE) — weak, contradicted by exemplars
(DE q21, stable-failed, below-average confusability; DE q18, stable-solved, above-average).
**Interpretation (inferred)**: confusability is pervasive-but-uniform by generator design, so it
doesn't discriminate solved vs failed; the dominant per-query variance is seed noise. The generator's
own exact-title collision check (0 gold-involved) doesn't surface this softer partial-overlap
confusability (100% of queries).

## 3. EN-vs-DE gap (0.815 vs 0.562 pooled-A)

| Signal | EN | DE | vs "reading cost" hypothesis |
|---|---|---|---|
| Avg doc words | 2529.7 | 1305.5 | EN docs ~2× longer |
| Avg turns/sample | 22.9–24.2 | 18.6–19.1 | EN uses MORE turns, scores higher |
| Avg unique tokens | 48–50k | 35–36k | EN spends more, still wins |

Confusability structure provably identical (same seed, same draws). This rules out doc-length-as-
difficulty and "DE cheaper so should score ≥". Clean paired exemplar: q0 (Reaktor/Kraftwerk, "upper
wetlands", 1st installation) is stable-solved in EN, stable-failed in DE — same chain, same
confusability, opposite outcome.

Wrong-answer composition (regex-based, lower bound):

| | EN | DE |
|---|---|---|
| Abstention-style | 38.6% of wrong | 18.8% |
| Confident wrong answer | 61.4% | 81.2% |

**Inferred**: DE failures skew toward confidently wrong values — consistent with weaker German
synonym-bridging (Kraftwerk↔Reaktor etc., all constructed zero-surface-overlap pairs) and/or general
LLM English bias, rather than corpus structure (identical). No per-step trace directly confirms the
exact mechanism.

## 4. Abstention accounting (measured, regex heuristic)

| Corpus | n incorrect | Abstain | Confident-wrong |
|---|---|---|---|
| EN | 57 | 22 (38.6%) | 35 (61.4%) |
| DE | 112 | 21 (18.8%) | 91 (81.2%) |

DE's profile points at a retrieval/matching-confidence lever; EN's is closer to a coin flip between
gave-up and picked-wrong.

## 5. Ceiling estimate (measured proxy + inferred conclusion)

For every incorrect completion, checked whether any of the 3 gold chain entity IDs appear in the
completion text (closed-book certification = 0% guessable, so any mention implies real contact with
the content):

| Corpus | n wrong | Mentions correct HEAD entity | Mentions all 3 (found full chain, still wrong) | Mentions none |
|---|---|---|---|---|
| EN | 57 | 6 (10.5%) | 4 (7.0%) | 50 (87.7%) |
| DE | 112 | 1 (0.9%) | 0 (0.0%) | 110 (98.2%) |

Reasoning failure given the correct chain found: ~1.9% (EN 4/207 found-chain cases), 0% (DE 0/148;
rule-of-three 95% CI upper ≈2%). **Inferred ceiling: an oracle-retrieval agent would hit ~95–99% on
both corpora** — the gap to 100% is overwhelmingly head-doc-identification/retrieval failure, not
reasoning capacity.

## Bottom line for corpus-design decisions

- Tooling arm is not a reliable driver of per-query outcome (0 arm-differential qids); the null is
  broad, not an artifact of a few noisy qids.
- The battlefield's dominant variance is within-arm seed noise (73% of qids) — small tooling effects
  need more epochs or reduced variance to detect.
- Sibling-confusability is necessary-but-not-discriminating — not currently a per-query difficulty
  lever.
- The EN/DE gap is a language/model effect, not corpus-generation structure — the lever is DE
  synonym-bridging fidelity or model choice, not corpus redesign.
- Headroom is real (~95%+ oracle ceiling) and concentrated almost entirely in the retrieval/
  head-matching step — product-improvement territory, not battlefield artifact.
