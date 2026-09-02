---
title: "Release unblock: code-signing vendor decision and wiring, GA tag, winget submission automation, CHANGELOG as the release-notes source, and a model-swap runbook"
type: tempdocs
status: CHARTERED, UNBLOCKED (2026-09-02) — first draft wrongly proposed Azure Trusted Signing and a pending GA; corrected same day from primary sources: eSigner (SSL.com) is wired (secrets set 2026-08-12), mirrors signed 2026-08-12, v0.2.0 published 2026-08-13; founder budget decision recorded (pay tier, no overage); every item startable now, owner only opens the winget-pkgs PR
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

- **Vendor: SSL.com eSigner — already chosen, validated, and in use.** The first draft of this
  charter proposed Azure Trusted Signing; that was wrong and is withdrawn. The record:
  `cut-a-release.md:326-341` documents the eSigner `command`-mode template (CodeSignTool,
  auto-installed by `build-installer.yml`, fail-closed exit codes), and tempdoc 823 §6 records
  signed round-17 candidates (publisher "Elias Justus", `uninstall.exe` signed) produced inside
  the eSigner trial window ending **~2026-09-09**, with steady-state cost after the signed
  mirrors at ~8 signings per build. The sign-once mirror workflow exists precisely to economise
  metered eSigner signings — it stays live. What remains is not a vendor decision but two owner
  facts: whether the production eSigner credential secrets are set for the release dispatch
  (the doc says signing "engages automatically on the next dispatch" once they are), and the
  post-trial plan (paid eSigner tier, or the ~93 mirror signatures done inside the trial window
  per `cut-a-release.md:341`).
- **GA is done.** Verified 2026-09-02 (`gh release list`): `v0.2.0` is the latest published
  release (2026-08-13), built by `build-installer.yml` on the tag the day after the signing
  secrets were set (2026-08-12) and after the signed-mirror run succeeded (2026-08-12). The
  register's 9.1 "shipped builds unsigned" and 9.7 "release qualification pending" were stale.
  The next cut is `v0.2.1` or `v0.3.0` per `CHANGELOG.md`; no GA gate remains.
- **Signing budget (founder, 2026-09-02): pay the eSigner tier after the trial, but never
  beyond the monthly allocation — no overage.** Consequences: the sign-once mirror workflow stays
  the primary economiser (~8 signings per build at steady state, `cut-a-release.md:338-341`);
  release cadence is bounded by allocation ÷ 8 builds per month including rehearsals; item 1 adds
  a signing counter to the release workflow output so a cut never silently crosses the
  allocation, and rehearsal builds must run with signing off (`JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED`
  stays rehearsal-only as documented).
- **winget first, Scoop second, no Chocolatey** (its moderation queue is a poor fit for a
  fast-moving indie project). MSIX/Store stays rejected (ADR-0024; re-review after GA at most).
- **`CHANGELOG.md` is the human half of the release body.** The workflow prepends the
  verify-your-download blurb, inserts the tag's `CHANGELOG` section, then appends
  `--generate-notes`; a gate fails the release build if the tag has no section.

## §O. Owner actions

1. ~~Confirm secrets / mirrors~~ — verified read-only 2026-09-02: `JUSTSEARCH_CODESIGN_MODE` and
   `JUSTSEARCH_CODESIGN_COMMAND` set 2026-08-12; `sign-vendored-mirrors.yml` run 31614684797
   succeeded 2026-08-12. Post-trial plan decided (see Decisions). Nothing to do.
2. Be ready to open the `microsoft/winget-pkgs` PR the agent drafts (item 3); publisher segment
   stays `eliasjustus` unless you say otherwise.

## Scope

1. **Signing hardening for the release path** (now; the eSigner wiring exists and shipped
   v0.2.0): `JUSTSEARCH_REQUIRE_SIGNING=1` on tag builds so an unsigned candidate cannot be
   published; confirm `verify-windows-signature.ps1` covers every PE incl. sidecars and mirrors;
   move the four repo secrets into a `release` Environment bound to `build-installer.yml` only
   (no Environments exist today — coordinates with 904 item 0); add a per-run signing counter
   to the workflow summary and a monthly running total artifact so cuts stay inside the eSigner
   allocation (founder budget decision); document the steady state in `cut-a-release.md`.
2. **Close 617's status** (now): 617 still says "release qualification pending"; v0.2.0 shipped
   on 2026-08-13 — record which of 617 §9 items 2-5 were satisfied by 734's rounds and the tag
   build, and set 617's status accordingly (`retire-with-a-sweep` on its open list).
3. **winget submission for the existing v0.2.0** (now): `scripts/release/gen-winget-manifests.mjs`
   fills `packaging/winget/*.yaml` from the published `SHA256SUMS` + tag `v0.2.0`;
   `check-winget-manifests.mjs` validates against the 1.6.0 schema (`winget validate` if
   available); a dispatch workflow step produces the three files as an artifact and a
   ready-to-paste `winget-pkgs` PR body — the owner opens it. Scoop: a
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

- Item 1: a dispatch build with `JUSTSEARCH_REQUIRE_SIGNING=1` fails closed when the secret is
  absent and passes `verify-windows-signature.ps1` on every PE when present; SmartScreen check on
  a clean VM recorded in §Status (reputation may still warn — say so, do not overclaim).
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

Chartered, unblocked. All items startable now (order: 3 → 4 → 1 → 5 → 2).
