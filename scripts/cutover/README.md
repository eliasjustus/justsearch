# `scripts/cutover/` — go-public cutover preflight tooling

One-shot tooling for the irreversible go-public flip (tempdoc **634**). These are **not** CI gates — the
cutover happens once, so they are run by hand at the flip, not wired into `ci.yml` (a continuous gate for a
one-shot event would be over-engineering; tempdoc 634 §T5). They are published under Option C as part of the
"here's exactly how we went public" transparency story.

## `check-snapshot-includes.mjs` — the include/exclude linter
Validates that a candidate **public snapshot tree** applies the Option-C include/exclude correctly
(mirrors `cutover-runbook.md` §1):
- **EXCLUDE** — the strategy sidecar (`docs/business/`, `docs/market-analysis/`), the local-runtime bits
  (`.claude/settings.local.json`, `.mcp.json`, `tmp/`, `.claude/worktrees/`), machine-local tempdoc 390 result
  artifacts, and the model `*.onnx` LFS blobs must not be present.
- **CLOSURE** — the full agent/governance machinery dependency closure must be present (631 C1 — the narrower
  "hooks/-only" list ships broken machinery).
- **SETTINGS** — `.claude/settings.json` must be the guards-only public template (no `permissions`/`env`; the
  4 founder-analytics hooks excluded). Strict mode only.

```
node scripts/cutover/check-snapshot-includes.mjs [<treeRoot>] [--source]
```
`--source` checks the **private source repo**: EXCLUDE hits are expected (it prints the strip-list), CLOSURE is
still enforced. Run it on the source before producing the snapshot; run it **strict** (no `--source`) on the
produced snapshot — any EXCLUDE hit then is a leak and fails.

## `preflight.mjs` — the go/no-go board
Composes the existing oracles (the linter, `gitleaks`, the discipline-gate kernel, the public-host test, the
dangling-ref sweep, build/test, and the freeze invariant) into one status board. Mirrors the dev-stack
`preflight`.

```
node scripts/cutover/preflight.mjs                 # PREP dashboard (light checks; rest PENDING with commands)
node scripts/cutover/preflight.mjs --gates         # also run the full discipline-gate kernel (heavy)
node scripts/cutover/preflight.mjs --flip --full <snapshotTree>   # the real gate, on the snapshot, before push
```
Exit 0 = nothing actively failed; non-zero = a check ran and FAILED. PENDING/SKIP never fail (PREP is a
dashboard). Under `--flip`, every runnable check is enforced and the deferred `main`-green is cleared via the
gate kernel.

## Tests
```
node scripts/cutover/check-snapshot-includes.test.mjs
```
