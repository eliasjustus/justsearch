---
title: "Asserted-measurement provenance: every externally-asserted number should trace to a cohort-identified reproducible run — recognized principle, structure deliberately deferred until the fork bites again"
type: tempdocs
status: proposed
created: 2026-06-21
author: agent analysis (research-channel design reach), filed by agent — STUB / record-only
category: governance / search-quality / projection-vs-fork / meta
principle: "every externally-asserted measurement must trace to a cohort-identified reproducible run; a hand-maintained table of numbers is a fork that drifts — an instance of the canonical-authority-and-projection seam (tempdoc 622), and of CLAUDE.md's projection-vs-fork rule"
related:
  - ../business/research-channel/plan.md            # origin — "Design reach"
  - 623-reproducible-benchmark-release               # the concrete first instance (the eval benchmark)
  - 622-agent-telemetry-native-otel-migration        # same seam, different domain (telemetry as projection)
  - 530-class-size-ratchet-automation                # the discipline-gate kernel that *could* host enforcement later
  - 635-contamination-resistant-eval-corpus          # the INPUT-side first instance (verified materialization binding); surfaced the verification-edge of this principle
  - 646-event-sourced-tempdoc-current-state           # the document-process instance — a tempdoc's current-state as a projection of its append-only log, not a drifting banner
---

> NOTE: Noncanonical working tempdoc — **STUB / record-only.** This intentionally captures a
> *recognized principle and its candidate scope*, NOT structure to build now. Separating
> "recognizing a general principle" from "building general structure" is deliberate (CLAUDE.md
> `structural-defects-no-repeat` vs. YAGNI). Do not build the generalized enforcement from this
> stub — see "Trigger" below.

# 625 — Asserted-measurement provenance (projection-vs-fork, generalized)

## The principle (stated plainly)

**Every externally-asserted measurement must trace to a cohort-identified reproducible run; a
hand-maintained table of numbers is a fork that drifts.** This is the same shape the codebase already
names — CLAUDE.md's *projection vs. fork* rule and the `canonical-authority-and-projection` seam
(tempdoc 622). Tempdoc 623 builds the *first instance* (the eval benchmark release); this tempdoc
records the **generalization**, so the insight is captured without becoming premature abstraction.

## Candidate scope (where else it applies) — named, not built

- **The search-quality register's Canonical Baselines table** — the largest existing fork; its A/B/C
  confidence tiers exist *precisely because* its rows come from heterogeneous runs.
- **`scripts/jseval/relevance-ratchet-baselines.v1.json`** — hand-typed floors; the file's own
  "grandfathered" note is a confession of the fork.
- **The agent-utility "92% / 62%" number** — the extreme case (no run identity at all; two unrelated
  metrics fused). See tempdoc 624.
- **Any business/marketing claim citing a metric** ("0.92 on legal") — third-order forks (a doc quoting
  a register that forked a run). The FTC two-bars concern (580 §11.1) is the same issue, legally.
- **A tempdoc's own current-state — the document-process instance (646).** The principle is not limited to
  *measurements*: a long, active tempdoc's "current state / what's left" is an *asserted state* too, and a
  hand-maintained top banner is a fork that **drifts** (lags the latest append, then lies). The conforming
  shape is the same — derive the current-state as a **projection of the append-only body** (transition markers
  folded into a generated block), never a hand-typed banner. See tempdoc 646 for the design; machinery deferred
  to the same rule-of-three trigger.
- **The INPUT side of every measurement record — a *recorded* identity is not a *verified* one (635).**
  The principle has a twin one layer upstream: *not only must an asserted number trace to a run, the
  run's input identity must be a CHECKED BINDING to the measured artifact, not a label asserted beside
  it.* The run manifest records `corpus_identity`/`doc_count` from the **source** and never verifies the
  live index it measured matches them; 623's `repro.v1.json` pins source *bytes* without a
  measured-against check; 640's perf-family inherits the same. **635 built the first instance** — its
  corpus gate now re-materializes when the cache's `corpus_signature` ≠ the source (the
  input→materialization binding), so a stale cache can no longer silently certify the wrong corpus.

## Existing violations

The register table, the ratchet file, and the strategy/business docs' quoted numbers already violate
the principle — and have already produced the drift tempdoc 623 had to correct.

**Input-side (635):** the run manifest's `corpus_identity`/`doc_count` are recorded-not-verified against
the measured index, and the ingest readiness keyed its doc-count on the materialization cache rather than
the source — so a regenerated corpus silently re-ingested a stale cache (nDCG 0.0 / a cert claiming a
corpus it never checked it measured). 635 fixed this **for its own ingest path**; the manifest /
`repro.v1.json` / 640 perf-family still carry the unverified-input hole.

## Trigger (why deferred, and when to act)

The *present* problem (the research channel) needs only 623's release projection for the eval
benchmark. Generalized enforcement (e.g. a kernel gate that fails when an asserted number lacks a
cohort trace) would be structure for cases this problem does not yet include. **Build only when:** the
register fork bites a *second* time, OR a user decision to harden it. Until then this is record-only.
(**635 is the input-side first instance** — built only for its own corpus gate; the GENERAL "every
canonical record verifies its measured artifact against its recorded identity" enforcement awaits the
same trigger — a second/third measured-identity-mismatch beyond the manifest hole already named above.)

## Next step (not done here)

None until the trigger fires. If it does: investigate whether enforcement belongs in the discipline-gate
kernel (530 lineage) or as a projection-generation step in 623, then plan.
