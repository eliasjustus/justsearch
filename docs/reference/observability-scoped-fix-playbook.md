---
title: Observability Scoped-Fix Playbook
type: reference
status: stable
description: "Engineering discipline for handling silent-failure-class defects in the observability stack."
---

# Observability Scoped-Fix Playbook

When you find a silent-failure defect in the observability stack —
a case where the system produced misleading, biased, or incomplete
output while reporting `status=ok` — this is the engineering
procedure to follow. Consistent application of this pattern is what
keeps silent-failure defects from silently recurring.

## Context

Tempdoc 404 describes an architectural direction (five pillars)
that structurally prevents whole classes of silent-failure defects
(D-1 / D-2 / D-3 type). The patterns in this playbook are the
narrow-scope application of the same principles: contract tests pin
the producer-consumer shape on a per-case basis, mirroring what
Pillar 2 would do across the whole surface.

## What qualifies as silent-failure class

A defect where **all of** the following are true:

1. The system continued to operate (no exception, no fatal log).
2. Downstream consumers received data that looked healthy (passed
   shape checks, schema-valid if any, produced finite metrics).
3. The actual output was misleading (biased distribution,
   incomplete coverage, wrong field, dropped events).
4. Detection required ad-hoc forensics (counting at two layers,
   comparing emitted-vs-mirrored, reading a rotated file someone
   forgot existed).

What doesn't qualify:
- Configuration bugs with clear error messages.
- Feature incompleteness documented in the design.
- Test-environment classpath issues.
- Performance regressions with visible latency impact.
- Documentation errors.

## Procedure — five steps

### Step 1 — Classify the defect

Assign a label `D-N` continuing from the existing series (D-1 D-2
D-3 already taken by tempdoc 400 §23.8 and §23.9). Next defect
is D-4.

Add a log entry to `docs/observations.md` under the **Silent-failure
log** section (not the Inbox):

```text
- [ ] 2026-MM-DD — <description> — pillar(s) that would have caught
  structurally: <1/2/3/4/none> — severity: <low/med/high> —
  site: `file:line` — resolution: <scoped fix | needs tempdoc |
  accepted limit>
```

**Pillar classification rubric** (from tempdoc 404 §3 + 405 §2.1):
- Pillar 1 would catch: artifact-loss / lineage-break defects.
  Example: D-3.
- Pillar 2 would catch: producer-consumer shape drift.
  Example: D-1.
- Pillar 3 would catch: biased-input or silent-coverage-loss at
  projection time. Example: rate_timeline window too short.
- Pillar 4 would catch: cost regression / detection threshold
  drift. Example: §23.9.4 #8.
- None: OS/process-boundary (D-2), documentation, feature
  incompleteness.

### Step 2 — Root-cause without jumping to architecture

Ask: **is this defect's class already represented in the log?** If
yes and this is the ≥3rd instance within 90 days, re-open tempdoc
404 planning instead of scoped-fixing. If no, proceed with scoped
fix.

Identify the narrowest scope that eliminates the defect:
- Single file + test: ideal.
- Single module: acceptable.
- Cross-module (≥ 2 modules touched): consider whether a
  tempdoc is warranted.
- Language boundary (Python+Java): tempdoc is warranted.

### Step 3 — Fix with contract-test coverage

The fix's scope is narrow, but the **contract test pattern** is what
keeps the defect from silently returning.

Template (derived from the Workstream C `test_projections_rate_timeline.py
::TestMetricNdjsonContract` pattern):

```python
class TestXxxProducerConsumerContract:
    """Tempdoc 400 §23.9 follow-up — pin the producer/consumer
    contract for <kind>.

    Defect class: <D-N>. Pre-fix pattern: <what the silent failure
    looked like>. Without this contract test, fixture-based unit
    tests alone would not catch producer-side drift.
    """

    @staticmethod
    def _real_shape_fixture(...) -> str:
        # Construct data byte-for-byte matching what the actual
        # Java/Python producer emits. Reference the emitter file +
        # line number in a comment — this is the ONE place the
        # producer shape is pinned on the consumer side.
        return json.dumps({
            # ... fields matching the producer's output ...
        }, separators=(",", ":"))

    def test_parses_real_shape(self, ...):
        # Feed byte-for-byte producer output to the consumer.
        # If producer shape drifts (field rename, new required
        # field, format change), this test fails — loudly.
        ...

    def test_all_required_attrs_round_trip(self, ...):
        # Parametrize over the required-attr/kind/field list.
        # Every required item is validated separately so a rename
        # of any one surfaces distinctly.
        ...

    def test_unknown_variants_ignored_not_crashed(self, ...):
        # The failure mode where unknown field values crash the
        # consumer is rarer than silent drops; pin which the
        # consumer does.
        ...
```

