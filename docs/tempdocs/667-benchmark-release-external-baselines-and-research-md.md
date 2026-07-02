---
title: "External-baseline population for the 2026-07-01 benchmark release, a new CLERC citation, and a public RESEARCH.md — plus a critical-analysis pass that caught three real problems in the first draft"
type: tempdocs
status: "implemented, with corrections applied after a requested critical-analysis pass (2026-07-01)"
created: 2026-07-01
updated: 2026-07-01
author: agent, prompted by an external grant-strategy investigation (Anthropic Claude Science AI for Science) that needed the release's `external_baselines` field populated and a public research narrative to exist
related:
  - 666-mixed-corpus-reproducibility        # the release this work builds on; fixed the corpus-reproducibility blocker this tempdoc's release_id originally (wrongly) implied it was part of
  - 623-reproducible-benchmark-release      # the release/cohort design this amends
  - 624-agentic-retrieval-eval-rebuild      # the deferred agent-utility work RESEARCH.md describes
---

# 667 — External-baseline population, a CLERC citation, and RESEARCH.md

## What this tempdoc covers

Tempdoc 666 (same day, merged ~2 hours before this work started) fixed corpus reproducibility for
MIRACL-de/fr and replaced CourtListener-200 with `mixed/legal-clerc-200`, but left `release.v1.json`'s
`external_baselines` field empty. This tempdoc: (1) added the missing CLERC citation to the pre-existing
`scripts/jseval/external-baselines.v1.json`, (2) merged the curated external baselines into `release.v1.json`,
(3) wrote a public-facing `RESEARCH.md`, and (4) — after a requested critical-analysis pass — corrected three
real problems the first pass introduced.

## Implementation (first pass)

- Added a CLERC (Hou et al., NAACL 2025, arXiv:2406.17186) external-baseline entry to
  `external-baselines.v1.json` for `mixed/legal-clerc-200`, verified against the actual paper (WebFetch of
  the HTML version, Table 3) rather than a secondary summary: BM25 nDCG@10 = 0.054, Contriever-MSMarco
  (best zero-shot dense) = 0.0422, both measured against CLERC's full 1.84M-document/23.7M-passage
  collection — a ~10,000x larger candidate pool than our 198-document `mixed/legal-clerc-200` corpus.
- Merged `external-baselines.v1.json`'s `baselines` object into `release.v1.json`'s `external_baselines`
  field by direct replication of `release.py:96-99`'s merge logic (`ext = ext_doc.get("baselines", ext_doc)`)
  — **read from source, not executed via the actual `jseval release` CLI**, because no intact
  `eval-results/` run directories could be located in this environment (PR #29's own run data appears to
  have been produced in an ephemeral temp location already cleaned up). This is disclosed here as a real
  limitation: the merge is inferred-correct from source, not empirically verified end-to-end.
- Assigned `release_id` and regenerated the three downstream projections
  (`methodology.md`, `scorecard.md`, the register headline table) via their real generator scripts.
- Wrote `RESEARCH.md` (repo root) and linked it from `README.md`.

## Post-implementation critical-analysis pass (requested, same day) — three real problems found and fixed

1. **`RESEARCH.md` overclaimed relative to what the evidence actually supports.** Its "multilingual-
   competitive... without any per-language tuning" headline didn't carry the corpus-scale/split caveats
   `methodology.md` discloses (our MIRACL subsample uses a smaller sampled document pool than the official
   multi-million-passage collection, on the wrong split) — a reader of the narrative doc alone would get a
   cleaner-sounding claim than the reference doc supports. **Fixed:** the caveat is now stated explicitly in
   `RESEARCH.md` itself, not left implicit in a separate document.
2. **The `release_id` (`"666-ext-baselines-2026-07-01"`) falsely implied this work was part of tempdoc 666.**
   It wasn't — tempdoc 666 was already closed, and this work was never itself documented, unlike every other
   substantive change to this release object. **Fixed:** this tempdoc now exists, and `release_id` is
   changed to reference it (`"667-external-baselines-2026-07-01"`).
3. **`RESEARCH.md`'s characterization of tempdoc 624 was too optimistic**, based on reading only its status
   line ("what's missing is the disciplined measurement pass") without reading its own `§Open uncertainties`
   (`624:1172-1216`). The tempdoc's actual preliminary data shows the **realistic** comparison arm (an agent
   that already has generic file tools, plus JustSearch) measured **+0.00 accuracy / ~8% token savings** —
   not the +0.20/+40% figure from a more favorable but less realistic comparison arm (no file tools at all).
   Tempdoc 624 names this explicitly as "U0 — the overarching uncertainty (resolve FIRST): does the honest
   number *help* the wedge?" — i.e. there is a live possibility that a properly-powered measurement finds a
   genuinely small effect, and the tempdoc's own author suggests the resolution might be to re-frame the
   claim, not just measure harder. **Fixed:** `RESEARCH.md` now states this honestly — the preliminary
   signal is weak/uncertain, and a grant would fund properly powering the measurement to find out, not
   guarantee a flattering number.

Also fixed as lower-severity items from the same review: `composed_at` staleness (the file claimed a
compose timestamp from before this amendment; the honest fix disclosed here is a `notes` entry describing
the amendment method and timing, not a faked recompose timestamp), a stated `RESEARCH.md`/plan inconsistency
(20–50 vs. 20–30 for the sufficiency-study sample size — aligned to 20–30), and a design reconsideration of
whether CLERC's external baseline belongs in the numeric side-by-side table at all given the scale mismatch
is large enough to be a different kind of problem than MIRACL's split mismatch — resolved by removing it
from the rendered table (kept only as a prose citation) rather than presenting a ~10,000x-mismatched number
in the same visual row format as genuinely comparable ones.

## What was not independently re-verified

The pre-existing SciFact and MIRACL external-baseline citations (authored before this tempdoc, already in
`external-baselines.v1.json`) were trusted rather than independently re-checked against their primary
sources in the first pass — inconsistent with the standard applied to the new CLERC citation. Spot-checked
in the critical-analysis pass (see below); full independent re-verification of every pre-existing citation
was not done.

## 2026-07-02 — landed via takeover

The originating session ended before committing this work — it sat as uncommitted changes in
`.claude/worktrees/claude-science-benchmark-release` (branch `worktree-claude-science-benchmark-release`,
no commits of its own). Founder-authorized takeover salvaged it into a fresh worktree and landed it on
`main` via PR, content unchanged from what's described above. `release.v1.json`, `methodology.md`,
`scorecard.md`, and `docs/reference/search-quality-register.md` were re-derived from the copied
`external-baselines.v1.json`/`release.v1.json` edits by re-running their real generator scripts
(`register-headline-sync.mjs`, `gen-scorecard.mjs`, `gen-public-benchmark.mjs`, `skills-sync.mjs`) against
current `main`, rather than copying the dead worktree's stale projection diffs verbatim.
