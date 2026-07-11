# 715 — Release re-baseline + scorecard recompose (post-F-031/F-032)

- **status:** seed — measurement program task, founder-scheduled (chartered 2026-07-11 from
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
