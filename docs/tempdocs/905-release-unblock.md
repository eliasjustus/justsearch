---
title: "Release hardening, package projections, changelog-driven notes, and model promotion"
type: tempdocs
status: MERGED (2026-09-03) — release hardening is on main; protected installer signing proven; packaged-verifier cleanup fix pending; WinGet submission withdrawn; 617 updater evidence and real model promotion remain open
created: 2026-09-02
updated: 2026-09-06
lane: 887 L16
model: gpt-5.6-sol (takeover, design, derisk, implementation)
parent: 887-improvement-landscape-register
related:
  - 760-installer-distribution-readiness   # signing drop-in; winget skeleton; "remaining owner-gated: cert/vendor decision, GA cut, winget publisher identity"
  - 772-installer-payload-composition      # signing thesis routed to 760 §K
  - 726-release-workflow-asset-set · 750-release-loop-scheduling · 617 §9 updater qualification
  - 802-release-artifact-provenance
  - 840-model-download-restructure · 915-index-fingerprint-identity · 826/819 (historical fingerprint work) · 317/253/358 (hand-run swaps)
  - 632 Stage G (SPDX headers, deferred)
  - docs/how-to/cut-a-release.md (the runbook these items extend)
---

# 905 — Release hardening, distribution projections, and model promotion

## Briefing for the agent picking this up

Read this file through the takeover, design, and derisk sections before implementation. Also read
887 Appendix A9, `docs/how-to/cut-a-release.md`, 760 §K, 617 §9, and the canonical model/fingerprint
documentation named below. Load `/installer` before touching NSIS, Tauri configuration, signing, or
installer packaging. Implement the slices independently; do not turn this into one cross-cutting PR.
Never commit a credential, `.pfx`, vendor token, or owner-supplied signing-budget value.

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
  beyond the monthly allocation — no overage.** A repository artifact cannot authoritatively track
  provider usage. The release gate needs a reviewed provider-portal remaining-budget input (or a
  future supported provider API/control), shared signing concurrency, and a fail-closed per-run
  maximum. Self-signed rehearsals remain allowed; production/vendor signing stays off for them.
- **WinGet first; Scoop is undecided, not an acceptance criterion.** A committed Scoop JSON is
  inert without a bucket/discovery path. The owner must choose Extras, a project bucket, or explicit
  remote-manifest installation before Scoop implementation. No Chocolatey; MSIX/Store stays rejected.
- **`CHANGELOG.md` is the human half of the release body.** The workflow prepends the
  verify-your-download blurb, inserts the tag's `CHANGELOG` section, then appends
  `--generate-notes`; a gate fails the release build if the tag has no section.

## §O. Owner actions

1. Re-enter the signing credential into the protected Environment, validate both signing workflows,
   then delete the repository-scoped copy. GitHub exposes secret metadata, not the value, so an agent
   cannot safely copy it.
2. Before any paid signing run, provide the portal's remaining-signings value and confirm the exact
   paid allocation/no-overage control. Repository counters remain advisory.
3. Update `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` from the transferred repository's old
   `eliasjustus/justsearch` endpoint to `justsearch-app/justsearch`, after the code gate is ready.
4. Open the generated `microsoft/winget-pkgs` PR. The package identifier remains
   `eliasjustus.JustSearch`; generated path/filenames must match that casing exactly.
5. Choose the Scoop delivery path before Scoop work enters scope.
6. For a future model promotion, approve the model/license and quality shift, publish immutable
   assets, and authorize the registry/release changes.

## Scope

1. **Signing hardening and final-payload verification.** Make every `v*` tag select the existing
   `package-installer-win.ps1 -Release` fail-closed contract while preserving optional signing for
   branch rehearsals. Pin and verify CodeSignTool before it can execute, and isolate signing secrets
   from unrelated updater/metadata keys. Put both paid-signing workflows behind one protected
   signing Environment and shared non-cancelling concurrency group. Add a structured per-run ledger,
   a reviewed pre-spend maximum, and strict verification of every shipped MZ-bearing file before
   publication. Keep provider-monthly accounting outside the repository unless SSL.com exposes a
   supported usage API.
2. **WinGet projection for published releases.** Replace the TODO skeleton/manual fill with a pure,
   deterministic generator from one authenticated published-release record plus product metadata.
   Generate schema 1.12.0 manifests under correctly cased identifiers/filenames; check YAML,
   determinism, and cross-file version/hash/URL identity locally. Provide a standalone v0.2.0
   preparation command/workflow, because the published release workflow must not be rerun to mutate
   an existing release. Treat official `winget validate` and Sandbox installation as separate
   evidence tiers. The owner opens the upstream PR.
3. **Changelog-driven release notes.** Create one tested parser/extractor/composer. Main validation
   requires a parseable `[Unreleased]` heading and unique version headings, but permits an empty
   post-release `[Unreleased]`. Release preparation moves entries into an exact, unique, non-empty
   `## [x.y.z] - YYYY-MM-DD` section before tagging; the tag lane rechecks it before draft creation.
   Compose the body deterministically from the trust notice, changelog section, and generated notes,
   and update an existing draft idempotently. Remove the manual next-cut list from the runbook.
4. **Canonical status reconciliation, without closing 617.** Record that v0.2.0 shipped signed and
   correct stale release/signing/mirror guidance in the runbook, download-verification guide, ADR,
   and release index. Keep 617's exact published-installer and in-app N→N+1 normal/interruption
   evidence gates open, or transfer them explicitly to a named successor; GA is not that evidence.
5. **Package-scoped model-promotion planner.** Replace the proposed monolithic swap command with a
   deterministic `plan`/evidence layer keyed by one registry package and one staged candidate bundle.
   Role adapters declare provenance, complete variants/support files, license, build/verification,
   quality, and migration requirements. The planner emits old/new registry records, asset BOM,
   immutable proposed URLs, evidence, projection diffs, and migration classification. Local build,
   production CPU/GPU verification, owner publication, remote byte verification, registry mutation,
   quality-baseline acceptance, merge, and release remain distinct gates. Exclude `cuda-runtime`.

## Acceptance criteria

- Item 1: workflow truth-table tests cover unsigned branch, self-signed rehearsal, and every tag
  selecting `-Release`; missing signing inputs fail before packaging. A self-signed fixture proves an
  extension-independent final MZ census, zero unsigned payloads, pinned signing-tool provenance,
  ledger accounting, and pre-publication ordering. SmartScreen remains a clean-VM observation, not a
  reputation guarantee.
- Item 2: v0.2.0 generation is deterministic and cross-file consistent with the published
  installer/hash; current official validation passes when available; Sandbox install evidence is
  recorded separately. No release is mutated and no upstream PR is opened by an agent.
- Item 3: fixture tests cover exact/missing/duplicate/empty release sections, a legitimate empty
  `[Unreleased]`, and idempotent body composition; tag validation runs before draft creation.
- Item 4: canonical docs state signed v0.2.0 truth while 617's unperformed updater lanes remain open.
- Item 5: a write-free plan against an explicit candidate produces deterministic evidence or precise
  missing-provenance/publication blockers. Role fixtures prove ONNX CPU/GPU closure, GGUF support,
  index-affecting classification `{embedding, splade, ner}`, and non-index roles. Do not require a
  real NER rebuild-and-revert as the unit acceptance fixture.
