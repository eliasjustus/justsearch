# 715 — Release re-baseline + scorecard recompose (post-F-031/F-032)

- **status:** EXECUTED 2026-07-16 (see §Execution record below) — was: seed — measurement program task, founder-scheduled (chartered 2026-07-11 from
  the 711 close-out; deliberately NOT agent-assigned — runs on the shared GPU/dev stack and
  publishes public-facing numbers, so it wants a coordinated single session)
- **created:** 2026-07-11

## Charter

Every published Release Scorecard number predates both F-031 (long-doc single-pass dense,
default-on 2026-07-11) and F-032 (all chunk vectors silently destroyed at prior HEAD; RMW
preservation recovers them — legal vector 0.3401→0.6180, hybrid 0.5446→0.5592 measured). The
scorecard (`docs/reference/search-quality-register.md`, generated block) still projects
`667-external-baselines-2026-07-01` — e.g. legal-clerc hybrid 0.516 vs the measured 0.56+ at
current HEAD. Corpora with chunked long docs (enron-qa especially) very likely also carried
dead chunk vectors in their pinned baselines.

Work: one cohort-identical release run across the five catalog corpora (beir/scifact,
mixed/enron-qa, mixed/legal-clerc-200, mixed/miracl-de-2k, mixed/miracl-fr-2k) at shipped
defaults on the standard hardware; regenerate `scripts/jseval/release.v1.json` → scorecard via
`node scripts/docs/register-headline-sync.mjs`; refresh the ratchet baselines that project
from the release (relevance/union-recall per their `--update-baseline` flows); reconcile
per-corpus "Best known" lines; mark pre-F-032 vector/hybrid rows as dead-chunk-vector
ablations where applicable (the legal-clerc block already has this treatment from 711).

## Notes

- Public-claims discipline: the scorecard IS outward-facing — every number must come from the
  one cohort run, no hand-typed values (tempdoc 623 mechanism).
- Expect enron-qa vector/hybrid to move (long chunked emails); scifact/miracl likely small
  (short docs, few chunks) — but measure, don't assume (interrogate-results).
- Engine-performance table refresh rides along (same run emits CE p50 / docs/s / resident).
- Related: tempdoc 623 (scorecard mechanism), 667 (current release), 691/711 (the two findings
  that staled it), F-031/F-032.


## Execution record (2026-07-16, session 109145ac — founder "proceed" 2026-07-16)

One cohort-identical run across the five catalog corpora at shipped defaults
(HEAD content == origin/main post-#207 + the two harness-integrity fixes on this
branch), composed as release `715-rebaseline-2026-07-16`; scorecard regenerated
via `register-headline-sync.mjs`; relevance floors re-pin automatically (the
baselines file is a pointer projecting from `release.v1.json` — no hand edits).

| corpus | hybrid nDCG@10 (new) | old scorecard | R@10 | tier |
|---|---|---|---|---|
| mixed/legal-clerc-200 | **0.5982** | 0.516 | 0.765 | A |
| mixed/enron-qa | **0.7359** | dead-chunk-era (depressed) | 0.850 | A |
| beir/scifact | 0.7604 | ~parity | 0.888 | A |
| mixed/miracl-de-2k | 0.8619 | ~parity | 0.997 | A |
| mixed/miracl-fr-2k | 0.8726 | ~parity | 1.000 | A |

Notable findings from execution (each root-caused, fixed on this branch):
1. **Manifest embed-compat REBUILDING stamp on fast runs** split the cohort key
   twice; fixed by a bounded settle-wait in `ingest_and_wait` — and the fix is
   not cosmetic: legal-clerc hybrid measured 0.5609 on REBUILDING-stamped runs
   vs **0.5982 settled** (+0.037) — unsettled runs UNDERSTATE retrieval quality.
2. **False-degenerate chunk-completeness verdict** on corpora the engine
   legitimately classifies short (SKIPPED_SHORT_CORPUS): the 718 corroborator
   now honors engine-declared skip reasons (`chunk_merge_skip_reason_counts`).
3. Perf-family relaxations accepted with recorded causes (`.changesets/
   release-*-tempdoc715.md`): scifact CE p50 +4 ms (noise scale); scifact
   primary docs/s 111→90 (the KNOWN 691 primary-indexing drift + session CPU
   contention). Quality floors relaxed nowhere; every corpus improved or held.
