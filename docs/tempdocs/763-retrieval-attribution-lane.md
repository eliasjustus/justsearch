---
title: "retrieval attribution lane: replay every failed with-tool cell's queries against the cached indexes and attribute each failure to one of five buckets — the engine only owns the bucket it actually caused"
type: tempdocs
status: "EXECUTED (2026-07-21, same day — orchestrator-run, two pinned opus workers: extraction + live replay). Census complete; engine finding promoted to register F-039; see §F Results."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed analysis program (umbrella: 762)
category: eval-analysis / retrieval-quality
related:
  - 762-agent-utility-analysis-program   # umbrella: priors §P, data §D, constraints §C — READ FIRST
  - 624-agentic-retrieval-eval-rebuild   # campaign history
  - 751-content-addressed-eval-index-cache  # the adopt path that makes replay cheap
---

> Charter. Execute after reading 762 §P/§D/§C. This lane's product is a failure
> taxonomy with counts, exemplar transcripts, and an engine-bug list. Findings
> that indict the engine go to the search-quality register (load
> `/search-quality` before closing); everything else is evidence for 762 §L.

# 763 — retrieval attribution lane

## §A. Question

With-tool (B-arm) accuracy is 0.167–0.200 on legal and the with-tool arm loses
to grep on email-1k. **Where, mechanically, do the failures happen?** Every
failed B cell went through: agent formulates query → engine returns results →
agent uses (or ignores) them → agent synthesizes an answer → judge scores it.
Attribute each failure to the first broken link:

| Bucket | Definition | Remedy owner |
|---|---|---|
| B1 bad-query | gold document(s) unreachable by the queries the agent actually issued, but reachable by a reasonable reformulation (verify by issuing the reformulation) | tool ergonomics / prompt surface (765 consumes) |
| B2 engine-miss | a reasonable issued query should have surfaced the gold docs and the engine did not put them in top-k | **the engine** → search-quality register |
| B3 result-unused | gold doc(s) were in returned results; agent never opened/used them | agent behavior / result presentation (765 consumes) |
| B4 synthesis-failure | agent used the gold evidence and still answered wrong | model capability (prior §P.1 — expected dominant at 10k; just count it) |
| B5 judge-error | the answer is actually correct/equivalent; judge scored it wrong | eval design (hand to 764 — do not fix here) |

Also bucket **B0 exhausted-before-retrieval** (cell died on tokens/turns/time
before a usable retrieval round) — count it and hand the anatomy to 765.

## §B. Scope

- **Primary**: all failed B cells in legal-1k and legal-10k (v5). That is
  ~48+50 of 60 cells each — sample down to ≥30 per stratum if reading cost
  demands, but report the sampling rule.
- **Secondary**: the email discordant pairs (B wrong where A right — the cells
  that *produce* the −0.117) — full transcript reads, both arms. This overlaps
  764's question-audit interest by design: this lane answers "what did the tool
  arm do worse, mechanically"; 764 answers "was the question measuring
  anything". Coordinate via umbrella §L, don't merge lanes.
