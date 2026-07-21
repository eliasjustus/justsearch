---
title: "Installer distribution readiness: silent install, published checksums, winget manifest, SmartScreen documentation — everything cert-independent, so code signing becomes a drop-in when the cert decision lands"
type: tempdocs
status: "open — Phase 1 gap audit COMPLETE (2026-07-21). Checksums already wired; signing is NOT drop-in (CI never engages it — the one place reality is worse than chartered); winget manifest absent. Phase 2 list ready, partly owner-gated. See §Findings."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed distribution-readiness work (2026-07-21)
category: distribution / installer
related:
  - 759-mcpb-standalone-feasibility   # sibling distribution lane
  - 761-linux-build-cost-estimate     # sibling distribution lane
---

> Charter. Motivation: distribution surfaces and first-run trust both hinge on installer
> mechanics that are independent of the (pending) code-signing decision. winget requires a
> stable installer URL + non-interactive install and — per primary Microsoft guidance — a
> winget-listed app installs without Smart App Control interference, which partially mitigates
> the unsigned-installer problem until a cert exists. Publishing checksums + a build-from-source
> note is the standard answer to "unsigned + filesystem access, why trust you". Since 2024, even
> EV certs earn SmartScreen reputation gradually, so all of this remains valuable post-signing.

# 760 — installer distribution readiness

## Phase 1 — gap audit (delegable, read-only)

Audit current state vs. requirements, with `file:line`/workflow evidence:

1. **Silent install**: does the NSIS/Tauri installer support `/S` (and per-user vs per-machine
   flags)? Does it uninstall cleanly non-interactively? (winget validation requires both, admin
   and non-admin.) Evidence from `modules/shell` config / NSIS scripts / tauri.conf.json.
2. **Release artifacts**: what does the release workflow (`build-installer.yml` and release
   process per `docs/how-to/cut-a-release.md`) actually publish today — stable URLs? SHA256
   checksums file? versioned naming winget can pin?