- `node scripts/ci/check-workflow-triggers.mjs`, `check-notices-regen.mjs`,
  `check-update-preserves-models.mjs` green; `/installer` checklist followed.

## Constraints

- No secrets in the repo; no Release, package-manager submission, or model asset published by an
  agent; no force-push. GitHub Environment creation and workflow wiring do not authorize secret
  migration or deletion.
- Non-goals: MSIX, Chocolatey, SPDX headers (632 G — separate small PR if wanted), integration
  plugins (887 L18), a generic artifact-promotion framework, and Scoop until its delivery path is
  chosen.

## §Status

The redesigned release-hardening slices and package-scoped, write-free model-promotion planner are
implemented in `codex/905-release-unblock`. Local verification is green; the independent
refute-first implementation re-review and the subsequent tempdoc-fit correction review are closed.
The implementation was committed as `a3cf0c4a`, pushed, and opened as
<https://github.com/justsearch-app/justsearch/pull/629>; hosted CI run `33795605667` and its CLA
check passed. No paid signing, GitHub Environment/secret mutation, Release mutation, upstream WinGet
submission, or model publication was performed. Production secret relocation, provider-budget
confirmation, the live repository-descriptor variable change, protected-Environment dispatches,
WinGet Sandbox install, and any real model promotion remain owner actions. Tempdoc 617 remains open
pending exact published installer N→N+1 normal/interruption/reconciliation evidence.

## Takeover and research pass (2026-09-03)

### Method

This pass re-read the charter and its current implementation owners, inspected the live repository
configuration and the published `v0.2.0` release/run, and checked the moving external contracts against
their primary sources. No implementation was started. The internet pass was warranted because WinGet's
accepted schema set, GitHub Environment behavior, and eSigner pricing/usage behavior are external
contracts that can change independently of this repository.

Primary sources consulted:

- Microsoft WinGet's current validation guide and manifest repository documentation:
  <https://learn.microsoft.com/en-us/windows/package-manager/package/repository>,
  <https://github.com/microsoft/winget-pkgs/blob/master/doc/ValidationFailureGuide.md>, and
  <https://github.com/microsoft/winget-pkgs/blob/master/doc/manifest/schema/1.12.0/README.md>.
- GitHub's Environment contract:
  <https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments>,
  and its secret metadata/value boundary: <https://docs.github.com/en/rest/actions/secrets>.
- SSL.com's signing-count and current code-signing tier documentation:
  <https://www.ssl.com/guide/esigner-signing-credential-guide/> and
  <https://www.ssl.com/guide/esigner-pricing-for-code-signing/>.
- Scoop's manifest/autoupdate documentation:
  <https://github.com/ScoopInstaller/Scoop/wiki/App-Manifest-Autoupdate> and
  <https://github.com/ScoopInstaller/Scoop/wiki/Buckets>.

No external code or text is copied into the repository; there is no new license or attribution
obligation from this research.

### Findings that change the charter

1. **WinGet schema 1.6.0 is a deprecated target.** The upstream repository marks 1.6.0 deprecated
   and its current failure guide recommends 1.12.0 (1.10.0 is also accepted). The existing
   three-file layout and correctly cased `eliasjustus.JustSearch` filenames are useful, but its schema
   declarations, placeholders, and README are stale. WinGet requires case-sensitive
   identifier/path/filename agreement. Validation should target 1.12.0 and include both
   `winget validate` and the
   upstream Sandbox test as separate evidence tiers; a home-grown JSON-schema check is useful on
   every PR but is not equivalent to the repository's validator.
2. **An Environment isolates secrets to jobs, not to one workflow file.** GitHub documents that an
   environment secret is available only to a job that references that environment, with optional
   reviewer and branch/tag rules. It does not provide a "this workflow filename only" boundary.
   Both `build-installer.yml` and `sign-vendored-mirrors.yml` legitimately spend eSigner signatures,
   so moving their shared credential into a `release-signing` Environment must update both jobs and
   use environment protection/concurrency. The charter's "bound to `build-installer.yml` only" would
   break the signed-mirror path and overstates what GitHub enforces.
3. **The tag lane still fails open when the credential is absent.** The workflow treats a tag as a
   signing request, but adds `-Sign` only when a signing secret is already present. A requested tag
   with no secret therefore takes the unsigned path. `package-installer-win.ps1 -Release` already
   carries the desired fail-closed semantics (`-Release` implies `-Sign` and
   `JUSTSEARCH_REQUIRE_SIGNING=true`); the workflow does not pass `-Release` today.
4. **The published path proves one observed steady-state count, but not complete packaged-PE
   coverage.** Run `31698999743` signed eight targets: the app executable, five NSIS/plugin DLLs,
   the generated uninstaller, and the setup executable. The workflow verifies the setup executable
   plus a verified uninstaller receipt. That supports budgeting the current shape, but eight must
   not become a timeless invariant. The workflow does not independently inventory every MZ-bearing
   file inside the final installer. The acceptance criterion needs one extraction-based
   inventory/verification authority,
   not a claim that the single-file verifier already covers the whole payload.
5. **A repository artifact cannot be the authoritative monthly spending counter.** SSL.com says one
   credential signature counts as one signing and exposes the certificate's monthly total in the
   account portal. Its pricing page says over-allocation signatures are charged; no supported
   machine-readable usage endpoint or provider-side no-overage control was found in the public
   documentation. A per-run receipt and an eight-signature ceiling can prevent accidental fan-out,
   but a cross-run Actions artifact is advisory and can miss local/manual signing or be deleted.
   The production gate therefore needs an owner-supplied, provider-authoritative remaining-budget
   confirmation (or a later documented provider API/control), not a self-declared cumulative total.
6. **The release-note source is demonstrably missing.** `CHANGELOG.md` has an `[Unreleased]` section
   but no `0.2.0` section. The published `v0.2.0` body contains the trust blurb followed by the full
   generated commit list. The desired extraction/gate is justified, but a tag gate alone is too
   late: main should require a parseable `[Unreleased]` heading, but allow it to be empty immediately
   after a release. A release branch must move content under the exact tag version before the tag exists.
7. **The WinGet skeleton is fillable now, and release identity needs one additional correction.** The published installer is
   `JustSearch_0.2.0_x64-setup.exe`, SHA-256
   `cba354165c38c90628082020d40fe00986814a3fa57da49c62dd18acb0f11772`; the installed publisher
   authority is `Elias Justus`. The generator should preserve the stable package identifier
   `eliasjustus.JustSearch`; its existing project URL already uses `justsearch-app/justsearch`.
   Separately, the live `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` repository variable still points to
   `eliasjustus/justsearch`, and future compiled updater clients would retain that transferred-repo
   endpoint until the owner changes it. A pre-tag gate should assert the canonical repository identity.
8. **Item 5 is not one generic model swap.** The shipped registry has five ONNX packages plus chat
   and runtime packages, while this checkout contains committed `build.json` provenance for only the
   SPLADE directory. Build CLIs are role-specific; `verify-model.py` is CPU-only; the registry needs
   stable `downloadUrl` values in addition to SHA-256 and size; and publishing those bytes is an
   outward owner action absent from the proposed sequence. The correct seam is a model-promotion
   planner/orchestrator that delegates to existing builders and emits a reviewable promotion bundle;
   it must not replace the builders or pretend it can publish assets.
