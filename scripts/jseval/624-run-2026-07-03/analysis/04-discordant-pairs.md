# 04 — Discordant-pair trace deep-dive (agent report, 2026-07-03, verbatim)

**Reproduction (confirmed):** recomputing from `scores.substring_scorer.value` reproduces the pooled
split exactly: EN 15 fixed / 24 broke, DE 17 fixed / 15 broke (130 keys per corpus). Cross-checked
against each `judge-overlay.json`: `judge_flips: 0` in both — no hidden correction changes counts.

**Load-bearing pre-finding: condition B never called the MCP tool.**
`agent_retrieval_eval.py:1038` (`parse_claude_stream_json`) captures every `tool_use` block by literal
name with no allowlist — it would have recorded an `mcp__...` call. Scanning all 260 B samples in the
certified logs (and every prior EN run in `logs-en`, `-v2`, `-v3`, `logs-repro*`, `logs-probe-inspect`)
turns up **zero MCP invocations, in every single run recorded**. Tool surface in A and B is identical:
`{Bash, Grep, Read, Glob, PowerShell}`. All MCP-referencing candidate categories are moot (0
occurrences).

## Classification (data-derived, pooled n=71)

| Category | FIXES (B rescues A) | BREAKS (B breaks A) | Total |
|---|---|---|---|
| **gave-up-abstained** — explicit "does not exist in the corpus" refusal | 22 | 20 | **42 (59%)** |
| **never-retrieved-any-chain-doc, no abstain** — confidently wrong chain, silently substitutes a different entity | 8 | 16 | **24 (34%)** |
| **retrieval-gap-partial-chain, no abstain** — found 1–2/3 chain docs, guessed the rest | 2 | 3 | **5 (7%)** |
| correct-info-in-hand-reasoning-failed (all 3 gold docs read, still wrong) | 0 | 0 | **0** |
| MCP-related categories | — | — | **0 (tool never called)** |

Read-file overlap with `evidence_ids` was the directly-observed signal for "reached the gold doc";
abstain detected via EN/DE regex over completion text.

## One dominant mechanism

**One dominant axis in two surface behaviors**: whether that stochastic rollout happened to map the
query's paraphrase vocabulary onto the corpus's literal templated vocabulary (EN: "power
station"→reactor, "upper wetlands"→northern marshlands, "streetcar line"→tramway; DE:
"Kraftwerk"→Reaktor, "Verlagshaus"→Druckerei, "Beobachtungsstation"→Sternwarte). Get the mapping →
find the doc → win. Miss it → abstain (59%) or grab a same-descriptor sibling and answer confidently
wrong (34%). **No case (0/71) of an arm holding all three gold docs and answering wrong** —
divergence happens at retrieval/mapping, never at synthesis.

Corpus confusability context: 26/26 gold chain-heads have ≥1 same-TYPE distractor (avg 8.73) and ≥1
same-PLACE distractor (avg 4.00) among the 130 chain-head docs. One exact-title collision corpus-wide
(`olmker298`/`tasmond127`), not gold-involved. ID-level: 234/390 EN docs (60%) share a name-prefix
with a differently-numbered unrelated sibling — but only 1/71 losing arms literally Read a same-prefix
sibling of a true evidence doc; the dominant confusion is at the descriptor level (shared
ordinal/type/place words triggering a wrong Grep hit), not literal ID collision.

## Seed-stability: seed-scattered, not systematic

**0/26 qids in either corpus are arm-differential** (stably solved by one arm, stably failed by the
other). EN: 19/26 seed-unstable, 7 stable-solved-both. DE: 19/26 unstable, 5 stable-solved, 2
stable-failed. Excluding the 3 most-unstable qids per corpus barely moves pooled accuracy. Grep/turn
medians for losers vs winners are near-identical (abstain losers: median 5 greps/21 turns vs winners'
5/24.5) — ruling out budget exhaustion. Given B never calls its extra tool, A and B are two
independent stochastic rollouts of the same agent; the discordance is resampling noise on the
paraphrase-mapping gamble.

## Exemplars (compact)

1. **EN q5, B breaks A** — "…optical instrument…Carpathian uplands, sixth installation" → target
   `crimson brannik 0018`. Both reach `druker16.txt` ("designed by Olmholt17"). A reads
   `olmholt17.txt`→`kanfen18.txt`→correct. B reads **`olmholt14.txt`** — real but unrelated
   ("Olmholt14 was founded by Zelreach15") — and confidently answers `indigo lansk 0015`. Verified
   same-prefix wrong-suffix substitution.
2. **EN q20, B fixes A** — "…publishing works in the ore working, first installation" → target
   `umber vellum 0063`. A's grep for "ore|installation" surfaces `orrfen355` (vineyard) and chains
   wrong. B's tighter combined pattern (`designer.*publishing|publishing.*designer`) lands
   `drudac61`→`falvale63`, correct.
3. **EN q16** — the same "Olmholt17"→`crimson brannik 0018` wrong-chain signature recurs as A's
   failure on a different query — a standing decoy-chain bias under ambiguous "designer/founder"
   greps.
4. **EN q11/q19 (both directions)** — loser stops at a plausible ordinal+type match one hop early;
   winner's grep combines type+place+ordinal in one pattern instead of iterating loosely.
5. **DE q1 (both directions occur)** — "Beobachtungsstation, Höhenzug im Osten, zweite Anlage" →
   loser enumerates the corpus's 12 canonical building types, doesn't see "Beobachtungsstation"
   literally, concludes "existiert nicht in den bereitgestellten Dokumenten" — textbook
   paraphrase-abstain (Beobachtungsstation≈Sternwarte).
6. **DE q22, B breaks A (epoch 5)** — A succeeds citing `quenfen67.txt` content **without ever
   issuing a Read on it** — almost certainly a `Grep --output_mode content` hit (inferred; results
   aren't logged). Caveat on the Read-based evidence-hit proxy: content-mode grep can expose doc text
   without a Read, so "never-retrieved" undercounts true exposure in an unknown number of cases.

## Caveat

"Never-retrieved" is measured via literal Read file_path calls; `Grep --output_mode content` can
surface text without a Read (exemplar 6 is a confirmed instance) — flagged as inferred, not corrected
for, since tool results aren't in the log.
