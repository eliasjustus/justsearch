---
title: "808 — Sandbox harness evidence yield: close the false-green channels, stop destroying information"
status: "design settled 2026-08-05; implementation licensed (owner: 'update the releases workflow autonomously accordingly'); must land BEFORE round 14 is staged"
created: 2026-08-05
updated: 2026-08-05
related: [734, 798, 804, 806, 807]
---

# 808 — Sandbox harness evidence yield

## Context

An evidence-lifecycle audit (2026-08-05, this session) mapped every artifact a validation
round produces against what consumes it. Verdict: the round earns trust well (fail-closed
staging, evidence tokens, reader gate, planted-defect calibration) and compounds knowledge
poorly. Four gaps were ranked; the owner approved three for implementation before round 14
— the fresh-install confirmation round that 807 names as the last step to a clean 0.2.0
qualification.

The findings split into three kinds:

- **Machine-invisible signals (false-green channels).** The 13 `mustWatch` items are
  re-injected into every brief (`gen_coverage_brief.py`) with `validateHow` notes, yet
  `check_coverage.py` never references them — a round can observe nothing on all 13 and
  exit 0. This is the campaign's own signature defect class ("a recorded claim nothing
  verifies") applied to the harness itself. Separately, `collect-evidence.ps1` detects the
  round-10 false-green class (all GETs green while every POST 401s) but deliberately keeps
  exit 0 and writes the verdict only to console + `collect-evidence-summary.txt`, which no
  host-side checker reads.
- **Irrecoverably destroyed information.** The API ladder writes fixed filenames, and
  sandbox-CLAUDE.md instructs "run it early and after each major step" — each run
  overwrites the last; the product's progression through install/enrichment is
  unrecoverable. The round agent's session-level self-analysis is unmandated: round 12's
  self-initiated `session-analysis-round12.md` produced ~11 adopted harness fixes (734
  §3181-3243) — the highest-yield artifact of the campaign — and nothing collects it.
- **No longitudinal layer.** Archives are moved, never read. **Deferred** (see Scope).

## Items (owner-approved 2026-08-05)

### I1a — mustWatch verdict record, graded at finalize

The round writes `evidence/mustwatch-verdicts.v1.json`:

```json
{ "schema": "mustwatch-verdicts.v1",
  "items": [ { "id": "<mustWatch id>",
               "verdict": "observed-pass | observed-fail | unobservable",
               "note": "<what was actually seen / why unobservable>",
               "evidence": ["<optional artifact filenames>"] } ] }
```

`check_coverage.py` gains `check_mustwatch_verdicts`: **fail-closed** on missing file,
on an item set that does not cover every mode-included mustWatch id in
`coverage-manifest.json` (the manifest already carries them), and on an invalid verdict
enum. **Verdict semantics stay judgment**: `observed-fail` prints prominently in the
report but does not flip the exit code — severity flows through the findings process,
same as any defect. `unobservable` requires a non-empty `note` (mirrors the register's
`blocked-by-posture` honesty rule for `install-trust-prompts`).

Rationale for grading recording, not outcomes: the gate's job is to make the *claim
of observation* verifiable, exactly the write-time witness shape 798 D2 built for the
data plane. Deciding what a failed watch means is the round agent's + owner's call.

### I1b — mutating-surface probe verdict becomes machine-visible

`collect-evidence.ps1` additionally writes `evidence/mutating-probe.v1.json`
(`{ "schema": "mutating-probe.v1", "status": "pass|fail|skipped", "detail": "..." }`;
`skipped` only when the backend was never reachable, with detail). `check_coverage.py`
gains `check_mutating_probe`: **fail-closed** on missing file or `status: "fail"`;
`skipped` prints a prominent warning (an unreachable backend already fails coverage
elsewhere). This wires three-rounds-tested detection into the exit code — completing
existing machinery, not debuting new machinery, hence fail-closed immediately.

### I2 — mandated session self-analysis

`sandbox-CLAUDE.md` mandates `evidence/session-analysis.md` at round end: what the
harness/charter/instructions made hard, what was done off-charter and why, what a next
round should do differently — the round-12 shape. `check_coverage.py` gains an
existence + ≥400-non-whitespace-byte check (same floor as the retrospective), content
ungraded (the retrospective's "deliberately dumb" precedent). Trivially satisfiable,
so the gate debut is low-risk; the value is that the artifact exists at all.

### I3 — timestamped API ladder + collect-run log

`collect-evidence.ps1`: each ladder snapshot is *additionally* written to
`evidence/api-history/<UTC yyyyMMdd-HHmmss>/api-*.json`; the fixed-name latest copies
stay (compat: `check_golden_parity.py` reads `api-api-knowledge-status.json` by name).
Each invocation appends one line to `evidence/collect-runs.ndjson`
(`{ts, mode, backendReachable, ladderOk, mutatingProbe}`). No grading — this is pure
information preservation; the time-series is what rounds 10-13 could not reconstruct.

### Pre-staging fixes riding along

- `governance/sandbox-coverage.v1.json`: `shapeCoverage["core.extract"].reach.testid`
  `schema-attach` → `escalation-structured` (the 807 W2 docked-strip route; the old
  pointer is the exact route round 13 could not use after a search). Verify the testid
  exists in `UnifiedChatView.ts` before editing.
- `docs/how-to/cut-a-release.md`: the v0.2.x release-index row still reads "rounds 1-6
  … DO-NOT-QUALIFY" — seven rounds stale. Update to reflect rounds 7-13 and the 807
  "one fresh-install confirmation round" state. Add the three new mandatory evidence
  artifacts to the qualifying-round description.

## Scope discipline

- **Deferred: cross-round archive index.** No consumer exists yet; building it now is
  apparatus (798 D4). The licensing instance is the next manual archive archaeology.
  Archives are already preserved move-not-delete, so deferral loses nothing.
- **Not built: NLP/quality grading of any prose artifact.** The retrospective keyword
  gate's honesty ("cannot judge QUALITY") is the ceiling for all three new checks.
- **No change to golden-parity assertions.** The unread leg scores / stage timings in
  captures are a separate, owner-gated question (734 finding 5 is descriptive by owner
  decision 2026-07-30; do not re-tighten by the back door).

## Bite proof (required, per plant_defects norm)

Every new check ships with tests proving it catches known-bad input:
missing `mustwatch-verdicts.v1.json`; verdict set missing one mode-included id; bad enum;
`unobservable` with empty note; missing `mutating-probe.v1.json`; `status:"fail"`;
missing/undersized `session-analysis.md`. Plus green-path tests. Wiring alone is not
evidence (798 D2d).

## Sequencing constraint

This lands before round 14 is staged, so round 14's brief/gates include the new
requirements from the start. Round 14's charter must name the three new artifacts so the
in-sandbox agent (which reads only the staged brief + sandbox-CLAUDE.md, not this
tempdoc) produces them.
