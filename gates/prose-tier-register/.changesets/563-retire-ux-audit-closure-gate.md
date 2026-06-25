---
classification: rule-retired
slot: ux-audit-closure
tempdoc: 530
---

Retires the `ux-audit-closure` rule (tier-register row 31, tier `gate` →
removed) and its `ux-audit-closure` discipline gate. Same rationale as the
sibling `independent-review` retirement: the gate blocked merges on an
independent, live, measured UX audit (auditor ≠ committer) for any touched
presentation scope — expensive to satisfy honestly and prone to rubber-stamping,
and it false-failed on pure no-UX-delta refactors (a type-extraction that moved
two scoped files past the audit's `coversThrough`). See tempdoc 530 §Remediation
(audit-gate removal) for the full rationale and removal map.
