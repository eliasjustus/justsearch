---
classification: tier-change
tempdoc: 530
---
Rule 3 (`no-legacy-endpoints`) tier moved from `gate` → `lint` per the
Pass-6 confidence audit. The catchesVia field cites `docsApiDriftCheck`,
which is a Gradle task (effectively a lint), not a kernel gate. The new
`Resolves to` column has no `gate:` marker because `docsApiDriftCheck` is
not in `governance/registry.v1.json` — it's lint-tier enforcement.

Pass-6's confidence appendix flagged this as a refuted claim ("§3
no-legacy-endpoints is a gate-tier rule") and the correction is captured
here.
