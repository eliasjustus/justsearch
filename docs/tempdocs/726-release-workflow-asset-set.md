# 726 — Release workflow: one dispatch → the full, hash-consistent release asset set

- **status:** IMPLEMENTED 2026-07-14 (branch `worktree-release-asset-set`, pre-PR). v1 (asset
  generator + fail-closed gate + workflow wiring + canonical runbook + orphan teardown) AND the
  **keystone** (deterministic `pack-mcpb.mjs` → build-the-bundle-from-source; the gate now
  re-packs-and-compares; the committed bundle is removed; `server.json` version/URL stamped by
  `sync-version.ps1`) both landed and validated (gates green). **The freshness gap is closed
  structurally** — the gate rebuilds from source, so a stale source edit FAILS; no longer deferred.
  Owner-only release steps are a handoff (`docs/m1-operator-checklist.md`); this tempdoc closes on
  merge. Still deferred to their own design passes: the two-mode generalized register + the
  monotonic Sandbox loop (see §Long-term).
- **created:** 2026-07-14
- **author-role:** orchestrator (Opus) — design/judgment; implementation self-authored (small,
  tightly wiring-coupled, gate-validated — under the delegation floor)
- **scope & lifecycle:** 726 is a **bounded** design/decision doc for the asset-assembly slice
  and **closes on ship**. The durable "how a release is cut" lives in the canonical runbook
  `docs/how-to/cut-a-release.md` (single living surface + 1-row-per-release index). Each future
  release's round-by-round Sandbox convergence gets its **own** bounded tempdoc — no single doc
  spans releases (that was 374's decay mode).
- **relation:** post-go-public continuation of the release/distribution slice of tempdoc **374**
  (App Packaging & Distribution — its G2/G3 "mechanism wired, no release cut yet"). Supersedes
  the publication-surface intent of tempdoc **409** (justsearch-releases repo audit), which
  predates the cutover: the separate `justsearch-releases` repo is gone and releases now live on
  `eliasjustus/justsearch`. Distinct from **562** (installer *build* smoothness — clean-env
  friction, not publication). Adjacent (disjoint files) to **725** (MCP tool-adoption) and **655**
  (MCP conformance). 374 and 409 stay as dated history; this doc is the current truth for the
  release workflow.

## Why this exists now (and as a new tempdoc)

374 is a pre-cutover artifact — a long rolling packaging/sandbox log, an older pre-release version
scheme, and a separate `justsearch-releases` repo — all superseded by the go-public cutover, which
reset the public version line to `0.1.0` and consolidated releases onto this repository.
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

## Long-term viability & correct end-state (2026-07-14)

Critical self-analysis of what shipped, and the architecture the shipped v1 is a way-station toward.
The v1 is *architecturally* sound but *coverage-incomplete*: its gates are **drift *detectors***, and
drift is possible at all only because the system still has humans **transcribing derived facts**
(the bundle hash into `server.json`; versions into `server.json` beside `gradle.properties`; the
release-index rows by hand). Each hand-authored copy is a **fork** of a fact owned elsewhere, and
every fork is a drift site. A gate that checks two hand-authored copies agree is itself evidence you
shouldn't have two copies.

### Ranked liabilities in the shipped v1
1. **Freshness gap (top).** The hash gate proves `bundle == server.json`, not `bundle == its source`.
   Edit `server/index.js`, forget to re-pack → stale bundle whose hash still matches an untouched
   `server.json` → **gate passes, ships old bridge code.** Backstop is the `mcpb-repack-hint`, which
   fires only for Claude-Code main-loop sessions (0% for humans/external contributors/subagents) and
   widens as contributors grow. Compounded by the fragile 3-rule `.gitignore` negation stack (a
   future edit can silently re-ignore the bundle → `git add` no-ops on re-pack → stale, undetected).
2. **`build-installer.yml` never run in CI** — the system-tier integration (runner builds installer →
   `build-release-assets.ps1` → `& node` gate → attach) is unproven; validated only at unit/stub tier.
3. **Re-introduced drift** — the release-index table and the version bump (`gradle.properties` +
   `server.json` version + URL) are hand-maintained/unenforced day-to-day; the gate catches the
   dangerous mismatch only at release-path, not the ergonomic drift.
4. **Runbook `draft` ⇒ unindexed** — discoverability hinges on links from dated/transient docs and a
   status flag a human must remember to flip.
5. **External-schema risk** — the gate hard-codes `server.json` shape against a preview-stage registry
   spec; a schema change breaks it or gives false green.

### Correct end-state — "generate, don't verify"
The target design makes drift **unrepresentable** by having one build derive every downstream
appearance from a single source — the stronger form of "verify, don't guess". Each fix is an instance:
- **Deterministic packing is the keystone.** Normalize the `.mcpb` zip (fixed mtimes, sorted entries)
  so the bundle is a pure function of source; then **build it in CI and stamp its hash into
  `server.json` + `SHA256SUMS` from the freshly-built bytes.** Freshness ceases to exist; the committed
  binary, the repack-hint, and the freshness gate all disappear. (Unlocks everything below.)
- **One version source.** `server.json` version + asset URL become *generated* fields stamped from
  `gradle.properties` (as Tauri/Cargo/npm already are); the equality gate becomes moot.
- **Derived index.** Render the release index from the GitHub Releases API — it can't drift because it
  isn't authored.
- **Discoverability without memory.** Index all canonical docs regardless of maturity; show maturity as
  a badge, not by omission.
- **Gate the contract, not its shape.** Validate `server.json` against the pinned published MCP-registry
  JSON Schema; a spec change is a loud, deliberate upgrade, never a silent false-green.
- **Generalize the mechanism.** A single hash-consistency gate over a register of
  `{artifact → generated-from}` tuples covering the installer, MCPB, **every model in
  `model-registry.v2.json`** (already drifting per 409 D7/D10), and the pinned llama SHA. A second
  surface already demands the general structure.

**Enabling precondition:** v1 *worked around* two constraints rather than removing them —
nondeterministic upstream packing and the offline-at-release posture (why the bundle is committed
instead of built). Aside from feasibility, the correct move is to attack those directly (reproducible
packing upstream / a vendored deterministic packer), which dissolves the offline objection and makes
CI-build-and-stamp safe.

**Process side (the loop, not the assets):** make the Sandbox-convergence loop **monotonic by
construction** — a regression can't be marked resolved without a linked regression test, so rounds
provably shrink and never re-discover a class. This is `audit-without-test` promoted from prose to an
enforced gate on the release-convergence process.

**Consequence:** in the end-state, most of v1's gates are **scaffolding you delete, not extend** — they
verify hand-authored copies that would no longer exist. v1 ships the correct *seam* and the honest
*gaps*; the end-state removes the need for the seam by removing the copies. (Falsifier for this whole
frame: if determinism proves genuinely unattainable upstream *and* un-vendorable, the committed-bundle
+ content-freshness-gate fallback is the correct terminal design, not a way-station.)

### Derisk of the long-term fixes (2026-07-14)

Assumptions tested before anyone implements the end-state:

- **Keystone — deterministic packing — GREEN (the scariest assumption collapsed).** The `.mcpb` is a
  plain 2-file DEFLATE zip; the sole nondeterminism is the embedded mtime. A normalized zip writer
  (fixed mtime, sorted entries) produced **byte-identical output across two runs**, and `mcpb info`
  reads it as a valid bundle. So determinism is a ~15-line packer *replacement*, fully in our control,
  **no upstream dependency** — the falsifier above does not fire. This unlocks CI-build-and-stamp,
  deletes the committed binary + the freshness gap, and needs only one owner-side confirmation
  (Claude Desktop installs our normalized bundle — low risk, it is a valid mcpb per the official tool).
- **The generalization is two-mode, not one — REFRAMED.** `model-registry.v2.json` holds 28
  `downloadUrl`+`sha256` pairs for **redistributed remote ONNX assets** (still pointing at the old
  `justsearch-releases` repo, a live 409-D-series staleness). Their hashes can only be **verified**
  (download+hash / trust), never *generated* from a local build. So the "one mechanism" is a register
  with two modes — **`generate`** (built: installer, MCPB) and **`verify`** (redistributed: models).
  The clean "generate everything" story is partly wrong; the register must respect the boundary.
- **Monotonic loop is prerequisite-blocked.** No structured finding/verdict substrate for Sandbox
  rounds exists (findings live as prose in tempdocs). A "no-resolve-without-linked-test" gate needs a
  machine-readable finding↔test format *first* — so this is a design-a-substrate research item, not a
  bounded implementation.
- **server.json stamping + derived index — low uncertainty.** `sync-version.ps1` doesn't touch
  server.json (confirming version is a *fork*); stamping it there is a straightforward projection.
  The derived release-index has a ready template (the `generate + --check` pattern of
  `check-readme-benchmark-numbers` against `release.v1.json`), with CI `GITHUB_TOKEN` for the API.

**Confidence & sequencing.** Staged, not uniform: **~8/10 for the near-term core** (deterministic
packer → stamp `fileSha256`/`SHA256SUMS`/`server.json` version → delete the committed bundle → freshness
gap dissolved) — this alone closes the #1 liability and is well-scoped/verifiable (**Sonnet, medium**).
**~5/10 for the full end-state**: the two-mode register needs a design pass + reconciling the stale
model-distribution surface (**Opus design, Sonnet impl**); the monotonic loop needs a findings-substrate
design first (**Opus, design-only — do not implement yet**). Recommendation: implement the keystone core
as the next slice; treat generalization and the loop as separate design passes, not one push.

### Keystone landed (2026-07-14)

The near-term core is **implemented on this branch**. `scripts/ci/pack-mcpb.mjs` builds the bundle
deterministically from source (STORED zip, fixed mtime, CRC-32 in-house — no zlib-version variance);
proven byte-stable across runs and accepted by the official `mcpb info`/`unpack`. The gate now imports
the packer, **re-packs from source and compares to `server.json.fileSha256`**, so integrity *and*
freshness are one check (a source edit without `pack-mcpb.mjs --sync` FAILS). The committed bundle is
deleted and re-gitignored; `server.json` version/URL are stamped by `sync-version.ps1` (last hand-authored
fork closed). The v1 committed-bundle mechanism + the deferred content-freshness gate are **superseded**
(torn down in the same change).

A refute-first review pass then hardened it (all 10 verification claims held): the tagged release build
now verifies `server.json` version/URL (`-VerifyReleaseVersion` on `refs/tags/v*`, decoupled from
signing); all `server.json` mutation moved to JSON-aware `pack-mcpb.mjs --sync`/`--set-version` (no more
regex-on-JSON — a malformed hash or a future nested `version` can no longer silently mis-edit); and the
packer fail-closes on a non-ASCII entry name or a top-level manifest asset outside `server/`. Byte-neutral
(hash stays `b71d792c…`). **Still open** (own design passes): the two-mode `generate|verify` register
(models are verify-only + `model-registry.v2.json` still points at the retired `justsearch-releases` repo),
the derived GH-API release index, and the monotonic Sandbox loop (needs a findings substrate).

## Verification (evidence pointers, 2026-07-14)

Each claim below was exercised on branch `worktree-release-asset-set`; commands are runnable from
the repo root. An independent refute-first review re-ran all of them (all held).

- **Deterministic bundle** — `node scripts/ci/pack-mcpb.mjs <out>` twice → identical
  `sha256 b71d792c1ef38dd658947742d842ec221499b8b5338be83ce031bb3dcf079d8c` (19196 bytes).
- **Valid zip / correct CRC** (external) — `unzip -t <bundle>` → "No errors detected".
- **Valid MCPB, payload round-trips** (external) — `npx -y @anthropic-ai/mcpb info` + `unpack`;
  unpacked `manifest.json` + `server/index.js` `diff`-clean vs source.
- **Manifest passes official schema** (external) — `npx -y @anthropic-ai/mcpb validate
  packaging/mcpb/manifest.json` → "Manifest schema validation passes!".
- **Cross-platform determinism** — `.gitattributes` pins the packed source to LF
  (`git check-attr eol packaging/mcpb/server/index.js` → `eol: lf`), so bytes are platform-independent.
- **Gate = integrity + freshness** — `node scripts/ci/check-mcpb-consistency.mjs` → OK on the real
  tree; editing `server/index.js` without `--sync` → exit 1 "MCPB hash drift".
- **Release-version guard fires on a cut** — `build-release-assets.ps1 -VerifyReleaseVersion` →
  FAIL on the version/URL mismatch; passes without the switch (dry-run).
- **`--sync`/`--set-version` are JSON-aware** — `pack-mcpb.test.mjs` (14 assertions) covers
  malformed-hash repair, nested-`version` non-clobber, and the two packer guards;
  `check-mcpb-consistency.test.mjs` (13 assertions). Both green.
- **Build assembles the set** — `build-release-assets.ps1` (stub installer) → installer + mcpb +
  `SHA256SUMS`; `sha256sum -c SHA256SUMS` → both OK.
- **Repo gates green** — `hook-integrity`, `check-workflow-triggers`, `check-root-readme`,
  `verify-canonical-doc-links`, `llmstxt-generate --check`; PowerShell parse checks on the three `.ps1`.

### Unverified assumptions / deferred checks (carry forward)

- **Claude Desktop actually installs the from-scratch STORED-zip `.mcpb`.** Not testable here (no
  desktop app); the official `mcpb info`/`unpack`/`validate` all accept it, so this is low-risk —
  but confirm with one real install before relying on the MCP one-click path.
- **`build-installer.yml` has never run in CI.** The unit/stub tiers pass, but the full CI path
  (runner builds the ~1 GB installer → `build-release-assets.ps1` → attach) is unproven end-to-end;
  the first real dispatch is owner-gated.
- **Owner-only release steps** remain a handoff: `docs/m1-operator-checklist.md` +
  `docs/how-to/cut-a-release.md` (push, bump `gradle.properties`, tag, dispatch, GitHub-UI).
- **Deferred design passes** (not started): two-mode `generate|verify` register (and reconciling
  `model-registry.v2.json`, whose model assets still point at the retired `justsearch-releases`
  repo — a distribution-hygiene item), the derived GH-API release index, and the monotonic Sandbox
  loop (blocked on a machine-readable findings substrate).
