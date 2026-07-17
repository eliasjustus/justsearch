---
title: "Cut a JustSearch release"
type: how-to
status: draft
description: "The release loop (build -> clean-Sandbox whole-product verification -> fix -> converge -> finalize), the hash-consistent asset set the build produces, and the per-release index. The single durable home for how a release is cut."
---

## What this does

Describes how a JustSearch release is produced and published. It is the durable "how";
per-release *results* (the round-by-round Sandbox convergence for a given candidate) live in
that release's own working notes, and the shipped record lives in the GitHub Release. This page
is the single living surface — it does not accrete per release (that was the failure mode of the
old rolling packaging log).

> **Status.** The Sandbox verification *loop* has been exercised across many candidates.
> `build-installer.yml` has been dispatched successfully against both `main` and a worktree branch
> (2026-07-15) — dispatching it does **not** require a `v*` tag or create/update a GitHub Release
> unless the ref dispatched against is one.

## The release loop

A release **candidate** is qualified before its number is finalized:

1. **Build** the installer via `build-installer.yml` (`gh workflow run build-installer.yml --ref
   main`, or any branch ref — no tag needed for a validation candidate), then `gh run download
   <run-id>` to fetch the artifact locally.
   **Do not attempt a local build via `package-installer-win.ps1` on a dev machine with Windows
   Smart App Control enforcing** (`Windows Security > App & browser control > Smart App Control`).
   SAC blocks unsigned cargo build-scripts, so the Tauri/Rust build fails partway through with
   `os error 4551` — the script's own preflight catches this and refuses to start (rather than
   burning 10-30 minutes on a build that cannot succeed), and `JUSTSEARCH_SKIP_SAC_CHECK=1` only
   bypasses the *warning*, not the actual OS-level block, so it still fails later. A local build is
   viable only on a machine with SAC off; **the CI path above works regardless and is the default**
   — it has no SAC restriction and produces the identical installer. (First hit + diagnosed
   2026-07-16, tempdoc 734 round-6 prep — this was previously undocumented and each Sandbox round
   independently rediscovered it.)
   CPU-only by default; models + GPU variants land on the user machine via the in-app "Install AI"
   flow (the full model set exceeds the NSIS 32-bit limit).
2. **Verify in a clean Windows Sandbox.** Install the candidate in a fresh Sandbox and let an
   independent agent run a **whole-product** verification pass (search, chat/RAG, MCP, Install
   AI, restart cycles — not just the changed slice). The clean Sandbox avoids dev-machine model
   pollution. Every qualifying round is launched with a **charter** (`sandbox-launch.py
   --charter`): the round's purpose and each open blocker's needs-round / needs-dig
   classification, staged for the verifier and read against the round's debrief at finalize
   (tempdoc 750 Part B). Round modes cover the supported **arrival states**, not only the empty
   machine: first and final qualifying rounds run `fresh-install`, and the qualifying set must
   include at least one `upgrade-from-release` round (previous public release installed first,
   candidate installed over it — tempdoc 750 Part C). *What* to cover is not remembered — it is
   **derived from what the candidate ships:**
   `scripts/sandbox/sandbox-launch.py` generates a per-candidate `coverage-brief.md` from the
   committed surface artifacts against `governance/sandbox-coverage.v1.json` and **fails closed**
   if the build ships a surface not yet classified there (so a new endpoint/panel like `/mcp` can't
   be silently forgotten). The round runs with request tracing on
   (`JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`) and `collect-evidence.ps1` captures the API ladder,
   the `/mcp` Inspector check, and a copy of `traces.ndjson` into the mapped evidence folder. At
   finalize, on the **host** (the sandbox has no Python), against the persisted evidence dir:
   `python scripts/sandbox/check_coverage.py --manifest <share>/coverage-manifest.json --traces
   <share>/evidence/traces.ndjson --evidence-dir <share>/evidence` — a non-zero exit means a
   required surface was not exercised. The round also captures golden-query search responses
   (`evidence/golden/<queryId>.json`), checked at finalize by `check_golden_parity.py` against
   the per-candidate baseline generated from the dev stack on the same build — parity-with-dev,
   not absolute quality, since the Sandbox has no `jseval`. That check fails closed (exit 1, not a
   reported ranking delta) on a model-identity mismatch, an under-staged corpus, or a skipped
   dense-retrieval leg, rather than surfacing any of those as a phantom search-quality regression.
   The durable harness method lives in `scripts/sandbox/*.md`;
   each candidate's round-by-round results live in that release's own convergence tempdoc (linked
   from the Release index below).
3. **Fix and rebuild at the *same* candidate number.** Major findings are reported back, fixed
   in code, and the installer is rebuilt under the same number. Every confirmed regression
   should become a regression test/gate before the next round, so the loop converges instead of
   re-finding the same class.
   **Round-scheduling gate (tempdoc 750 Part B):** after a DO-NOT-QUALIFY, classify each open
   blocker **needs-round** (only a clean install / real GUI / real external client can answer
   it) or **needs-dig** (source-level or dev-stack investigation). Schedule a new round only
   when at least one blocker is needs-round, or the round is the final qualifying round — a
   round is the most expensive verification tier and must not be spent re-confirming a
   needs-dig blocker (0.2.0 rounds 5→6 did exactly that; the classification goes in the
   charter). A needs-dig classification carries an owner and a timebox so the gate cannot
   stall a release indefinitely; by-catch from past confirmatory rounds is real but is
   measured in the debrief (on-opportunity share), not used to justify the next one.
4. **Finalize on zero findings.** Only when a Sandbox round finds no blocking issues is the
   number finalized and the release published.