9. **The fingerprint dependency statement is stale.** Tempdoc 819 was merged in PR 470 on
   2026-08-17. Tempdoc 826 still says it is blocked on that owner decision, while later tempdoc 915
   has merged the single `index_fingerprint` design and includes model digests in the rebuild-required
   index identity. Item 5 must re-read 915/826 at implementation time and describe the current
   automatic migration contract; it must not preserve 905's "826 blocked on 819" wording.
10. **Canonical release history is stale, but 617 is not closable.** `docs/how-to/cut-a-release.md`
    still lists `v0.2.x` as pending despite the published `v0.2.0` release. Tempdoc 617 still requires
    the exact published installer-over-release lane plus normal and interrupted in-app N→N+1 evidence;
    734's last recorded round was fresh-install and does not supply those observations. Update the
    stale shipped/signing record, but keep 617 open or transfer its exact gates to a named successor.
11. **The signing-tool bootstrap is an executable supply-chain boundary.** Both paid-signing
    workflows download a mutable CodeSignTool ZIP, log its digest, and execute it without comparing
    that digest or a publisher signature to a trusted pin. In the installer job it runs alongside
    signing and release/updater secrets. Pin and verify the tool before execution and separate
    unrelated keys from that job; Environment relocation alone does not harden this boundary.
12. **Secret relocation requires owner re-entry.** GitHub exposes secret metadata without revealing
    values, and setting an Environment secret requires a new encrypted value. The code/topology can
    be prepared now, but the actual copy, validation, and repository-secret removal require a secure
    owner ceremony.
13. **A Scoop file without a channel is inert.** Scoop discovers manifests through bucket
    repositories. An Extras PR, a maintained project bucket, or explicit remote-manifest install
    flow must be selected before Scoop can have a meaningful acceptance criterion.

### Takeover verdict

**LITE-CLASS: no.** This work changes release-policy enforcement, external-package projections, and
model-promotion structure; it is not pure teardown/rename/config deletion.

**GO in independent slices after the redesign below; NO-GO on the original charter.** Signing
code/verification, WinGet, changelog/release notes, canonical status reconciliation, and a separately
verifiable model-promotion planner address evidenced gaps. Do not close 617, migrate secrets without
the owner, or treat a repository counter as provider authority. Scoop remains out of scope until its
delivery channel is chosen. The whole charter must not be implemented as one undifferentiated change.

The cheapest evidence for the distribution need already exists: the committed 1.6.0 skeleton is
deprecated upstream and `v0.2.0` provides immutable asset/hash inputs. The cheapest evidence for the
signing gap also already exists: the workflow's credential-present conditional proves a tag can fall
through unsigned. The cheapest evidence against the original item-5 design also already exists: the
registry/provenance inventory cannot satisfy its promised no-op preflight, and there is no publication
step that could make new registry URLs true.

This work displaces the manual WinGet placeholder fill, the manual release-notes list in
`cut-a-release.md`, the tag lane's silent unsigned fallback, mutable/unverified signer bootstrap, and
stale `v0.2.x pending` records. A model-promotion tool would compose the existing role-specific
builders; it would not supersede them. Tempdoc 617's unperformed evidence gates are not displaced.

**BLOCKED ON YOU**

- The exact paid eSigner tier/allocation and whether the account offers a provider-side "no overage"
  control are not recorded in the repository. That fact is required only for a hard monthly budget
  gate; it does not block the per-run ceiling, release fail-closed change, WinGet, CHANGELOG, status
  sweep, or model-promotion design.
- Re-entering the signing credential into an Environment, validating it, and deleting the
  repository-scoped copy require an owner-held secret value.
- Changing the live `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` variable to the transferred repository is an
  owner mutation; the implementation slice should first add the fail-closed identity check.
- Opening the eventual `microsoft/winget-pkgs` PR remains an owner action, as already chartered.
- Scoop needs an owner choice of delivery channel before implementation.
- New model assets and any deliberate quality-baseline shift require owner publication/acceptance.

**PROCEEDING / DONE**

- Takeover and primary-source research are done.
- Design and derisk can proceed without either owner action above.

## Design (2026-09-03)

### Design summary

The correct solution is five independently reviewable changes joined by two narrow principles: a
release is projected from one immutable published identity, and promotion is an evidence-backed state
transition rather than an in-place mutation. There is no shared implementation framework between
release packaging and model promotion in this tranche.

#### Slice A — signing admission, supply-chain trust, and final-payload proof

1. Treat `package-installer-win.ps1 -Release` as the single stable-tag admission contract. The
   workflow must select it for every `v*` tag before consulting credential presence. Non-tag dispatch
   retains unsigned and explicit self-signed rehearsal modes. Add a small workflow truth-table test so
   refactoring cannot restore the current tag-plus-missing-secret unsigned path.
2. Replace the mutable CodeSignTool bootstrap with a versioned download whose archive digest and/or
   trusted publisher signature is checked against a reviewed pin before extraction or execution.
   Fetch/verify it before materializing unrelated release/updater private keys. Keep signer credentials
   in the signing step only; keep metadata/updater keys in later release-assembly steps. A tool-integrity
   failure occurs before any vendor invocation.
3. Use a protected `release-signing` Environment for both `build-installer.yml` and
   `sign-vendored-mirrors.yml`; this supersedes 904 item 0's workflow-filename-bound wording. Apply
   tag/branch and reviewer protection there, and apply one shared, non-cancelling workflow concurrency
   group separately. Environment protection controls secret access; concurrency controls spend.
4. Extend the shared signer with an append-only JSON-lines ledger written only after a verified
   successful signature. Each record carries run-local target identity, digest, signer mode, result,
   and ordinal, but no credential data. The caller clears the ledger at run start, declares a reviewed
   maximum, refuses the next invocation when it would exceed that maximum, and summarizes actual
   consumption. The cap is derived from a pre-sign MZ census, with a checked policy ceiling; the
   observed eight-signature v0.2.0 run is evidence, not a permanent constant.
5. Generalize the existing MZ-header inventory used by `sign-vendored-payload.ps1` into one strict
   census/verification authority. Re-extract signed mirror archives and the final NSIS installer,
   enumerate every MZ-bearing file regardless of extension, and require a valid Authenticode signature
   on all of them plus the outer setup and generated uninstaller. Run this gate in the publishing job
   before the draft can be published; a post-publication verification job is not an admission gate.
6. Require an owner-supplied provider-portal remaining-signings value (or later supported provider
   API result) greater than or equal to the run maximum before production signing. The repository
   ledger is an auditable per-run guard, never the monthly authority.

This slice supersedes the credential-dependent tag fallback, mutable/unverified CodeSignTool download,
mirror-only concurrency, extension-glob/pre-repack-only inventories, setup-only final verification, and
the proposed monthly Actions artifact. It preserves the existing signing modes, sign-once mirrors,
uninstaller receipt, and self-signed rehearsal contract.

#### Slice B — WinGet as a deterministic release projection

