---
title: "passage contextualization recipe: recover the measured context-starvation share of the long-doc floor (2x+ at 10k offline) without the real-task cost the naive prefix pays"
type: tempdocs
status: "chartered (2026-07-22, rides with the 774 PR). Measurement-first; no implementation licensed before a recipe clears BOTH pre-registered bars."
created: 2026-07-22
author: agent (Fable orchestration), chartered from tempdoc 774's H.4 A/B (F-040)
category: search-engine / retrieval-representation / enrichment
related:
  - 774-passage-first-retrieval-program   # F-040/§J.7: the evidence base; probe + A/B harness
  - 777-resident-llm-ranking-activation   # the resident-LLM contextualization tier couples here
  - 776-benchmark-v2                      # gold-feature parity duty (the title-leak bycatch)
---

> Thin charter. The evidence and the harness exist (774 §J.5/§J.7, F-040);
> this lane's job is to find a chunk-contextualization recipe that keeps the
> camouflaged-floor recovery without the real-task regression, then take it
> through the D-004 flow engine-side. Load `/search-quality` before work.

# 780 — passage contextualization recipe

## §A. Evidence base (banked — do not re-derive)

F-040: the legal-10k "representation floor" is substantially **context
starvation of isolated chunk embeddings** — a uniform, leak-free 150-char
doc-lead prefix on the chunk-embed input recovers R@100 0.20 → 0.42 (2.1×)
and median gold-parent rank 887 → 188 offline, but costs −0.04 R@10 on the
real-task Gate-0 control (fails the pre-registered ±0.03 tolerance). A
title-prepend variant measured bigger gains and was INVALIDATED (gold-only
title leak in the 767 strata — 776's lane). Harness + per-cell artifacts:
`tmp/analysis-624/774/probe/` on the 774 branch (`probe_774.py`, `h4_ab.py`,
Gate-0 anchored to F-034/F-030).

## §B. The lane's question

Which contextualization recipe keeps ≥ the naive prefix's camouflaged-floor
recovery while staying within Gate-0 tolerance on the real task? Candidate
recipes (offline first, cheapest first; all D-003-clean, per-doc signals
only): shorter/structured lead (e.g. 50 chars); heading-path + title for
real files (both already stored per chunk); lead-sentence selection rather
than raw head; prefix on the EMBED input only vs indexed text; conditioning
weight (prefix embedded but downweighted); 777-coupled resident-LLM chunk
context (Anthropic contextual-retrieval shape) as the expensive tier, gated
on the cheap tiers failing.

## §C. Pre-registered bars (both must hold before any engine-side flag)

1. Camouflaged cells: ≥2× baseline MaxP R@100 at 10k OR median gold-parent
   rank ≤ half baseline (F-040's rule, unchanged).
2. Gate-0 (legal-clerc-200): R@10/nDCG@10 within ±0.03 of baseline.
Then: engine-side enrichment flag per D-004 (default-off → sentinel A/Bs on
enron/scifact/legal incl. enrichment-throughput ratchets (640) — the prefix
lengthens every chunk-embed input — → founder default decision). Chunk
re-embedding is index-rebuild-scale: cost stated in the design, not
discovered.

## §D. Constraints

D-003/D-005 bind (per-doc signals only, no corpus routers). 767-strata
measurements must use leak-audited features only (no `title` on those corpora
until 776's parity fix lands). Register duties: results land as F-numbers;
this lane closes F-040's "chartered follow-up" pointer either way — including
the honest outcome "no recipe clears both bars" (that closure is a valid
result, not a failure).