**Finalize criterion:** a clean whole-product Sandbox round (independent verifier, not the
committer) with no blocking findings — or with every remaining blocking finding explicitly
downgraded to a **known issue** per the policy below. This is not a route to silently lowering the
bar (`structural-defects-no-repeat` still applies to the defect itself once picked up) — it
requires the owner naming the specific finding and its tracking tempdoc, not an agent's unilateral
call, and it does not excuse the finding from eventually needing a real fix with a regression test.

### Known issues at release

A confirmed, reproducible finding may ship as a documented known issue instead of blocking finalize
when **all** of:

1. **Explicit owner decision**, dated and recorded in the release's convergence tempdoc (not an
   agent's or round's own call) — e.g. "owner decision YYYY-MM-DD: finding N ships as a known
   issue, tracked in tempdoc NNN."
2. **A dedicated tracking tempdoc exists** for the finding, with the problem statement, root-cause
   understanding so far, and a suggested fix shape — so the fix has a durable home separate from
   the release record, and isn't lost once the release ships.
3. **The GitHub Release's notes carry a Known Issues section** linking to that tempdoc, so the
   limitation is disclosed to users at the point of install, not buried in internal docs.
4. **The convergence tempdoc's finding is reclassified in place** (not deleted or silently
   dropped) — the routing column points at the new tracking tempdoc, and the release verdict
   records that this specific finding no longer blocks finalize, while any *other* still-open
   blocking findings continue to.

This exists so one non-core defect doesn't hold an entire release hostage, while keeping every
known defect visible, owned, and headed toward an actual fix rather than indefinite tolerance.

## The asset set the build produces

One build produces a complete, hash-consistent asset set in `dist/installer/`:

| Asset | Source | Notes |
|---|---|---|
| `JustSearch_<version>_x64-setup.exe` | NSIS build (Tauri) | The installer. |
| `justsearch-mcp.mcpb` | built from source by `scripts/ci/pack-mcpb.mjs` | The one-click MCP bundle; a deterministic STORED zip of `manifest.json` + `server/**` (not committed). |
| `SHA256SUMS` | generated by `build-release-assets.ps1` | `sha256sum -c`-compatible manifest over both assets. |

**Consistency invariant (fail-closed).** The MCPB's SHA-256 is a *published contract*: it lives
in `packaging/mcpb/server.json` (`fileSha256`, read by the Official MCP Registry publish), in the
release `SHA256SUMS`, and in the bundle bytes. A wrong `fileSha256` silently breaks MCP installs.
Because the bundle is **built deterministically from source**, `scripts/ci/check-mcpb-consistency.mjs`
re-packs from source and fails the build if the fresh hash `!= server.json.fileSha256` (run every PR)
— this catches both integrity *and* freshness (an edit to `manifest.json`/`server/**` without a
re-sync). On a real cut (`--release-version`) it also fails if `server.json`'s version/URL don't match
the tag. After editing the source, run `node scripts/ci/pack-mcpb.mjs --sync` (the `mcpb-repack-hint`
reminds you). See `packaging/mcpb/README.md`.

## Cutting a release (operator)

In-repo steps (an agent can prepare these; the branch must be pushed and green):

1. Bump `gradle.properties` `version` to the release version (e.g. `0.2.0`).
   `sync-version.ps1` propagates it to Tauri/Cargo/npm; `-RequireReleaseSemver` accepts
   `x.y.z[-alpha.N]` and rejects `SNAPSHOT`.
2. `sync-version.ps1` also stamps `server.json`'s `version` + release-asset URL from the
   gradle version. If the MCPB source changed since the last release, also run
   `node scripts/ci/pack-mcpb.mjs --sync` and commit `server.json` (its `fileSha256`).
   Verify: `node scripts/ci/check-mcpb-consistency.mjs --release-version <version>`.

Owner-only steps (require repo permissions):

3. Push, tag `v<version>` (a bare `vX.Y.Z` auto-resolves to **non-prerelease** → becomes
   `/releases/latest`; `-alpha/-beta/-rc` tags are prereleases).
4. Dispatch `build-installer.yml` against the tag ref. It builds, runs
   `build-release-assets.ps1`, and attaches the whole asset set to the GitHub Release.
5. Set the repo homepage, enable Discussions if desired, and take/replace hero screenshots
   (see `docs/m1-operator-checklist.md`).

> Do **not** advertise MCP against a release whose installer predates the `/mcp` endpoint. The
> shipped **v0.1.0** app has no MCP endpoint (its backend is 2026-04-28 jars); the MCPB bundle
> and the README's MCP sections are meaningful only from the next release onward.

## Release index

One row per release (this table is the only living per-release record here; detail lives in
each release's convergence tempdoc + its GitHub Release):

| Version | Date | Sandbox verdict | Notes | Links |
|---|---|---|---|---|
| v0.1.0 | 2026-06-25 | (pre-pipeline) | Prerelease/alpha. **No MCP endpoint.** Installer built locally, not via CI. | [release](https://github.com/eliasjustus/justsearch/releases/tag/v0.1.0) |
| v0.2.x | pending | rounds 1-6: dense-retrieval + capability-gate fixes confirmed working; `core.workflow-run` fixed 2026-07-16 (tempdoc 744); round 6 (fresh-install, pre-registered) is **DO-NOT-QUALIFY** — the golden-query parity regression reproduces with the CPU/GPU-precision hypothesis now ruled out, root cause still unidentified; a new HIGH-severity RAG chunk-retrieval bug was also found (tempdoc 749) | First cut with the MCP endpoint + the hash-consistent asset pipeline. | tempdoc 734, tempdoc 749 |

## See also

- MCPB bundle + registry: `packaging/mcpb/README.md`
- Owner funnel: `docs/m1-operator-checklist.md`
- Workflow: `.github/workflows/build-installer.yml`