3. **winget manifest dry-run**: draft the manifest fields (`wingetcreate`-shape) against the
   real v0.1.0+ release assets; list every blocker (e.g. "publisher website" field — website
   work is tracked outside this tempdoc; note it, don't solve it).
4. **Signing pipeline state**: confirm the wired-but-dormant path
   (`tauri.signing.conf.json` → `scripts/ci/sign-windows.ps1`) still matches the current build,
   so cert purchase is genuinely drop-in. Identify anything that drifted.
5. **Trust documentation gap**: is there a user-facing "verify your download" doc (SHA256,
   why SmartScreen warns, build-from-source path)? If not, spec its contents.

**Acceptance:** a gap table in this tempdoc (requirement → current state → gap → effort class),
each row with primary evidence. No code changes in Phase 1.

## Phase 2 — fixes (chartered from the gap table)

Expected shape (to be confirmed by Phase 1): silent-install flags if missing; CI step emitting
`SHA256SUMS` on releases; winget manifest committed + submission checklist; `docs/how-to/`
verify-your-download page. Implementation in a worktree; standard verification tiers apply.

## Findings — Phase 1 gap audit (2026-07-21, opus investigator; evidence per row)

Pinned facts: Tauri 2.11.3 (`Cargo.lock:3914`), app 0.2.0 (`tauri.conf.json:5`), NSIS
per-user/no-admin (`tauri.conf.json:29-33`, `installMode: currentUser`).

| Requirement | Current state | Gap | Effort |
|---|---|---|---|
| Silent install `/S` | Present by Tauri-NSIS inheritance; custom hooks (`nsis/installer-hooks.nsh`) are non-interactive, nothing breaks silent mode | **Never empirically verified** — CI runs `-SkipVerify` (`build-installer.yml:173`); winget's validation VM would be the first real test | S (verify) |
| Per-user scope | `currentUser` → winget `Scope: user` maps cleanly | none | — |
| Silent uninstall | Tauri NSIS uninstaller supports `/S`; PREUNINSTALL hook non-interactive | Not verified (same `-SkipVerify` gap) | S (verify) |
| Stable InstallerUrl | Pattern exists: `releases/download/v<ver>/JustSearch_<ver>_x64-setup.exe` (`cut-a-release.md:119`) | **No GA release to pin** — v0.1.0 lacks `/mcp` + `SHA256SUMS`; v0.2.x pending (`cut-a-release.md:166`) | M (owner: cut release) |
| SHA256 on release | **Already wired**: `build-release-assets.ps1:83-104` emits `SHA256SUMS` over installer+mcpb; attached on `v*` tags (`build-installer.yml:221-228`) | none for future cuts — never yet run for a GA | — |
| winget manifest | **Absent** (repo-wide grep: only this tempdoc mentions it) | Author 3-file manifest + runbook; `InstallerType: nullsoft` auto-provides silent switches | M |
| Signing drop-in | Chain intact on paper (`tauri.signing.conf.json:4-14` → `sign-windows.ps1`, path resolves, fail-closed env contract) | **NOT drop-in — the charter's assumption fails.** CI calls `package-installer-win.ps1` **without `-Release`** (`build-installer.yml:173`) so it always takes the `--no-sign` branch (`package-installer-win.ps1:247`); the signing overlay only fires under `-Release` (`:233-244`), which is coupled to heavy `installer_verify` CI deliberately skips (`:274-290`). No PFX secret plumbing exists in the workflow. | M |
| Verify-download doc | Partial content scattered in README (`:37-47, :196`) + `SHA256SUMS` header comment; no `docs/how-to/` page, no user-facing SAC note | Consolidate + extend into `docs/how-to/verify-your-download.md` (scope: consolidation, not creation) | S |

**winget manifest dry-run:** `PackageIdentifier` (e.g. `eliasjustus.JustSearch`) + `Publisher`
legal name = **owner decisions**; `PublisherUrl`/homepage empty = external website dependency;
`License: Apache-2.0` (`Cargo.toml:6`); `InstallerSha256` reformat from `SHA256SUMS`
(uppercase, per-installer). Microsoft-verified: winget-listed apps install **without Smart App
Control interference** (charter framing confirmed). **Flag:** no verbatim primary-source rule
that winget-pkgs accepts *unsigned* exe installers was found — empirically it does (many
unsigned NSIS apps listed) — re-confirm against current CONTRIBUTING before submitting.

**Phase 2 work list (bounded; 3 delegable, 2 owner-gated):**
1. Empirically verify silent install/uninstall in a clean sandbox, admin + non-admin, exit 0,
   clean `%LOCALAPPDATA%` removal; capture evidence for the winget PR. (S, delegable)
2. Author `packaging/winget/` 3-file manifest + submission runbook; `winget validate` green.
   Gated on the GA release cut for final URL/sha values. (M, delegable)
3. Decouple signing from heavy verify: a `-Sign`-style flag so CI can engage the signing
   overlay + `JUSTSEARCH_REQUIRE_SIGNING` without `installer_verify`; add
   `JUSTSEARCH_CODESIGN_*` secret plumbing to `build-installer.yml`; dry-run with self-signed
   PFX; unsigned path unchanged when secrets absent. This is what actually makes a cert
   drop-in. (M, delegable)
4. `docs/how-to/verify-your-download.md`: certutil sha256 check, why SmartScreen warns +
   More-info→Run-anyway, build-from-source trust path, end-user SAC note (mirror of
   `package-installer-win.ps1:210-220` dev-side self-diagnosis); link from README. (S, delegable)
5. **(Owner)** Cut GA v0.2.x non-prerelease — precondition for winget pinning and for 759's
   registry publish; the existing pipeline already stages installer + `SHA256SUMS` + `.mcpb`.

Out-of-scope observation (logged to inbox): installer size stated inconsistently — 853 MB
(`README.md:57`) vs ~748 MB (`.claude/skills/installer/SKILL.md:16`) vs 741 MB
(`build-installer.yml:202`).
