---
title: "Release unblock: code-signing vendor decision and wiring, GA tag, winget submission automation, CHANGELOG as the release-notes source, and a model-swap runbook"
type: tempdocs
status: CHARTERED (2026-09-02) — vendor decision made by the orchestrating session under founder delegation; two owner actions in §O gate items 1-2; items 3-5 can start now
created: 2026-09-02
updated: 2026-09-02
lane: 887 L16
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 760-installer-distribution-readiness   # signing drop-in; winget skeleton; "remaining owner-gated: cert/vendor decision, GA cut, winget publisher identity"
  - 772-installer-payload-composition      # signing thesis routed to 760 §K
  - 726-release-workflow-asset-set · 750-release-loop-scheduling · 617 §9 release qualification
  - 802-release-artifact-provenance
  - 840-model-download-restructure · 826-fingerprint-persistence (BLOCKED on 819) · 317/253/358 (hand-run swaps)
  - 632 Stage G (SPDX headers, deferred)
  - docs/how-to/cut-a-release.md (the runbook these items extend)
---

# 905 — Release unblock

## Briefing for the agent picking this up

Fresh start. Read this file, 887 Appendix A9, `docs/how-to/cut-a-release.md` in full, and
760's §K. Load `/installer` before touching NSIS, Tauri config, or `scripts/ci/package-*`.
Items 3-5 need no credential and can start immediately in a worktree; items 1-2 wait for §O.
Never commit a credential, a `.pfx`, or a vendor token; the signing modes read secrets by name
(`cut-a-release.md:310-323`). Outward actions (creating the vendor account, submitting to
winget-pkgs, publishing a Release) are the owner's; you prepare, verify, and document.

## Decisions (2026-09-02)

- **Vendor: Azure Trusted Signing**, consumed through the existing `command` mode
  (`JUSTSEARCH_CODESIGN_MODE=command`, `cut-a-release.md:315`: "any vendor CLI … pluggable with
  zero further repo changes"). Grounds: subscription-priced with no per-signature metering (the
  sign-once mirror workflow was built to economise eSigner signings — under Trusted Signing it
  becomes optional, keep it dormant), identity validation suits an individual publisher, and
  SmartScreen reputation accrues to the certificate. eSigner remains the validated fallback
  (sandbox-validated 2026-07-22) if the Azure identity validation fails for an individual.
- **GA tag: `v0.2.0`** once 617 §9's release qualification passes on a signed candidate; no GA
  from an unsigned build (SAC blocks it outright, `verify-your-download.md:54-72`).
- **winget first, Scoop second, no Chocolatey** (its moderation queue is a poor fit for a
  fast-moving indie project). MSIX/Store stays rejected (ADR-0024; re-review after GA at most).
- **`CHANGELOG.md` is the human half of the release body.** The workflow prepends the
  verify-your-download blurb, inserts the tag's `CHANGELOG` section, then appends
  `--generate-notes`; a gate fails the release build if the tag has no section.

## §O. Owner actions

1. Create the Azure Trusted Signing account + certificate profile (individual identity
   validation); store the four values as repository secrets named in `cut-a-release.md`'s
   `command` mode (the agent adds the exact names in item 1); run one signed local build to
   confirm.
2. Decide the winget `PackageIdentifier` publisher segment (currently `eliasjustus`), and be
   ready to open the `microsoft/winget-pkgs` PR the agent drafts (item 3).

## Scope

1. **Signing wiring for Trusted Signing** (after §O.1): the `command`-mode invocation
   (`signtool`/`TrustedSigning` client) in `scripts/ci/sign-windows.ps1`'s command branch,
   `verify-windows-signature.ps1` expectations, `build-installer.yml` secrets plumbing,
   `JUSTSEARCH_REQUIRE_SIGNING=1` on the release path; dry-run on a dispatch build; document in
   `cut-a-release.md`. Sign-once mirrors left dormant with a one-line note why.
2. **Release qualification** (after 1): run 617 §9 items 2-5 on the signed candidate
   (`verify-installer-nsis-win.ps1`, sandbox `upgrade-from-release`), record evidence in 617
   and here; then the owner cuts `v0.2.0`.
3. **winget automation** (now): `scripts/release/gen-winget-manifests.mjs` fills
   `packaging/winget/*.yaml` from `SHA256SUMS` + the tag; `check-winget-manifests.mjs` validates
   against the 1.6.0 schema (`winget validate` if available); a dispatch workflow step produces
   the three files as an artifact and a ready-to-paste `winget-pkgs` PR body. Scoop: a
   `packaging/scoop/justsearch.json` manifest generated the same way (autoupdate stanza).
4. **CHANGELOG gate + release body** (now): `check-changelog-section.mjs` (tag → `## [x.y.z]`
   section present, `[Unreleased]` non-empty on `main`); `build-installer.yml:469-479` reads the
   section into the body between the blurb and the generated notes; `cut-a-release.md:298-308`'s
   manual list is deleted in favour of the file (`retire-with-a-sweep`).
5. **Model-swap runbook** (now): `scripts/models/swap-model.py` (or `.mjs`) sequencing what
   exists — `build-*.py` → `check-integrity.py` → `verify-model.py` → registry sha256/size update
   in `model-registry.v2.json` → `gen-notices` → jseval re-baseline command → blue/green
   re-embed note (doc 11) — with a dry-run mode and a manifest of what it changed. It must
   refuse to proceed when a model dir lacks `build.json` (the incident at
   `inference-runtime-register.md:138`). The fingerprint-persistence dependency (826, blocked on
   819) is recorded as a precondition for the automatic re-embed trigger, not solved here.

## Acceptance criteria

- Item 1: `verify-windows-signature.ps1` passes on every PE in a dispatch build; SmartScreen
  test on a clean VM recorded in §Status (reputation may still warn — say so, do not overclaim).
- Item 3: generated manifests validate; `check-winget-manifests.mjs` in the release workflow.
- Item 4: gate green on `main`; a dispatch build's draft Release body shows the section.
- Item 5: dry-run against the current registry produces a no-op manifest; a swap of the NER
  model on a branch (not merged) exercises the full sequence and is reverted.
- `node scripts/ci/check-workflow-triggers.mjs`, `check-notices-regen.mjs`,
  `check-update-preserves-models.mjs` green; `/installer` checklist followed.

## Constraints

- No secrets in the repo; no Release published by an agent; no force-push.
- Non-goals: MSIX, Chocolatey, SPDX headers (632 G — separate small PR if wanted), integration
  plugins (887 L18).

## §Status

Chartered. Items 3-5 startable now; 1-2 blocked on §O.
