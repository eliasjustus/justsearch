# test-efficacy changesets

Declare a deliberate, justified drop in a registered seam's mutation test-strength here (tempdoc 555
Pillar C). Without a changeset, a strength drop below the baseline floor fails with
`test-efficacy/silent-regression`.

Allowed classifications:

- `strength-regression` — the seam was genuinely simplified and fewer mutants are now meaningful.
- `seam-retraction` — the seam is being removed from `governance/logic-seams.v1.json`.
- `emergency-override` — escape hatch; requires manual review.

Each changeset requires a `tempdoc:` or `adr:` frontmatter field. Example:

```markdown
---
classification: strength-regression
tempdoc: 555
---
Why this seam's strength legitimately dropped.
```