Where the contract test lives:
- Python consumer + Java producer: Python test file, `_real_shape_fixture`
  comments reference the Java emitter file + line.
- Python consumer + Python producer: Python test file.
- Java consumer + Java producer: Java test file.

Do NOT rely on synthetic fixtures that replicate only the fields the
consumer uses. D-1 hid for months because its fixture did exactly
that.

### Step 4 — Commit discipline

One commit per fix. Commit message template:

```text
fix(400 §23.9 D-N): <brief description>

<1-2 paragraph description of the silent-failure pattern and why
it hid before.>

<1 paragraph description of the fix.>

<N new regression tests in TestXxxProducerConsumerContract:
 - test 1 ...
 - test 2 ...>

pytest scripts/jseval/tests/ → X passed (was Y + N new D-N).

docs/observations.md — D-N marked resolved with fix date + summary.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

### Step 5 — Update the log

After shipping:
1. Update the `docs/observations.md` silent-failure log: change
   `[ ]` to `[x]`; append `— fixed YYYY-MM-DD in <commit-sha>:
   <summary>`.
2. Keep the log current as a running engineering record.

## What NOT to do

**Do not:**

- Fix without a contract test. The fix is the easy part; the test
  is what prevents silent regression. No test → the defect will
  recur.
- Generalize the fix to adjacent code speculatively. If
  rate_timeline had the same class of bug as encoder_drift, file
  a separate D-N entry; a single "fix both at once" commit hides
  the recurrence signal.
- Skip the pillar classification. The classification keeps the
  engineering record crisp.
- Let the fix linger in observations.md. Convert `[ ]` → `[x]` at
  commit time, not later.
- Open a new tempdoc for every instance. Only when the fix scope
  crosses a language boundary or surfaces a pattern worth
  formalizing.

## Relationship to tempdoc 404 / 405

This playbook and tempdoc 404's five-pillar architecture are two
points on the same spectrum of producer-consumer validation depth.
The playbook's contract tests are narrow-scope applications of
Pillar 2's principle (pinning producer output to consumer
expectations). When 404's Pillar 2 lands, existing contract tests
stay in place — they become schema-backed automatically.

## Concrete examples from the repo

Reference implementations of the pattern already in the codebase:

1. **D-1 fix** (`5bc9bbee3`) — producer-side field addition +
   consumer-side fallback + `TestDurationMsExtractionContract`
   class in `test_projections_encoder_drift.py`. 8 contract tests
   pinning shape.

2. **D-3 fix** (`3ece672e1`) — producer-aware consumer logic
   (rotation-aware mirror) + `TestMirrorTelemetryRotatedSiblings`
   class in `test_artifacts.py`. 6 regression tests.

3. **Workstream C** — proactive contract test added without a live
   defect, based on the retrospective ("rate_timeline reads
   metrics.ndjson; no contract test existed"). 4 tests in
   `test_projections_rate_timeline.py::TestMetricNdjsonContract`.

Each of these is what a correct scoped fix looks like. Copy the
pattern. Resist the impulse to generalize prematurely.

## Review cadence

- **Per-fix:** Steps 1-5 above. ~1-4 hours end-to-end for most
  scoped fixes.
- **Per tempdoc closure:** when a major tempdoc ships, run a
  post-implementation critique (tempdoc 400-style) looking
  specifically for silent-failure patterns in the just-shipped
  code.

## Related

- **Tempdoc 400 §23.9** — the post-followup validation pass that
  surfaced D-3 and motivated 404/405.
- **Tempdoc 404 §3** — the four pillars that define which class of
  defect each would catch; used by Step 1's classification.
- **Tempdoc 405 §7** — the recommendation that makes this playbook
  the canonical path.
- **`docs/observations.md`** — silent-failure log section.
- **`scripts/jseval/tests/test_projections_encoder_drift.py::
  TestDurationMsExtractionContract`** — reference contract-test
  template.
- **`scripts/jseval/tests/test_projections_rate_timeline.py::
  TestMetricNdjsonContract`** — proactive contract-test template.
- **`scripts/jseval/tests/test_artifacts.py::
  TestMirrorTelemetryRotatedSiblings`** — D-3 regression template.
