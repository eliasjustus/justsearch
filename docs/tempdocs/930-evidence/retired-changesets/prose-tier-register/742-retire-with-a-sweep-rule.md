---
classification: new-rule-registered
tempdoc: 742
---
New rule 43 (`retire-with-a-sweep`) registered at `prose-only` tier in the
CLAUDE.md Agent Discipline section.

Tempdoc 742's history-survivorship audit catalogued ~350 files of residue from
un-swept retirements (the Playwright e2e tier, React shell scaffolding, JNI and
VMware script families, the lingui layer, two silently inert gates). The
ADR-named retirements (FFM engine, ai-worker, pipeline DAG) left near-zero
residue; the un-named ones leaked everywhere — the difference was whether the
retirement carried its own fingerprint sweep. This rule makes the sweep part of
the retiring PR's contract. Prose-only because "a retirement happened" is a
judgment event with no mechanical detector; the gate-input contract (also 742)
covers the gate subclass mechanically.