1. Introduce one small checked-in product/distribution metadata authority for stable fields. A pure
   generator consumes that authority plus a closed published-release record containing tag, version,
   publication date, installer filename/URL, SHA-256, and repository identity. For v0.2.0 those dynamic
   values must be authenticated against the published asset and `SHA256SUMS`, not manually copied.
2. Generate the schema-1.12 three-file manifest set into a versioned output directory whose path and
   filenames exactly match `eliasjustus.JustSearch`. Do not retain a second editable YAML authority;
   retire the TODO skeleton and manual-fill README once generation is established.
3. The local checker reparses YAML, reruns generation byte-for-byte, and enforces cross-file package
   ID, version, URL, hash, locale, architecture, installer type, scope, and release-date agreement.
   Official `winget validate` is the next evidence tier, and an upstream Sandbox installation is a
   separate owner/GUI tier.
4. Provide an independently dispatchable preparation workflow for already-published v0.2.0 that
   uploads only the generated submission bundle and ready-to-paste PR text. Future release jobs may
   call the same generator/checker, but must not auto-submit or mutate a published GitHub Release.

Scoop is deliberately absent. It can reuse the immutable release input only after the owner chooses a
real channel; generation alone would create an unowned projection.

#### Slice C — changelog authority and idempotent release-body composition

1. Use one pure parser as the authority for validation, extraction, and body composition. Main checks
   structure, the `[Unreleased]` heading, unique versions, descending versions/dates, and link syntax;
   an empty `[Unreleased]` immediately after a cut is valid.
2. A pre-tag release-preparation command moves selected unreleased entries into one exact
   `## [x.y.z] - YYYY-MM-DD` section. The tag job requires that unique section to be non-empty before
   creating or reusing a draft.
3. Ask GitHub for generated notes, then deterministically compose the trust/download-verification
   notice, human changelog section, and generated appendix. Create or edit the draft with those exact
   notes on every retry, so retry behavior cannot preserve an obsolete body.
4. Retire the manually maintained "Release notes for the next cut" list in
   `docs/how-to/cut-a-release.md` once the changelog is authoritative.

#### Slice D — truthful release-status reconciliation

Update the release index to the shipped v0.2.0 record and sweep stale claims that the installer is
unsigned, signing is dormant, or mirrors are empty in `cut-a-release.md`, `verify-your-download.md`,
ADR-0024, and neighboring canonical documentation. Record which 617 gates are actually evidenced.
Leave the exact published-installer-over-release and in-app normal/interruption/reconciliation gates
open in 617, or transfer them verbatim to a named successor with a bidirectional pointer. Do not call
v0.2.0 publication equivalent to updater application qualification.

#### Slice E — package-scoped model-promotion planning

1. The planner accepts one registry package ID, its current record, and one explicit staged candidate
   bundle. It never scans/promotes every model and never treats `check-integrity.py`'s successful
   skip-without-`build.json` behavior as proof. ONNX candidates carry complete build provenance;
   GGUF candidates carry an equivalent immutable source revision/digest manifest.
2. A maintainer-only adapter table keyed by package ID defines required variants/supporting files,
   builder, license/provenance checks, production CPU/GPU checks, live behavior, quality/performance
   suite, and migration classification. `tier` is not a role discriminator. Assert that the set of
   index-affecting roles stays exactly `{embedding, splade, ner}` unless fingerprint ownership changes.
3. `plan` is write-free and emits deterministic JSON plus a human review: old/new registry records,
   asset BOM, provenance/licenses, SHA-256/size, proposed immutable URLs, verification evidence,
   generated-file diffs, and index-migration classification. A no-op is valid only when an explicit
   candidate equals the current registry identity and its projections.
4. Keep phases explicit: plan; local prepare/verify using existing role builders; owner publication;
   read-only retrieval and remote byte verification; registry/docs/projection patch; live activation,
   migration, and role-quality evidence; owner acceptance/merge/release. Existing URLs are immutable;
   changed bytes require new coordinates before a registry patch can be truthful.
5. Adapters cover embedding, SPLADE, NER, reranker, citation scorer, and chat/chat-compact. They must
   account for current builder/registry mismatches and for CPU-only `verify-model.py`; production GPU
   checks use the production verification path. `cuda-runtime` remains in its runtime pin/mirror flow.

This supersedes only the proposed fixed `build → integrity → CPU verify → registry → notices →
rebaseline` choreography, unconditional notice/rebaseline steps, hand-copied hashes, and a destructive
real-NER-build-and-revert acceptance test. It preserves role builders, integrity checks, production
runtime verification, registry loader/install planner/downloader, notice generation, jseval, and the
already-implemented fingerprint/blue-green migration architecture.

### Adjacent work and ownership

- **760:** supplies the signing and distribution history; its ad-hoc PE census becomes a reusable
  pre-publication assertion, while sign-once mirrors remain.
- **904 item 0:** must adopt the same job-scoped signing Environment topology and must not claim
  workflow-filename isolation. Secret migration remains one owner ceremony shared by both tempdocs.
- **617 / 734:** v0.2.0 and fresh-install evidence update stale status but do not close in-app updater
  gates. No evidence is backfilled by assertion.
- **819 / 826 / 915:** 819 is merged and 915's unified embedding/SPLADE/NER fingerprint is current.
  The model planner consumes that contract; it does not reopen the historical 826-on-819 dependency.
- **Canonical model docs:** implementation must repair the discovered stale claims that every model
  directory has `build.json` and that the GTE FP32 artifact is unshipped, in the same model slice.

### Design reach

Two principles may generalize, but neither earns a shared framework in 905:

1. **Immutable release description → multiple projections.** GitHub release assets, WinGet, and a
   future Scoop channel should consume the same version/URL/hash identity. Promote this into a shared
   release-description schema only after two checked-in projections use it and a drift test proves it
   removes duplicated authority. Retire the abstraction if consumers require incompatible lifecycle
   or provenance fields that produce branching rather than shared invariants.
2. **Plan → publish → verify → activate.** Model packages, signed runtime mirrors, and other large
   immutable assets may share this promotion shape. Keep it a design principle until a second package
   class demonstrates identical evidence/phase needs. Retire or narrow it if role-specific adapters
   contain most of the behavior or if a generic layer weakens an existing package-specific gate.

The design is repo-wide in policy reach (release admission, external distribution, model registry),
but each implementation slice stays within its owning workflow/scripts/docs. No module boundary,
search analyzer, local API trust boundary, or runtime index-I/O ownership changes.

## Derisk (2026-09-03)

### Confidence-building plan

No implementation was performed. The cheapest useful probes were chosen to invalidate assumptions
before code exists:

1. Exercise the existing signer regression suite, especially required-signing-without-input and the
   NSIS temporary-extension shim.
2. Confirm the current workflow policy baseline and local availability of the two official/extraction
   tools needed by the proposed evidence tiers.
3. Run the current WinGet skeleton through the installed official validator to learn whether it is a
   usable fixture without first writing a generator.
4. Recount registry packages, tracked model provenance, changelog release headings, and the current
   fingerprint inputs from repository truth.
5. Have bounded, independent agents audit release surfaces, model-promotion boundaries, and the
   takeover verdict refute-first; incorporate findings before rating confidence.