- Out of scope: A-arm anatomy (765), question quality verdicts (764), engine
  fixes themselves (file, don't fix — log to register/observations).

## §C. Method

1. **Extract**: from each failed cell, pull `tool_call_sequence` (per-run record
   `out/utility-comparison.v1.json`) + the full transcript (Inspect log in
   `logs/`) → the ordered list of issued search queries + returned result sets
   (`tool_result_digests` identify results; the transcript carries the visible
   payloads).
2. **Gold mapping**: `fabricated-queries.json` gives qid → question + gold
   answer + gold-provenance (which docs carry the answer).
3. **Replay**: boot the eval backend with the prebuilt index —
   `python -m jseval serve-up --index-cache-mode on --corpus-dir <corpus dir>`
   (or the equivalent invocation recorded in
   `tmp/confirm2/chain-confirm.log` / `chain-confirm-v5.bat.txt` in the evidence
   dir) — adoption from `tmp/index-cache` takes seconds, not an indexing run.
   Re-issue each agent query verbatim against `/search`; record rank of gold
   docs in top-k. Then, for B1-candidates, issue 1–3 reasonable reformulations
   (a human-obvious rewrite of the question; small API budget MAY be used to
   generate reformulations — 762 §C.2).
4. **Classify** each cell into B0–B5 by the first broken link, with the
   file/qid/rank evidence inline. Ambiguous cells get a bucket + a flag, not a
   sixth bucket.
5. **B5 screening**: cells where the transcript's final answer looks
   plausibly correct → hand the (qid, answer, gold) triple to lane 764's judge
   audit rather than adjudicating here.

## §D. Deliverables & acceptance

1. **Taxonomy table**: per stratum, count + share per bucket, with the sampling
   rule stated. Acceptance: every sampled failed cell has exactly one bucket +
   evidence pointer (qid, log file, rank numbers).
2. **Exemplar dossier**: ≥2 fully-narrated exemplar cells per non-empty bucket.
3. **Engine-bug list**: every B2 with query, expected doc, observed top-k, and
   a reproduction command. These become search-quality register entries
   (F-numbers) at lane close — that register update is part of DONE.
4. **§L rows for 762**: one lever row per bucket that holds ≥15% of failures
   (e.g. "B2 engine-miss on boolean-ish legal citations — fix X, expect +Y").
5. Implementation log in this tempdoc; observations for out-of-scope finds.

## §E. Constraints & practicalities

- Inherit 762 §C (no paid runs; small API ok; GPU/dev-stack takeover authorized
  — but `quick_health` + lease rules still apply; step2-powered worktree is
  read-only source data — do NOT edit or remove it).
- jseval env: `PYTHONPATH=<step2-powered>\scripts\jseval`, `PYTHONUTF8=1`,
  `INSPECT_DISPLAY=none` (762 §D).
- Reading Inspect logs: use `inspect_ai.log.read_eval_log` (or jseval's own
  reader helpers in `jseval/agent_utility_inspect.py`) — the `.json` eval files
  in `logs/` are large; do not raw-Read them into context.
- Time budget: replay is minutes per boot; the expensive part is transcript
  reading — delegate per-stratum reads to pinned workers, keep classification
  judgment with the lane orchestrator.

## §F. Results (2026-07-21)

Two phases, both complete. Artifacts: `tmp/analysis-624/763/` (dossier,
156-cell summary CSV) + `763/replay/` (raw top-20 per query, per-cell
classification, reformulation probes). Census, not sample: all 127 failed
B-cells classified.

**Final taxonomy (per stratum, count):**

| stratum | B0 | B1 | B2-hard | B2-marginal | B3 | B4 | B5 | total |
|---|---|---|---|---|---|---|---|---|
| legal-1k | 0 | 0 | 3 | 0 | 14 | 31 | 0 | 48 |
| legal-10k | 1 | 0 | 12 | 2 | 17 | 18 | 0 | 50 |
| email-1k | 0 | 0 | 0 | 0 | 4 | 14 | 0 | 18 |
| email-10k | 0 | 0 | 0 | 0 | 4 | 7 | 0 | 11 |

**Headlines.** (1) The engine owns 17/127 = 13.4% of with-tool failures —
all legal, scaling 6%→28% from 1k→10k: the bridge-entity retrieval miss,
promoted to **search-quality register F-039** with reproductions; fix lane =
tempdoc 769. (2) B1 = 0: no reformulation rescued what the agents' own 4–12
reformulations missed — bad-query is not a driver. (3) B3 = 39 (31%), the
largest addressable bucket — the engine RETURNED gold within the
agent-visible k (28 via `justsearch_answer`'s passage pack → effectively
synthesis failures; 11 via `justsearch_search` rank 1–7, never opened →
salience/ergonomics, feeds tempdoc 770). (4) B4 = 70 — dominated by
partial-hop behavior (opened hop-1, extracted the bridge fact, never fetched
hop-2); with B3-answer, ~77% of all failures are model-synthesis-owned
(prior §P.1 confirmed). (5) B5 = 0 and B0 ≈ 0.

**Charter corrections discovered (code/data win):** §C.1's "the transcript
carries the visible payloads" is FALSE — search-result payloads are redacted
to sha256/len before persistence (`agent_utility_inspect.py:481-490,
877-883`), which is why phase-1's "gold never returned" lean was wrong for
39/56 replay cells and why live replay was load-bearing. The durable fix
(capture ids+ranks at run time) is chartered as tempdoc 768. Also: per-cell
observability fields live in the Inspect log sample metadata, not in
`out/utility-comparison.v1.json` (aggregate-only) — 762 §D described this
inaccurately at charter time.

**Adopt verification:** all four strata warm-adopted the exact campaign
index entries (git_sha 3ed766b7) in ~16s each, doc counts 1001/10001
verified; selector bypass via `index_cache.lookup(known_selector_key)` was
required because HEAD had advanced past the campaign commit — noted for 768
(replay tooling should support pinned-entry adoption first-class).

**Register duty discharged:** F-039 filed (this PR). Email discordant reads
fed 764's email verdict (single answer-shaped search → hop-1 overconfidence
vs grep-forced thoroughness).
