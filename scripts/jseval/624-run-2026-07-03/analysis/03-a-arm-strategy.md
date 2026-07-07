# 03 — How the file-tools baseline wins, and where it breaks (agent report, 2026-07-03, verbatim)

## 1. Strategy taxonomy (measured, tool-sequence classification)

Classified by coarsening every tool call (Grep, and Bash/PowerShell commands containing
grep/Select-String/Get-Content/ls/Get-ChildItem) into S(earch)/R(ead)/L(ist)/O(ther), counting maximal
search "bursts":

| Strategy (≥2 search bursts + ≥2 reads = hop-chaining) | EN-A n (%) | EN-A acc | DE-A n (%) | DE-A acc |
|---|---|---|---|---|
| iterative-hop-chaining | 108 (83%) | 0.80 | 103 (79%) | 0.46 |
| two-phase-search-then-read | 14 (11%) | 0.86 | 13 (10%) | 0.92 |
| grep-then-read-single-pass | 8 (6%) | 1.00 | 14 (11%) | 1.00 |

Iterative hop-chaining dominates (~80%) — matches the dataset design: every query resolves through 3
evidence docs (installation → designer → founder). Bulk-sweep and read-many-no-grep strategies are
near-absent (<2%) and score ~0 when they occur.

## 2. Coverage quantification (measured)

| Metric | EN-A | DE-A |
|---|---|---|
| Distinct files Read: median / p90 / max | 6 / 11 / 21 | 4 / 7 / 12 |
| Grep-tool calls: median / p90 / max | 7 / 12 / 19 | 3 / 6 / 12 |
| Fraction of 390-doc corpus Read: median (mean) | 1.5% (1.9%) | 1.0% (1.2%) |
| unique_tokens: median (mean) | 46,478 (48,086) | 35,342 (36,106) |
| cost_usd: median (mean) | $0.216 ($0.226) | $0.163 ($0.183) |
| num_turns: median (mean) | 22 (22.9) | 18 (19.1) |

The winning strategy never scans the corpus — it inspects ~1–2% of documents. Rough token-share
estimate (inferred approximation): median-read content ≈ 25,200 tok (~54% of EN's trajectory) vs
8,680 tok (~25% of DE's); grep/Bash output + reasoning fills the remainder.

## 3. Hop mechanics (measured, verbatim exemplars)

Semantic-bridging hop chain (EN-A, q6, hit=3, correct): query says "optical instrument… Carpathian
uplands… sixth installation," corpus literal term differs:
```
Grep('Carpathian') → Grep('optical instrument') → Grep('optical|telescope|microscope')
→ Grep('sixth installation') → Grep('sixth') → Read(druker16.txt)
→ Grep('Olmholt17') → Read(olmholt17.txt) → Grep('Kanfen18') → Read(kanfen18.txt)
```
5 broad synonym-expansion greps to locate the hop-1 doc, then a clean grep(entity)→read(entity) cycle
per hop.

Minimal winning chain — only 2 greps total, then 3 straight Reads:
```
Grep('stargazing') → Grep('ridge.*east|east.*ridge') → Read(brelker4.txt) → Read(drumond5.txt) → Read(rellcrag6.txt)
```
Once one document is read, the extracted entity name (e.g. "Drumond5") maps **directly to the next
filename stem** (`drumond5.txt`) — the corpus's entity-id≡filename-stem convention lets models skip
the intermediate grep once an entity is in hand. Every doc's linking sentence names the next-hop
entity in a form that lowercases straight to a real filename.

Typical: 1 seed-locating burst (2–5 greps) + 2 targeted greps, interleaved with 3 evidence Reads.

## 4. Failure modes of A (measured)

| | EN-A (24 failures) | DE-A (57 failures) |
|---|---|---|
| Completion contains abstention language | 10 (42%) | 47 (83%) |
| evidence_hit=0 (none of 3 chain docs ever Read) | 19/130 (acc 0.05) | 47/130 (acc 0.02) |
| evidence_hit=3 (all 3 Read) | 101/130 (acc **1.00**) | 66/130 (acc **1.00**) |

**evidence_hit is an almost perfect predictor of correctness in both languages** — the task is
entirely "did you retrieve all 3 chain docs," not reasoning quality once retrieved. EN reaches hit=3
in 78% of cells; DE only 51%.

**A specific, large, DE-only defect**: 36/130 DE-A cells (28%) issue Bash commands against
**`all_locations.txt`** — a file that does not exist anywhere in the corpus (0 occurrences in EN
cells). Cells referencing it score 0.31 vs 0.66 for those that don't, and burn more turns (median 22
vs 17). One exemplar (q16, epoch 1): the model concludes "This location does not exist in the corpus"
— confident abstention — after failing to bridge "Metallwerk" to the corpus's actual term, compounded
by turns wasted on the phantom file.

Root cause of DE's gap (0.56 vs 0.82) is **not doc length or corpus structure** — DE docs are shorter
(mean 8,685 vs 16,790 chars; both corpora repeat the same **untranslated English filler paragraphs**,
only the linking sentence differs by language). It is: (a) the same query-paraphrase-vs-corpus-literal
gap EN has, but haiku's synonym-bridging appears weaker in German, and (b) the `all_locations.txt`
hallucination unique to DE. A small number (5, DE-only) of Bash calls also grep the wrong filesystem
path entirely.

## 5. Scaling extrapolation (inference, not measured)

At 390 docs the median EN-A trajectory already spends ~44% of the $0.50 budget. What scales badly
with corpus size N:
- Directory reconnaissance (`ls` on corpus-dir, done by nearly every cell) — output scales with N.
- Structural (non-entity-specific) grep patterns — e.g. `grep -h "^The .* was founded by"` — match a
  near-fixed fraction of the corpus, so output scales **linearly with N** (~390 lines today,
  4,000–40,000 at scale).
- Reads stay O(hops)=O(3) regardless of N — the strategy's core strength.

The strategy breaks not from context exhaustion on Reads but from **search-output explosion**; given
EN-A already consumes ~$0.22/query at N=390, linear projection puts budget exhaustion in the **low
thousands of documents** — well before 40K — unless greps become narrower or the budget ceiling
rises.