### Probe results

- `scripts/ci/test-sign-windows.ps1`: **PASS, 8/8**. PFX, store, and command modes signed a disposable
  fixture; required signing with absent inputs returned the expected failure; optional absent inputs
  skipped; timestamping, the `.tmp` uninstaller shim/receipt, and trap cleanup passed. This proves the
  existing fail-closed primitive is sound enough to reuse. It does not prove workflow selection,
  CodeSignTool provenance, run-ledger caps, or a final-installer census.
- `node scripts/ci/check-workflow-triggers.mjs`: **PASS** on the unchanged baseline. This establishes a
  clean workflow-policy starting point, not correctness of the proposed edits.
- Local tools: WinGet **v1.29.290** and 7-Zip are available. `winget validate --manifest
  packaging/winget` fails because the mixed documentation/manifest directory causes the README's
  Markdown to be parsed as YAML; validating the version file alone correctly fails because a
  multi-file manifest is incomplete. This cheaply rejects using the current source directory as the
  submission bundle and supports a generated YAML-only output directory. A generated 1.12.0 bundle
  still needs validation during implementation.
- Registry/provenance census: the active registry has **8 packages**, while only
  `models/splade/naver-splade-v3/build.json` is tracked. The current changelog has `[Unreleased]` plus
  a template heading and no shipped version section. Current `IndexFingerprint` names embedding,
  SPLADE, and NER model digests. These results reject the original all-registry no-op and stale
  fingerprint dependency while supporting explicit candidate/package planning.
- Independent release and model audits agreed on the slice boundaries. The refute-first review found
  four high-impact omissions that changed the active contract: unverified mutable CodeSignTool,
  unperformed 617 updater evidence, owner-required secret re-entry, and the stale live release
  descriptor URL. It also rejected always-non-empty `[Unreleased]` and inert Scoop generation.

### Remaining uncertainty and retirement tests

| Uncertainty | Current risk | Cheapest implementation-time evidence | Decision if it fails |
|---|---|---|---|
| Trusted CodeSignTool pin/signature source | High: executing mutable vendor bytes beside secrets | Fixture the pinned-download verifier, then verify one reviewed production tool archive without signing | Stop production workflow wiring; require a checked-in vendored/pinned tool acquisition decision |
| Environment secret topology and least privilege | High: shared signer secrets versus installer-only updater keys | Workflow fixture/tests plus a protected-Environment dry dispatch before owner migration | Split jobs/reusable workflow if one job cannot avoid exposing unrelated keys |
| Provider monthly budget authority | High financial risk, owner-dependent | Owner supplies portal remaining count and confirms tier/no-overage behavior | Keep production signing disabled; per-run ledger alone is insufficient |
| Final NSIS/mirror MZ completeness | Medium-high: hidden unsigned executable | Self-signed synthetic archives plus extracted real rehearsal installer, including renamed MZ fixture | Do not publish; refine the shared census, never add an extension allowlist |
| WinGet 1.12 semantic validity | Medium | Generate v0.2.0 YAML-only bundle, run official validator, then Sandbox install | Fix metadata/projection; do not weaken checker or claim internal-schema equivalence |
| Draft release-body retry semantics | Medium | Mock create/reuse/edit API flow and compare byte-identical composed body twice | Keep draft unpublished; change orchestration to exact-body update |
| 617 updater application qualification | High product-evidence gap | Exact published N→N+1 normal and interrupted Sandbox evidence bundle | Keep 617 open; never infer from fresh install or publication |
| Model adapter/provenance coverage | High for real promotion | Synthetic per-shape fixtures, exhaustive package-ID mapping, then one owner-chosen real candidate | Keep planner read-only/blocking; do not invent missing provenance or overwrite URLs |

### Confidence and implementation recommendation

- **Slice A signing:** 7/10. The fail-closed signer exists and passes its regression suite, but trusted
  tool provenance, least-privilege job topology, and final-payload extraction need fixtures before any
  production/secret ceremony.
- **Slice B WinGet:** 8/10. Inputs and generator boundary are clear; official validation of generated
  schema-1.12 output and Sandbox installation remain.
- **Slice C changelog:** 9/10. The parser/composer contract and failure fixtures are narrow and local.
- **Slice D status reconciliation:** 9/10. Evidence is sufficient precisely because the design leaves
  617 open instead of manufacturing closure.
- **Slice E model promotion:** 6/10 for a real promotion, 8/10 for the planner/evidence layer. Package
  shapes are understood, but only one tracked `build.json` and no selected candidate mean publication,
  live activation, migration, and quality acceptance cannot be proven in this task.

**Overall implementation confidence: 7.5/10.** Proceed slice-by-slice, in the order C → B → D → A →
E. C/B/D establish low-risk authorities and correct stale truth; A then changes the sensitive release
boundary with its new fixtures; E remains an independent planner tranche and should not block release
hardening. Use `gpt-5.6-terra` at high effort for C/B/D and `gpt-5.6-sol` at xhigh effort for A/E,
with a refute-first review before owner-facing or production steps.

### Final takeover state

**GO** for the redesigned C/B/D/A implementation slices and the read-only/planning portion of E.
**NO-GO** for the original undifferentiated charter, for closing 617, for Scoop without a channel, for
secret migration without owner re-entry, for paid signing without provider-budget authority, and for
a real model promotion without an explicit candidate and immutable publication path.

**BLOCKED ON YOU:** the owner actions in §O. None blocks completion of C/B/D, signing code/tests before
the protected dry dispatch, or the package-scoped model planner and synthetic fixtures.

## Implementation plan (2026-09-03)

The plan skill was applied after research, design, and derisk. Work remains in this dedicated worktree;
no PR, release, package-manager submission, secret mutation, or paid signature is authorized. Checked
items below become part of the acceptance contract.

### P0 — shared guardrails and integration ownership

- [x] Preserve optional unsigned and self-signed branch rehearsals; only a `v*` tag selects the
  production `-Release` contract.
- [x] Keep edits path-disjoint across agents. The primary agent owns both signing workflows,
  `scripts/ci` signing changes, workflow composition, canonical docs, generated docs, and final
  integration. Bounded agents own only the file sets named in P1, P2, and P5.
- [x] Do not run paid signing, create/move/delete GitHub secrets or Environments, change repository
  variables, mutate a Release, publish model assets, or submit package manifests.
- [x] Re-read changed workflows end to end and run the workflow signal-policy gate after integration.

### P1 — changelog parser and release-body authority (bounded agent)

- [x] Add a pure release-changelog module and CLI under `scripts/release/` with `check`, `extract`,
  release-preparation, and deterministic body-composition operations. Reuse one parser for all modes.
- [x] Add fixtures/tests for valid structure, empty `[Unreleased]`, missing/duplicate/empty requested
  version, release rollover, generated-note composition, and byte-identical retry output.
- [x] Move the existing public-release entry from `[Unreleased]` into `## [0.2.0] - 2026-08-13`,
  leaving the heading present and empty. Do not invent release notes beyond the existing text.
- [x] Do not edit `build-installer.yml` or canonical docs in the bounded lane; return the CLI contract
  and exact workflow integration instructions to the primary agent.

