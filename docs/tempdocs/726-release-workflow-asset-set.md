# 726 — Release workflow: one dispatch → the full, hash-consistent release asset set

- **status:** open — DESIGN + DERISK 2026-07-14. Design settled; load-bearing assumption
  (MCPB pack determinism) tested. Confidence 8/10, implementation delegatable to sonnet.
  Not started. Owner-only release steps enumerated as handoff (`docs/m1-operator-checklist.md`).
- **created:** 2026-07-14
- **author-role:** orchestrator (Opus) — design/judgment; implementation delegatable to sonnet
- **relation:** post-go-public continuation of the release/distribution slice of tempdoc **374**
  (App Packaging & Distribution — its G2/G3 "mechanism wired, no release cut yet"). Supersedes
  the publication-surface intent of tempdoc **409** (justsearch-releases repo audit), which
  predates the cutover: the separate `justsearch-releases` repo is gone and releases now live on
  `eliasjustus/justsearch`. Distinct from **562** (installer *build* smoothness — clean-env
  friction, not publication). Adjacent (disjoint files) to **725** (MCP tool-adoption) and **655**
  (MCP conformance). 374 and 409 stay as dated history; this doc is the current truth for the
  release workflow.

## Why this exists now (and as a new tempdoc)

374 is a pre-cutover artifact — 3,000+ lines of `alpha.1–28` sandbox history, a `2.0.0-alpha.N`
scheme, and a separate `justsearch-releases` repo — all superseded by the go-public cutover
(private tempdoc 634: version reset `2.0.0-alpha.28` → `0.1.0`, public snapshot repo created).
The post-cutover release surface is a new chapter (releases consolidated onto this repo, `0.1.0`
scheme, MCPB + Official MCP Registry — none of which existed in 374's world), so it gets its own
doc near the frontier rather than an append that buries current design under stale history.

**Forcing function (validating evidence already exists, no experiment needed).** The shipped
`v0.1.0` GitHub Release (2026-06-25, prerelease/not-latest) is **2026-04-28 jars with no MCP
endpoint** (`/mcp` = catch-all empty 200), yet the m1 README and the new one-click MCPB bundle
advertise MCP. Publishing the m1 + MCPB branch stack to public `main` without a fresh release
makes the public surface contradict the shipped artifact — a candor defect. Per
`docs/m1-operator-checklist.md` item 4, **cutting a new release (v0.2.x) from current main is
required, not optional.** Verifiable from the asset's build date.

## Current state (2026-07-14)

- **Release workflow** `.github/workflows/build-installer.yml`: `workflow_dispatch`-only; when
  dispatched against a `v*` tag ref it builds the CPU-only NSIS installer and attaches **only
  `dist/installer/*.exe`** to a GitHub Release (`softprops/action-gh-release`, prerelease
  auto-detected from `-alpha/-beta/-rc`). **It has 0 CI runs** — `v0.1.0` was built locally via
  `scripts/ci/package-installer-win.ps1`; the workflow has never actually executed.
- **`package-installer-win.ps1`**: syncs version (`sync-version.ps1`), builds, computes an
  installer hash into an *evidence JSON*. Emits **no `SHA256SUMS`** asset and does **not** pack
  the MCPB.
- **MCPB bundle** `packaging/mcpb/`: `server/index.js` (zero-dep stdio→loopback-HTTP bridge),
  `manifest.json`, `server.json` (Official Registry metadata, **pre-set to `0.2.0`** with a
  `fileSha256` and a `…/v0.2.0/justsearch-mcp.mcpb` URL — prepared, not published). The packed
  `dist/justsearch-mcp.mcpb` is **gitignored** (a build output), 6.8 KB, current hash matches
  server.json. Asset assembly today is a manual operator runbook (`packaging/mcpb/README.md`).
- **Root `/SHA256SUMS`**: hand-authored, one `v0.1.0` line.
- Local `gradle.properties` = `0.1.0`; the m1/mcpb branch stack is **local-only, unpushed**.

## Design — release-asset assembly (generator + fail-closed consistency gate)

**The real problem is hash consistency, not "attach more files."** A release ships three assets
whose SHA-256s cross-reference each other — installer, `justsearch-mcp.mcpb`, `SHA256SUMS` — and
the MCPB hash additionally lives in `server.json.fileSha256`, which the Registry publish reads.
So the MCPB hash lives in **three places that must agree** (server.json / SHA256SUMS / the bytes),
and **MCP clients fail-closed on a wrong `fileSha256`** → silently broken installs. Today all of
it is hand-maintained.

The seam is **one generator + one fail-closed consistency gate**, with a deliberate split (the
repo's "verify, don't guess" applied to release integrity):

1. **Generate transient release assets.** A single `scripts/ci/build-release-assets.ps1`, reused
   by both entry points — the operator's local `package-installer-win.ps1` **and**
   `build-installer.yml` — so local and CI cuts emit byte-identical sets. Given the built
   installer + version from `gradle.properties`, it stages into `dist/installer/`: the installer,
   the committed `justsearch-mcp.mcpb` (copied, **never re-packed** — see derisk), and a generated
   `SHA256SUMS` over both in the standard `<lowercase-hex>␠␠<filename>` `sha256sum(1)` format with
   the existing comment header.
2. **Verify tracked sources, never machine-rewrite them.** The consistency gate:
   - **PR-time (version-independent, passes on the current tree):**
     `sha256(committed bundle) == server.json.fileSha256`, **and** a *freshness* check — the
     bundle's inner `manifest.json`/`server/index.js` equal the tracked source files
     (content-compare, robust to zip-metadata nondeterminism; catches "edited source, forgot to
     re-pack"). No re-pack.
   - **Release-path only:** `server.json.version` + asset URL == the version being cut (from
     `gradle.properties` after the operator bumps it). The current `gradle=0.1.0` vs
     `server.json=0.2.0` gap is expected pre-cut and must not be gated at PR time.
   `server.json` stays operator-authored; the gate only reads it.
3. **Commit the 6.8 KB bundle** (un-ignore `packaging/mcpb/dist/justsearch-mcp.mcpb`). Its hash is
   a *published contract*, so it should be a reviewed, version-controlled artifact — not a
   gitignored output re-packed per release. Committing lets the consistency gate run on **every
   PR** (drift caught at review, not at the cut) and keeps the release pipeline free of a network
   `npx` fetch (preserving the offline posture). `mcpb pack` stays the authoring step; the SSOT
   catalog dual-copy is the precedent for "generated but canonical, committed."
4. **Workflow change is small.** Attach `dist/installer/*` (glob) instead of `*.exe`. A bare
   `v0.2.0` tag already auto-resolves `prerelease=false` → `/releases/latest` (checklist item 4's
   "non-prerelease" needs a non-`-alpha/-beta/-rc` tag, no code change).
5. **A re-pack staleness hint** (PostToolUse on `packaging/mcpb/server/**` + `manifest.json`)
   mirrors `lockfile-hint.mjs`: "re-pack the MCPB + update `server.json.fileSha256`." The
   content-freshness gate is the hard backstop; the hint is the moment-of-relevance nudge.
6. **sync-version — confirmed, no change.** `sync-version.ps1 -RequireReleaseSemver` accepts
   `0.2.0` (regex `^\d+\.\d+\.\d+(-[A-Za-z0-9]+(\.[A-Za-z0-9]+)*)?$`; step 2 rejects only
   `SNAPSHOT`). Verified `sync-version.ps1:60-75`.
7. **README TODOs — resolve toward visible, non-overclaiming truth.** Both `<!-- TODO(operator) -->`
   comments already state the honest caveat; surface it as user-visible prose and drop the marker.
   RAM row: "16 GB recommended / 8 GB minimum" (a recommendation, not a measured floor — no 8 GB
   benchmark exists to cite, so no number is asserted). OS row: "Windows 11 verified; Windows 10 is
   the expected WebView2 baseline, not explicitly tested." No quantitative public claim without a
   citable run (public-claims CI lane).

### What this orphans (deletion belongs to this tempdoc, not a later sweep)

- **Root `/SHA256SUMS`** (hand-authored, single v0.1.0 line) — displaced by the generated
  per-release manifest. Retains one-time value only for the already-shipped v0.1.0 (checklist item
  3 attaches it); once attached, delete it.
- **MCPB README "Release flow (operator)" steps 3–4** (hand-add SHA to SHA256SUMS, hand-edit
  server.json hash) — displaced by the generator + gate; README points at the pipeline, keeping
  only genuinely-manual steps (attach-to-release, registry publish).
- **`.gitignore` / `.mcpbignore` exclusion of `dist/justsearch-mcp.mcpb`** — removed for that one
  path when the bundle is committed.

## Derisk findings (2026-07-14) — assumptions tested

- **`mcpb pack` is nondeterministic (experiment).** Two packs of identical source → different
  hashes (`0e2ba25…`, `202fcc…`), both ≠ committed `415a57ab…`, all 6836 bytes. The difference is
  **zip metadata, not content**: the committed bundle's payload is byte-identical to a fresh pack
  (`diff -rq` clean). ⇒ **never re-pack in CI** (would drift `fileSha256` → broken installs); the
  committed bundle is canonical; freshness is checked by *content-compare*, not re-pack.
- **Version coupling is a non-issue.** `sync-version` only reads `gradle.properties` (the operator
  bumps it), so the version gate is release-path-only; hash+freshness are version-independent and
  **pass on the current tree today**. Nothing asserts `gradle==server.json`, so the speculative
  `0.2.0` is harmless.
- **No CI gate blocks the change.** `check-workflow-triggers` validates only `on:` triggers vs
  `workflow-signal-policy.v1.json` — triggers are untouched (only the attach glob broadens). README
  OS/RAM-row edits don't touch the benchmark table (`check-readme-benchmark-numbers`) or required
  entrypoint patterns (`check-root-readme`). The size/count ratchet gates were retired, so a
  committed 6.8 KB binary is fine.
- **`lockfile-hint.mjs`** is the clean template for the re-pack staleness hint.
- **Implementation wrinkle to decide at plan time:** the freshness content-compare needs to read
  the bundle's zip entries; Node stdlib has no zip reader. Options: shell `unzip -p` (availability
  on `windows-latest` in a plain `node` step unconfirmed), a ~40-line stored/deflate reader, or
  ship hash-gate + repack-hint first and add the freshness gate as a fast follow.

**Confidence: 8/10.** The one assumption that could have forced a redesign (pack determinism) was
tested and resolved in the design's favor; the current tree already satisfies the PR-time gate; no
blocking gates. Residual −2: freshness-gate zip-read mechanism unconfirmed on CI (has fallbacks);
`build-installer.yml` has never run in CI so its first real dispatch could surface unrelated
build-path issues (owner-gated, out of the agent slice); `sha256sum -c` not exercised end-to-end
(format is standard).

## Scope

**Agent-actionable (this tempdoc's implementation):** `build-release-assets.ps1` + wiring into
both entry points; the consistency gate (Node, stdlib; PR-time hash+freshness, release-path
version); broaden the workflow attach glob; commit the bundle (un-ignore); the re-pack hint;
resolve the two README TODOs; delete/tombstone the three orphans; update `packaging/mcpb/README.md`.

**Owner-only (handoff, not agent work — see `docs/m1-operator-checklist.md`):** push the branch
stack; bump `gradle.properties` → `0.2.0`; cut/tag/publish v0.2.x; attach assets; set repo
homepage; enable Discussions; take hero screenshots. Also: **`build-installer.yml` has never run
in CI** — cheapest proof of the build path is one `workflow_dispatch` on a non-tag ref (skips
release-attach via the `refs/tags/v` guard), but that needs owner `gh` auth + runner minutes.

**Deferred correctly (from 374):** G1 auto-updater, G4 code-signing (incl. `mcpb sign`) — no user
base yet. **Adjacent-open, not scoped in:** 409 D1–D3 license-attribution defects (NER = AFL-3.0
not MIT; ONNX/SPLADE attribution) ↔ 374 G12 `THIRD_PARTY_NOTICES` — a distribution-compliance item
for a separate slice.

## Reach — published-hash-contract consistency

**Principle.** *A hash that is a published contract must have one canonical source and a
fail-closed consistency gate; never hand-maintained parallel copies.* The MCPB `fileSha256`
(server.json ↔ SHA256SUMS ↔ bytes) is a "same fact in three places" drift risk — the exact class
the repo already fights with the SSOT catalog dual-copy sync gate, the execution-surface register,
and the projection-vs-fork rule. This design **conforms to that existing seam** rather than
inventing a parallel one.

**Candidate scope beyond this problem (name, don't build):** the installer's own SHA (README quotes
it + SHA256SUMS + bytes); `model-registry.v2.json` download URLs + SHA-256 (381) vs the actual
release model assets — tempdoc **409 D7/D10** already recorded checksum-filename and cross-release
naming mismatches, the un-gated form of this same principle, live today; the llama-server prebuilt
SHA pinned in `build.gradle.kts` and echoed in `build-installer.yml`'s cache key.

**Do not generalize now** — the present problem only needs the installer + MCPB + SHA256SUMS set
consistent; a repo-wide "all published hashes" gate is unwarranted until a second surface demands
it. **Earns-its-keep:** zero "wrong `fileSha256` broke install" / "checksum command failed"
incidents after it lands, **and** the gate fires at least once on a real drift. **Retirement:** if
the MCPB/registry path is abandoned (`server.json` deleted) or the published-hash surfaces collapse
to one, the gate is dead apparatus — retire it.
