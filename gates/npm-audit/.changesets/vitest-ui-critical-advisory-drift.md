---
classification: declared-regression
adr: 0026-manual-ci-triggering
---
Pre-existing on main (NOT this PR's gate work): `ui-web` critical regressed
0 → 2 against the 2026-02-20 baseline. Both criticals are the SAME advisory —
GHSA-5xrq-8626-4rwp (`vitest` + its `@vitest/ui` peer): "When the Vitest UI
server is listening, an arbitrary file can be read and executed."

Accepted as a declared regression because:

- It is a **dev-only test dependency** (`@vitest/ui` is a devDependency; the
  Vitest UI server is never started in CI or shipped in the production Tauri
  bundle), so there is no production exploit path — the advisory requires an
  attacker to reach a locally-running Vitest UI dev server.
- It is **advisory drift, not a code change**: the advisory was published
  after the Feb baseline; this PR changes no npm dependencies. It surfaced only
  because CI is manual-only (ADR-0026) and hadn't run against the live advisory
  DB in weeks.
- It is **fixable upstream** (`npm audit fix` / a dependabot `vitest` bump);
  this declaration is the interim accept until that bump lands, at which point
  the kernel auto-rebalances the count back down (`severity-decrease`).