### P2 — WinGet deterministic projection (bounded agent)

- [x] Establish one stable product metadata authority and a pure release-input contract. Generate a
  schema-1.12 three-file YAML set into a YAML-only versioned output path with exact package casing.
- [x] Authenticate v0.2.0's installer URL/hash/date against an explicit immutable release record; do
  not silently fetch mutable `latest` state during pure generation.
- [x] Add a checker and tests for deterministic bytes, parseability, required fields, and cross-file
  package/version/hash/URL/date/locale/architecture/installer/scope agreement.
- [x] Add a standalone manual preparation workflow that produces an artifact and PR-body text only;
  it must not create/edit a GitHub Release or submit upstream.
- [x] Run official `winget validate` against the generated YAML-only v0.2.0 bundle. Delete the manual
  TODO YAML skeleton and replace its README instructions in the same slice.
- [x] Do not add Scoop files or touch `build-installer.yml`.

### P3 — signing admission, trusted tool bootstrap, spend ledger, and PE census (primary agent)

- [x] Extract workflow decision logic into a testable helper or add an equivalent truth-table gate:
  unsigned branch, explicitly signed branch, self-signed rehearsal, stable/prerelease tags, and
  missing production credential. Every tag passes `-Release`; missing input fails before packaging.
- [x] Replace both mutable CodeSignTool downloads with one checked version/digest authority and
  shared bootstrap script. Verify the archive before extraction/execution and test digest mismatch.
  If no trustworthy reviewed pin can be established from repository/provider evidence, leave paid
  command-mode execution fail-closed and record the owner decision rather than guessing a digest.
- [x] Limit secret materialization by step/job purpose. Add the same protected Environment reference
  and non-cancelling concurrency group to both paid-signing workflows without attempting the owner
  secret migration.
- [x] Extend `sign-windows.ps1` with a credential-free structured run ledger written only after a
  verified signature and a pre-invocation maximum. Preserve the existing uninstaller receipt and add
  ledger/cap/retry tests.
- [x] Factor or extend MZ-header discovery into one strict verifier used for signed mirror re-extraction
  and final NSIS payload verification. Test renamed MZ files, signed/unsigned mixtures, repack drift,
  and zero-unsigned enforcement.
- [x] Put the complete installer census before Release publication; retain the later packaged-install
  smoke as defense in depth.
- [x] Add a production workflow input/preflight for provider-authoritative remaining budget versus the
  computed run maximum. Never persist a claimed monthly total as repository truth.

### P4 — workflow integration, release identity, and changelog notes (primary agent)

- [x] Integrate P1's parser/composer before draft creation. Request generated notes, compose the exact
  trust notice + version section + generated appendix, and create or edit an existing draft with the
  same bytes on retry.
- [x] Add a fail-closed pre-tag/release check that the descriptor URL uses the canonical
  `justsearch-app/justsearch` repository. Do not mutate the live variable; give the owner the exact
  required value after the guard exists.
- [x] Ensure all final PE, checksum, authenticated updater-set, and changelog checks occur before
  `--draft=false`.

### P5 — package-scoped model-promotion planner (bounded agent)

- [x] Add a write-free planner plus deterministic JSON schema/fixtures keyed by one explicit registry
  package and staged candidate. No builder, uploader, registry writer, or generic artifact framework.
- [x] Add exhaustive package-ID adapter policy for embedding, SPLADE, NER, reranker, citation,
  chat/chat-compact; explicitly exclude `cuda-runtime`. Assert index-affecting roles are embedding,
  SPLADE, and NER.
- [x] Represent ONNX and GGUF provenance/closure separately; report precise missing build metadata,
  supporting files, immutable URLs, remote verification, production CPU/GPU checks, quality suite,
  generated projections, and migration evidence as blocking requirements.
- [x] Test deterministic/no-write output, explicit-candidate no-op semantics, missing provenance,
  incomplete CPU/GPU or GGUF support files, URL reuse with changed bytes, index classification, and
  notice-generation no-op versus identity/license drift.
- [x] Update canonical model inventory/runtime documentation only within the bounded model lane, then
  report all canonical paths so the primary agent can run the required regeneration sequence once.

### P6 — truthful canonical status and teardown (primary agent)

- [x] Update `cut-a-release.md`, `verify-your-download.md`, ADR-0024, and other source-proven stale
  claims: v0.2.0 shipped signed, signed mirrors are populated, current release identity, signing
  admission/budget procedure, changelog authority, and WinGet generation.
- [x] Update 617 and the release index with evidenced status only. Keep the published-installer and
  in-app normal/interruption/reconciliation lanes open or transfer them verbatim with pointers.
- [x] Coordinate the protected signing Environment wording with 904 without claiming workflow-name
  isolation or performing secret migration.
- [x] Delete/tombstone every superseded manual list, TODO skeleton, stale status claim, duplicate
  parser/projection, or advisory monthly-total mechanism in the same slice that replaces it.

### P7 — verification and critical review

- [x] Run all new focused tests plus `scripts/ci/test-sign-windows.ps1`, official WinGet validation,
  `check-workflow-triggers.mjs`, `check-notices-regen.mjs`, and
  `check-update-preserves-models.mjs`.
- [x] Run the installer skill's affected compilation/test checks in proportion to edits; no paid
  credential is required. Do not claim SmartScreen, Environment, or updater Sandbox evidence without
  an actual owner/GUI run.
- [x] After canonical doc edits, regenerate `docs/llms.txt` and synced skills, then run canonical link,
  skill-sync, module-graph, and runtime-config verification required by docs-maintenance.
- [x] Run `git diff --check`, inspect the complete diff, confirm only this worktree changed, and obtain
  an independent refute-first review before fixing any findings.
- [x] Update this checklist and §Status with commands, outcomes, residual owner actions, and an honest
  implementation verdict. Do not create a PR or commit unless separately requested.

## Implementation Result (2026-09-03)

**Verdict: code-complete for the redesigned scope; operationally gated on the owner actions below.**
The original charter remains superseded: Scoop has no chosen channel, 617 has no qualifying live
updater evidence, and no real model candidate/publication authority was supplied.

Implemented outcomes:

- Release admission is centralized and fail-closed. Every `v*` tag selects `-Release`, must exactly
  match the single `gradle.properties` version, rejects rehearsal trust relaxation, requires the
  configured mode's complete inputs (including a command `{file}` placeholder), and requires the
  reviewed provider-portal remaining allocation before packaging. Command/vendor mode also rejects
  the rehearsal trust override on branches, and the shared signer itself requires a ledger path and
  positive ceiling before any command invocation. Only throwaway PFX/store certificates can bypass
  trusted-chain verification during a rehearsal.
- The two signing workflows share protected-Environment placement and non-cancelling spend
  concurrency. CodeSignTool acquisition is version/digest pinned. Every provider invocation is
  durably reserved in an append-only attempt journal before execution; outcomes are appended, while
  the separate verified ledger is written only after local Authenticode verification. Failed calls
  and retries therefore consume the run ceiling.
- Final installer and mirror verification discovers all MZ-bearing files regardless of extension,
  verifies extracted and repacked payloads, and precedes publication. A mixed archive fixture proves
  already-valid skip plus renamed unsigned-PE signing with exactly one attempt/signature.
- `CHANGELOG.md` is now the human release-note authority through one deterministic parser/composer.
  WinGet schema-1.12 manifests are deterministically projected into the upstream-ready hierarchy
  from authenticated v0.2.0 release identity by a standalone artifact-only workflow. The workflow
  cross-checks GitHub's installer digest against the authenticated published `SHA256SUMS`, the local
  checker reparses YAML and enforces cross-file semantics, and the manual TODO skeleton is removed.
- Canonical release/update status now records signed v0.2.0 and populated mirrors without closing
  617. The model-promotion tool is package-scoped, deterministic, write-free, schema-validating, and
  exhaustive over the seven model package roles while excluding `cuda-runtime`. Its review bundle
  retains canonical provenance, remote byte facts, evidence references, projection results/diffs,
  and a license-specific approval gate.

Verification evidence:

- Changelog + WinGet Node suites: **18/18 passed**; official `winget validate`: **passed**.
- Model planner: **17/17 passed**, including review-bundle retention, license-approval blocking,
  unknown-property rejection, and invalid-URI rejection.
- Signing resolver/bootstrap/signer/PE-verifier/mixed-archive suites: **all passed**; signer rehearsal
  covers **12/12** cases, including direct command-mode trust/budget rejection without invoking the
  fake vendor, a failed vendor invocation consuming the only attempt, and the next call being
  refused before vendor execution.
- Workflow YAML parse, workflow signal policy, notice regeneration, model-preserving update gate,
  MCPB consistency, ADR coverage, canonical links, generated docs/skills, module-dependency
  canonical check, and runtime-config matrix: **passed**.
- `./gradlew.bat build -x test`: **BUILD SUCCESSFUL**. Notice/license regeneration checks passed
  using the installed stable Rust toolchain. `git diff --check`: **passed** (line-ending notices only).
- After replaying the single 905 commit onto refreshed `origin/main`, GitHub PR **#629** CI run
  **33795605667** passed all public-claims, license/notices, build, unit-test, Windows-native, Rust,
  integration, secret-scan, and wall-clock-report lanes; CLA run **33795605735** also passed.
- Independent refute-first review initially found four blockers: tag/version identity, mode-specific
  credential admission, success-only spend accounting, and unenforced model-candidate shape. All
  four were fixed and the re-review reported no remaining release-safety or signing-spend blocker.
  Its final non-blocking suggestion—rejecting a command template without `{file}` during preflight
  rather than inside the signer—was also implemented and the resolver regression rerun green.
- The later tempdoc-fit review found three conceptual gaps: command-mode rehearsal could bypass the
  provider-budget preflight, the model plan discarded required review-bundle evidence, and the
  WinGet projection was flat/byte-only and trusted only GitHub's installer digest. All three were
  corrected and rerun through their focused contract suites before this result was finalized. A
  first correction hardened only workflow admission; refute-first re-review then reproduced a direct
  signer bypass, so the shared signer now enforces command-mode trust and ledger prerequisites too.
  The final independent re-review passed with no blocking or material findings.

Residual owner actions:

1. Create/protect the `release-signing` Environment, re-enter the chosen credential there, validate
   both signing workflows, and only then remove repository-scoped copies.
2. Confirm the paid eSigner allocation/no-overage control and provide the current portal remaining
   value for each paid dispatch; do not infer it from repository journals.
3. Set `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` to
   `https://github.com/justsearch-app/justsearch/releases/latest/download/release.v1.json`.
4. Run the protected release/mirror dispatches when intended. No paid signature was consumed here.
5. Review and submit the generated WinGet bundle upstream, then record separate Sandbox installation
   evidence. No upstream PR was opened here.
6. Supply/approve immutable assets, provenance, licenses, quality evidence, and publication authority
   before a real model promotion.
7. Complete tempdoc 617's exact published-installer N→N+1 normal, interrupted, and reconciliation
   lanes; neither a fresh install nor successful publication substitutes for those observations.

## Operational follow-through (2026-09-03)

- PR `justsearch-app/justsearch#629` merged through the protected merge queue as
  `7aa4a916fbee342ac4678bcf5603570f5fc1a0a0`. Merge-group CI run `33797308631` and post-merge
  `main` CI run `33797753403` passed. The published merge tree exactly matched the reviewed PR head.
- GitHub Environment `release-signing` now requires reviewer `eliasjustus`, permits self-review so
  the sole owner is not locked out, disables administrator bypass, and restricts deployment refs to
  branch `main` or tags matching `v*`. No paid workflow was dispatched.
- Repository variable `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` now points to
  `https://github.com/justsearch-app/justsearch/releases/latest/download/release.v1.json`.
- A credential-safe temporary helper was adapted from the earlier tempdoc-760 script. It prompts in
  a separate terminal and targets only Environment secrets. Repository-scoped copies remain in place
  until the Environment values are present and both workflows can be validated without guessing the
  provider allowance. The owner retains the SSL.com username and password but reported that the
  eSigner-specific values were lost, so the helper was closed without writing any Environment secret.
  GitHub cannot reveal the existing encrypted repository-scoped values, and those copies were
  deliberately left untouched. Before the helper can be rerun, use the certificate order to verify
  the signing credential ID and recover or replace the eSigner TOTP enrollment secret. SSL.com's
  self-service QR reset requires the 4-digit eSigner PIN; its PIN reset requires either a current OTP
  or the current PIN. The owner then reported three unsuccessful PIN attempts followed by a provider
  disablement message. A read-only inspection of the authenticated SSL.com order page on 2026-09-03
  established the actual account state: the order and certificate remain `issued`, eSigner remains
  active, the order has zero YubiKeys, and its single signing credential is explicitly selected as
  `disabled`. The same page exposes an available `signing credential enabled` control. Its signing
  log contains prior successful `SIGN_HASH` entries but no recorded PIN-failure or lockout event. This
  is therefore a disabled cloud-signing credential, not a revoked certificate or missing hardware
  token. Do not order another token or credential, and do not guess further PINs. With owner
  confirmation at the moment of the account mutation, first try re-enabling the existing credential
  in the portal; if the control fails or eSigner still returns the exact `key status is disabled`
  error, SSL.com's error reference says support must re-enable it. Its public eSigner documentation
  does not publish a three-attempt threshold, so retain the literal error for support and recover the
  PIN/QR/TOTP enrollment path rather than rotating repository state.
- The owner recovered the eSigner factors and re-enabled the existing credential. A second live portal
  inspection confirmed the credential is `enabled`, the certificate remains `issued`, the current
  plan is **Personal ID Code Signing Tier 1 Annual**, and the provider-authoritative balance is
  **240 unused signings**. No provider-side no-overage switch was exposed; the published plan charges
  for additional signings, so JustSearch's reviewed per-run admission value and hard signature ceiling
  remain the no-overage enforcement. The credential-safe helper then wrote
  `JUSTSEARCH_CODESIGN_MODE` and `JUSTSEARCH_CODESIGN_COMMAND` into the protected `release-signing`
  Environment without echoing or persisting the owner-entered password/TOTP secret. Secret-name and
  timestamp metadata verified both Environment entries. The temporary helper was deleted. The older
  repository-scoped copies remain only as rollback until a real signed build validates the migrated
  values; no signing workflow was dispatched and no signing credit was consumed during migration.
- A narrow, explicitly non-qualifying whole-product Windows Sandbox probe used WinGet `v1.29.290`.
  The clean final result validated the schema-1.12 manifest bundle, downloaded the published v0.2.0
  installer, verified SHA-256
  `cba354165c38c90628082020d40fe00986814a3fa57da49c62dd18acb0f11772`, installed silently,
  observed the expected `JustSearch` version `0.2.0` registration, verified the installed executable's
  Authenticode status as `Valid` with the Elias Justus signer, and observed a successful 20-second
  boot. Earlier probe attempts are retained as harness evidence: Windows Sandbox lacks WinGet until
  the documented `Microsoft.WinGet.Client` bootstrap runs; `--exact` is invalid with `--manifest`;
  and pre-submission `winget list --id` cannot map a local-manifest identity back to the ARP record.
- The upstream-ready bundle was submitted from `eliasjustus/winget-pkgs` as
  `microsoft/winget-pkgs#429017`. Local duplicate checks found no existing package or open PR before
  submission. All ten Microsoft validation stages passed. The owner then decided not to pursue WinGet
  distribution and explicitly requested withdrawal, so pull request `#429017` was closed on
  2026-09-04 before merge. No CLA was accepted and no package was published to WinGet. The local
  deterministic manifest projection remains available if that distribution choice changes later.
- With explicit owner authorization, non-release `build-installer.yml` run `33807983478` used the
  protected `release-signing` Environment with a reviewed provider balance of 240 and a hard ceiling
  of 12. The signing/build job passed: the attempt journal reserved exactly **8/12** provider calls,
  all eight were locally verified, the final installer signature passed `signtool`, the extracted
  installer census accepted 178 MZ files with zero rejected, and no release was published. This proves
  the migrated Environment credential for the installer workflow and bounds this run's provider usage
  to eight signatures. An initial post-run portal read reached a signed-out session, so the temporary
  **232** arithmetic estimate from 240 minus eight was explicitly non-authoritative. A signed-in
  follow-up on 2026-09-04 used the provider's documented `END ENTITY CERTIFICATES` usage surface and
  showed **240 unused signings** on the active Tier 1 Annual plan. The provider ledger also shows the
  September 3 signing activity. SSL.com's published pricing says the first 30 days include unlimited
  signings, so the unchanged paid allowance indicates that this run used the introductory unlimited
  period rather than the annual pool. The authoritative current balance is therefore 240; the 232
  estimate is retired and must not be used for admission.
- The separate packaged-verification job then passed its fresh-install, restart/session-token, and
  upgrade-arrival product legs before its EvidenceBundle Node process aborted during stdout teardown
  with Windows exit `0xC0000409` / libuv `UV_HANDLE_CLOSING`. The bundle path had already been emitted;
  the failure was the capture CLI's direct `process.exit()` racing Node's delayed fetch/Undici and
  stdout-pipe cleanup, not a signing or packaged-product failure. The follow-up replaces forced exit
  with `process.exitCode` so Node drains naturally, adds a loopback-fetch child-process regression that
  enforces the one-line stdout contract and forbids forced exits on both completion paths, and wires
  that test into public CI. The focused regression passes locally. No retry of the paid signing build
  is justified for this harness-only failure; the fix can be validated without another provider call.
  The regression also passes on Windows under the workflow's exact Node `24.14.0`; the post-merge full
  Gradle build/test suites, frontend typecheck, and all 6,269 frontend unit tests pass. After the fix
  was pushed to `codex/905-operational-closeout`, ordinary no-signing CI run `33810680856` passed every
  Windows-native, public-claims, license/notices, build, unit, integration, Rust, secret-scan, and
  reporting lane. In particular, the new evidence-capture regression passed on GitHub's Windows runner
  with Node `24.14.0`; this validates the harness correction without consuming another signing credit.

Residual owner-dependent work is now limited to validation of the mirror signer during its next
natural upstream refresh before deleting repository-scoped credential copies, a fresh
provider-authoritative remaining-signature read before every later paid dispatch, tempdoc 617's exact
N→N+1 updater lanes, and a future model candidate with approved immutable assets, provenance,
license, quality, and publication authority. Re-signing unchanged
mirrors solely to exercise the same Environment would waste roughly 120 metered signatures, so the
repository-scoped rollback copies remain until the next necessary mirror refresh. Scoop remains
deliberately deferred.

## Publication completion plan (2026-09-06)

The implementation and operational follow-through above are complete. The remaining work is the
publication of the packaged-verifier cleanup without reopening superseded distribution choices or
spending another signing credit.

- [x] Incorporate current `origin/main` into `codex/905-operational-closeout`, confirm the merge is
  clean, and prove the resulting content diff is limited to the capture fix, its Windows regression,
  CI wiring, the publication-runner portability repair exposed by the caught-up candidate, and this
  tempdoc's operational record.
- [x] Re-run the focused evidence-capture regression and the repository's current full verification
  suite against the caught-up candidate. Re-run all current publication and public-content gates;
  do not rely on the September 3 green run after `main` has moved. The caught-up candidate exposed a
  pre-existing Windows portability defect in the newly landed local publication runner: commands
  beginning with `./gradlew.bat` were passed to `cmd.exe`, which rejects that path spelling. Normalize
  only that wrapper prefix to `.\\gradlew.bat` at execution time and cover both platform branches
  before re-running the complete preflight. The sequential runner also caused its later directory-mode
  secret scan to inspect ignored build output generated by earlier lanes, producing a false match in a
  generated protobuf source. Make the local subset scan the committed Git candidate and history instead;
  the hosted clean-checkout directory scan remains unchanged.
  The focused regression, full Gradle build and test suite, frontend typecheck, all 6,333 frontend
  unit tests, and the complete publication preflight passed. The preflight included all deterministic
  public-claims, notice/license, build, PMD, JVM unit-shard, 663-commit secret-scan, and jseval lanes;
  jseval completed with 3,094 passed and 12 skipped tests.
- [ ] Dispatch one **unsigned** branch `build-installer.yml` run and require the complete packaged
  verifier job to pass. This closes the capability-realization gap on the exact consumer path without
  using the provider credential or consuming an eSigner signing.
- [ ] Scan the complete public diff for credentials, private identifiers, machine-local paths, stale
  quantitative claims, and unrelated changes. Keep the old WinGet PR closed, keep Scoop deferred,
  and keep repository-scoped signing-secret rollback copies until the next necessary mirror refresh.
- [ ] Open a fresh pull request because closed PR #631 describes an older docs-only head. Use the
  current public squash-body contract, create the separate review record, and pass both strict
  publication checks before enqueueing.
- [ ] After required checks pass, enqueue through the protected merge queue. Verify the landed content
  by diff, confirm merge-group and post-merge `main` CI, then remove only this task's worktree and local
  branch after the publication is proven.

No implementation teardown remains. The only superseded publication artifact is closed PR #631;
it stays closed as history rather than being repurposed. The withdrawn WinGet submission, deferred
Scoop channel, updater evidence lanes in tempdoc 617, and future real model promotion remain outside
this publication.
