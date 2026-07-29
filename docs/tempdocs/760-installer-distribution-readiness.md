---
title: "Installer distribution readiness: silent install, published checksums, winget manifest, SmartScreen documentation — everything cert-independent, so code signing becomes a drop-in when the cert decision lands"
type: tempdocs
status: "Phase 2 IMPLEMENTED (2026-07-22) — signing is now genuinely drop-in: -Sign decoupled from release semantics, three credential modes (pfx/store/command — vendor choice becomes zero-code-change), CI secret plumbing (secrets-absent builds byte-identical), cert-free rehearsal path proven 6/6 twice on this machine; sign-once mirror producer landed (build-side consumption deliberately design-only — supply-chain pin overrides need explicit opt-in design); verify-your-download.md + winget skeleton/runbook landed; sandbox silent-install harness prepared (GUI-gated execution). Remaining owner-gated: cert/vendor decision (now mode-config only), GA cut, winget publisher identity, running the sandbox harness. See §Takeover + Phase 2 design and §Outcome."
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
| Credential mode for the cert we'd actually buy | Chain is **PFX-only**: `sign-windows.ps1:31-35` reads `JUSTSEARCH_CODESIGN_PFX_PATH` / `_PFX_B64` / `_PFX_PASSWORD` / `_TIMESTAMP_URL`, gates on a resolvable PFX (`:74-93`), and signs via `signtool sign /f <pfx> /p <password>` (`:117-123`) | **Cloud-HSM signing has no PFX to point at.** CA/B Forum rules since 2023-06 require code-signing keys on FIPS 140-2 L2 hardware, so the leading candidate (cloud-HSM/eSigner-style, signed via a provider's key adapter) cannot satisfy the `/f`+`/p` contract at all. USB-token certs — the other compliant option — are not automatable on GitHub-hosted runners (no USB passthrough), so they are not an escape hatch. `sign-windows.ps1` needs a **second credential mode** alongside PFX. | M |

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
   PFX; unsigned path unchanged when secrets absent. (M, delegable)
   **Correction (see the credential-mode row):** this closes the *CI-never-engages-signing* gap,
   but it does not by itself make a cert drop-in. `sign-windows.ps1` is built around a PFX on disk
   (`:31-35`, `:74-93`, `:117-123`); a cloud-HSM cert has no PFX, so activating one also requires a
   second credential mode in that script. Scope "purchase a cert → signing works" accordingly — it
   is not accurate as stated in the charter.
4. `docs/how-to/verify-your-download.md`: certutil sha256 check, why SmartScreen warns +
   More-info→Run-anyway, build-from-source trust path, end-user SAC note (mirror of
   `package-installer-win.ps1:210-220` dev-side self-diagnosis); link from README. (S, delegable)
5. **(Owner)** Cut GA v0.2.x non-prerelease — precondition for winget pinning and for 759's
   registry publish; the existing pipeline already stages installer + `SHA256SUMS` + `.mcpb`.

Out-of-scope observation (logged to inbox): installer size stated inconsistently — 853 MB
(`README.md:57`) vs ~748 MB (`.claude/skills/installer/SKILL.md:16`) vs 741 MB
(`build-installer.yml:202`).

## Takeover + Phase 2 design (2026-07-22, Fable — continuation of the 772 lane, owner-directed "proceed on your own")

**Context imported from tempdoc 772 §K** (same orchestrator, fresh worktree `760-signing-pipeline`
off `origin/main` at the 772 merge): the installer is now 259.8 MB; the four-problem decomposition
of "signing" (trust / per-release cost / pipeline mechanics / vendor eligibility); and the
**sign-once-per-upstream-bump** input — the 93 unsigned third-party PEs are pin-stable and Tauri's
`should_sign` verifiably skips already-signed PEs, so signing vendored payloads once per pin bump
(signed mirrors on `justsearch-releases`) collapses per-release signings to ~8 with no payload
movement. 772 also verified all four pack-published ORT DLLs are Microsoft-signed.

**Gap-table correction (primary re-read of `package-installer-win.ps1:224-290`):** `-Release` and
`-SkipVerify` are *independent* — CI already passes `-SkipVerify` alone (`build-installer.yml:173`),
so the blocker is NOT the heavy-verify coupling this table's "Signing drop-in" row implied. The real
couplings preventing CI signing are: (a) `-Release` forces `-RequireReleaseSemver`
(`:227`) — a non-tag dry-run build cannot use it; (b) zero `JUSTSEARCH_CODESIGN_*` secret plumbing
exists in the workflow; (c) `sign-windows.ps1` is PFX-only (`:31-35,117-123`), while modern
code-signing keys are HSM-resident by CA/B-Forum mandate, and its post-sign
`signtool verify /pa` (`:129`) hard-fails on any untrusted (e.g. self-signed dry-run) chain, so the
pipeline cannot even be *rehearsed* without a production cert.

### Design (Phase 2 item 3 + §K's sign-once input)

1. **`-Sign` switch on `package-installer-win.ps1`, decoupled from release semantics.** `-Sign`
   ⇒ signing overlay config + `JUSTSEARCH_REQUIRE_SIGNING=true` + `signature_verify` phase (cheap),
   with NO `-RequireReleaseSemver`. `-Release` implies `-Sign` (behavior unchanged). Unsigned path
   untouched when `-Sign` absent.
2. **Credential modes in `sign-windows.ps1`** via `JUSTSEARCH_CODESIGN_MODE`:
   - `pfx` (default — exactly today's behavior, back-compatible);
   - `store` — cert by `JUSTSEARCH_CODESIGN_THUMBPRINT` from the Windows cert store (`signtool
     /sha1`), which is how USB-token/HSM CSP-backed nonexportable keys present locally;
   - `command` — `JUSTSEARCH_CODESIGN_COMMAND` template with a `{file}` placeholder, making any
     vendor CLI (Azure Trusted Signing dlib wrapper, eSigner, etc.) pluggable with zero further
     repo changes. Fail-closed per mode under `JUSTSEARCH_REQUIRE_SIGNING`.
3. **Rehearsable without a cert**: `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED=1` relaxes the post-sign
   check from `signtool verify /pa` to a hash-valid Authenticode presence check
   (`Get-AuthenticodeSignature` status `Valid|UnknownError/NotTrusted-but-signed`), enabling a
   self-signed end-to-end dry run locally and in CI — the missing rehearsal path in (c).
4. **CI plumbing** in `build-installer.yml`: pass `-Sign` only when the signing secrets are
   configured (workflow-level conditional), env-map `JUSTSEARCH_CODESIGN_*` from repo secrets;
   secrets-absent dispatches build exactly as today.
5. **Sign-once mirror producer** `scripts/release/sign-vendored-payload.ps1`: archive in →
   extract → sign every unsigned PE via the same credential modes → deterministic re-zip →
   SHA256 out. Run once per llama.cpp/Tesseract pin bump; output uploads to `justsearch-releases`
   as the signed mirror. Consumption wiring (pointing the build's prebuilt-download URLs at
   mirrors) is designed after reading the staging sources — it must not disturb 618 §3's
   no-mirror-task constraint.

**Mirror consumption wiring — UPDATE (2026-07-22, later same day): IMPLEMENTED under the explicit
opt-in trigger** (a metered signing plan under consideration is exactly the moment this design
waited for). Paired `llamaPrebuiltUrlOverride`/`llamaPrebuiltSha256Override` and
`tesseractSourceUrlOverride`/`tesseractSourceSha256Override` gradle properties, all-or-nothing
enforced at configuration time (providing exactly one fails with a message naming the
supply-chain-pin defeat); defaults byte-identical (pins remain the floor); override URL+hash
declared as task `inputs.property` so an override busts the up-to-date check (without this an
incremental build silently ignored the override — caught during implementation). Tesseract scope
limit: archive-level pin only; the manifest's per-file `files[]` SHAs are NOT skipped — with an
active override a per-file mismatch fails with an explicit manifest-regeneration instruction
(no in-repo generator exists; it is hand-authored). Verified with three live gradle runs:
default path (exit 0, pinned artifact), lone-override (exit 1, pairing message), both-overrides
against a loopback-served mirror copy (exit 0, mirror fetch proven via httpd access log after
deleting the cache). `gradle/verification-metadata.xml` confirmed non-interacting (these tasks
use raw HttpClient outside Gradle dependency resolution). Original design rationale kept below.

**Mirror consumption wiring — investigated, deliberately DESIGN-ONLY (2026-07-22).** The sign-once
producer (`scripts/release/sign-vendored-payload.ps1`, landed, rehearsed end-to-end fail-closed and
`-AllowUnsigned` modes, layout-preservation and deterministic-output proven) intentionally ships
WITHOUT build-side consumption wiring, because that wiring is not a clean small change: the llama
CPU prebuilt download fail-closes against a hardcoded `llamaPrebuiltSha256`
(`modules/ui/build.gradle.kts:470,524-530`), so a mirror-URL swap alone breaks the build, and a
paired URL+SHA override weakens the exact supply-chain pin it would bypass — an explicit-opt-in
design decision, not a drive-by property. Tesseract is harder still (source = a self-extracting
installer whose `sourceSha256` + 74 per-file SHAs are pinned in
`packaging/runtime/tesseract-windows.v1.json`; signed binaries can't ride the original installer
format). Wiring design for the implementer: paired `llamaPrebuiltUrlOverride`/`llamaPrebuiltSha256Override`
gradle properties mirroring the `includeCuda` pattern (`:370`), applied at `:359-362`; CUDA/cudart
additionally need pins introduced (they have NONE today — observation logged); Tesseract needs
`sourceUrl`/`sourceSha256` override handling plus post-signing regeneration of the manifest's
`files[]` SHAs. Constraint honored: no gradle task may mirror into `native-bin`
(`build.gradle.kts:882-887`, tempdoc 618 §3 — it would clobber the user-installed cuda12 variant).

Items 2 (winget manifest skeleton + runbook, GA-gated values marked) and 4
(`docs/how-to/verify-your-download.md` consolidation + README link) proceed as chartered.
Item 1 (sandbox silent-install verification) is prepared as an automated `.wsb` LogonCommand
harness against the existing `tmp/offline-installer-sandbox` share infra; execution requires a
GUI session, so it ships as a ready-to-run harness + runbook step if it cannot be driven headless
from this session.

### Silent-install verification (sandbox harness) — prepared, not executed (2026-07-22)

Item 1's harness is authored and statically validated (PowerShell AST parse, zero errors; `.wsb`
generation dry-run confirmed against Microsoft's published Sandbox config schema) but **not run**
— Windows Sandbox requires an interactive GUI session, unavailable to the authoring agent.

Two files, `scripts/release/sandbox-silent-install-test.ps1` (host launcher) and
`scripts/release/sandbox-guest-silent-test.ps1` (guest LogonCommand), together verify the two
facts the Findings table flagged as never empirically tested: `/S` silent install lands the app
at the real default per-user path (`$env:LOCALAPPDATA\JustSearch`, confirmed against
tauri-bundler's `installer.nsi` currentUser branch, cross-checked at
`scripts/ci/verify-installer-nsis-win.ps1:677`'s identical hardcoded registry key literal), and
`/S` silent uninstall removes it cleanly (install dir, `HKCU:\...\Uninstall\JustSearch` key,
Start Menu/Desktop shortcuts) with no leaked processes.

**Usage** (two commands, from a GUI-capable Windows machine with Windows Sandbox enabled):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\package-installer-win.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\sandbox-silent-install-test.ps1
```

The first stages the newest installer into `tmp/offline-installer-sandbox/share` as the stable
`JustSearch-LATEST-setup.exe` alias (already-existing behavior,
`package-installer-win.ps1:316-373`); the second stages the guest script alongside it, generates
`tmp/offline-installer-sandbox/silent-install-test.wsb`, and launches Windows Sandbox. Results
land at `tmp/offline-installer-sandbox/share/silent-test-result-<timestamp>.json` (schema
`justsearch.sandbox-silent-install-test.v1`): a per-step pass/fail/detail log plus a residue list,
ending in a literal `PASS`/`FAIL` line. Pass `-GenerateOnly` to prepare the `.wsb` without
launching Sandbox (e.g. from an agent session); `-InstallerPath` overrides the staged alias.

**Safety finding, load-bearing for anyone running this harness — do not skip:** the guest
script's registry/filesystem lookups are host-global, not scoped to the installer it staged. An
early validation pass of the guest script (run directly on a dev machine, outside Sandbox, with a
non-functional placeholder installer file — intended only to check the PowerShell/JSON plumbing)
located a real pre-existing `F:\JustSearch-test` NSIS test install via the machine's actual
`HKCU:\...\Uninstall\JustSearch` registry key and **silently ran its real uninstaller with `/S`**,
removing its registry entry, Start Menu/Desktop shortcuts, and `uninstall.exe` (a
`resources\headless` remnant was left behind — the removal was not 100% clean either). No attempt
was made to restore or otherwise act on this after the fact; it is reported here as-is. This
script must only ever run inside a disposable Windows Sandbox instance — never directly on a host
with a real JustSearch install. The guest script's own header now carries this warning verbatim.

**Known limitations only a real GUI Sandbox run can close:**
- Smart App Control may still block the unsigned installer despite the harness's best-effort
  disable attempt (mirrors the existing, documented-unreliable mitigation in
  `scripts/sandbox/sandbox-launch.py:889-903`) — an immediate install failure is plausibly SAC,
  not an installer defect, until signing lands.
- No interactive verification of the NSIS finish page, first-run UX, or tray icon.
- The app is never launched by this test, so runtime residue (AI Home contents, worker/
  llama-server child processes) is out of scope by design.

## §Outcome — Phase 2 implemented (2026-07-22, same day)

All four delegable Phase-2 items landed on this branch, each independently verified before commit:

1. **Signing pipeline (item 3)** — commit `23bfd7c5`. `-Sign` decoupled from `-RequireReleaseSemver`
   (the actual blocker, per the gap-table correction above); credential modes `pfx`/`store`/`command`
   in `sign-windows.ps1` (the cert-vendor decision is now a `JUSTSEARCH_CODESIGN_MODE` config choice,
   zero code change — directly relevant to the eligibility constraints on vendor selection);
   `JUSTSEARCH_CODESIGN_*` secret plumbing in `build-installer.yml` with the env-hoist gating pattern
   (secrets-absent dispatches byte-identical to today; `check-workflow-triggers: OK`); and
   `test-sign-windows.ps1`, a cert-free end-to-end rehearsal (self-signed cert, pristine
   csc-compiled PE target after a catalog-signing false-green was caught, 6-case matrix incl.
   fail-closed and a real DigiCert timestamp countersignature) — **run twice, worker and
   orchestrator independently: 6/6 PASS both times.** What this buys: when a cert exists, engaging
   real signing = set repo secrets, nothing else.
2. **Sign-once mirror producer (§K import, item 5)** — commit `2a373cf6`; consumption wiring
   design-only per the section above.
3. **verify-your-download.md + README link (item 4)** and **winget skeleton + runbook (item 2)** —
   commit `f2e7f20f`; `check-root-readme: OK`; `winget validate` schema-clean except intentional
   `TODO-GA` placeholders.
4. **Sandbox silent-install harness (item 1)** — prepared (launcher + guest script + generated
   `.wsb`), execution GUI-gated; see the harness subsection.

**Remaining, all owner-gated:** the cert/vendor decision (now pure configuration); GA v0.2.x cut
(precondition for winget pinning); winget `Publisher` identity fields; double-clicking the sandbox
harness once and attaching its result JSON; optionally seeding a self-signed
`JUSTSEARCH_CODESIGN_PFX_B64` repo secret to rehearse the full CI signing path end-to-end in a
dispatch before a real cert exists (the local rehearsal covers the signing script itself; a CI
dispatch with test secrets would additionally prove the workflow plumbing under Actions).

## §CI signing rehearsal campaign (2026-07-22, later same day) — 6 dispatches, 5 root causes, 0 signings spent, GREEN

Owner asked the load-bearing question directly: *"how confident are you that we wouldn't spend
multiple turns and signing usage on actually getting signing to work correctly?"* Answer then:
4/10 — so the full CI rehearsal was executed BEFORE any purchase, with a 30-day self-signed cert
in the repo secrets and `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED=1` as a repo variable. Six
`build-installer.yml` dispatches later, the pipeline is green end-to-end (run `29914075529`).
Every failure was root-caused by local reproduction (never guess-pushed), fixed, regression-run
through the 6-case local rehearsal, and would otherwise have burned metered quota and opaque
debugging hours on a paid plan:

| # | Run | Root cause | Fix | Class |
|---|---|---|---|---|
| 1 | `29909495558` | **Array-splat flag binding**: `$extra += '-Sign'` binds positionally — `-Sign` landed in `-SetupExePath`; build ran `--no-sign` then crashed resolving a file named `-Sign`. Bonus: the pre-existing `-VerifyReleaseVersion` has the same latent bug, never exercised (no tag dispatch since it was added) — it would have broken the next real release cut. | Hashtable splatting for both flags; binding probe-verified locally against a matching param signature. | my code + latent pre-existing |
| 2 | `29910543640` | **Trailing-newline secrets**: values piped into `gh secret set` from files carry CRLF; signtool fails "The specified PFX password is not correct" — with the child's output swallowed by the bundler. | `Strip-TrailingNewlines` on password/URL/thumbprint/command/pfx-path (never legitimate there); secrets re-set clean via `--body`; repro'd locally with the exact dirty values (fails before, signs after). | operator-error class, now structural |
| 3 | `29911439832` | **Terminating stderr**: PS 5.1 + `$ErrorActionPreference=Stop` + the bundler's PIPED stderr ⇒ a native process's first stderr line is a terminating `NativeCommandError` BEFORE the exit-code check — script dies mid-call, real error lost. Actual trigger: transient timestamp-server failure on the first file. | `Invoke-Native` wrapper at all 4 native call sites (EAP Continue, output captured + tee-logged, args never logged — passwords travel in them) + `Invoke-NativeWithRetry` (3 attempts/3s) on the sign step. Repro: unreachable `/tr` under piped stderr — silent death before, 3 logged attempts + clean FAIL with verbatim signtool error after. | environment (PS 5.1 semantics) |
| 4 | `29912444815` | **Silent post-sign death**: signtool exit=0, then nothing — died inside `Get-AuthenticodeSignature`. | Diagnosability layer: entry breadcrumbs, defensive 3-attempt signature read, and a script-level `trap` that tees ANY escaping terminating error with position info. (This run's fix is what made #5 diagnosable in one shot.) | diagnosability |
| 5 | `29913294778` | **PowerShell edition poisoning** (trap caught it verbatim): CI steps run under pwsh 7, whose exported `PSModulePath` points at Core-edition modules; the spawned 5.1 signCommand child can't load its OWN `Microsoft.PowerShell.Security` ⇒ `Get-AuthenticodeSignature` unloadable. Signing worked; reading the signature back was impossible. Unreproducible locally (5.1 parents). | Both 5.1 scripts reset `PSModulePath` to Windows PowerShell defaults under Desktop edition; `verify-windows-signature.ps1` fixed preemptively (same spawn shape + cmdlet — it was the queued-up next failure). Repro: locally poisoned `PSModulePath` fails identically, clean after. | environment (pwsh→5.1 spawn) |
| 6 | `29914075529` | — | **GREEN**: full build, all bundled PEs signed + timestamped via the real signCommand loop, rehearsal-mode verify passed. | — |

**Layered diagnosability shipped as a by-product** (each layer added after the previous failure
mode was invisible without it): tee log (`%TEMP%\justsearch-sign-windows.log`, printed by an
`if: failure()` workflow step) → captured native output per invocation → terminating-error trap
with position. A future REAL-cert signing failure inherits all three.

**Confidence answer, post-campaign: ~9/10** that the first paid signing run works, with a
predictable cost: ~93 signings once (vendored mirrors via `sign-vendored-payload.ps1` +
consumption overrides, both landed) + ~8 per release. The five found bugs were all
environment-shaped — exactly the class that survives local testing and burns metered quota.
Residual risk is vendor-CLI-specific quirks (`command` mode with the actual eSigner/vendor tool —
retire via the vendor's sandbox before purchase). Rehearsal secrets/variable are removed after the
artifact-signature census; re-rehearsing later just means re-running `test-sign-windows.ps1`-style
setup (self-signed certs are generated on demand).

**Artifact-signature census of the green run (run `29914075529`, artifact downloaded and every PE
checked): PERFECT.** 177 embedded PEs → **99 rehearsal-signed, all 99 with DigiCert timestamp
countersignatures** (74 tesseract + 19 llama-server + 6 own/NSIS — reconciles exactly with
tempdoc 772's 93-third-party estimate); **78 vendor-signed originals untouched** (48 Microsoft +
26 Eclipse Temurin + 4 vc_redist, all still `Valid` with their own timestamps — empirical proof
of the `should_sign` skip, upgrading it from source-verified to behavior-verified); **zero
NotSigned, zero anomalous**. Outer setup exe: rehearsal-signed + timestamped. Size: 259.9 MB —
signing 100 PEs added negligible bytes to the 259.8 MB unsigned baseline. Per-release signing
count confirmed empirically: 99 + outer ≈ 100 today → ~7-8 once the vendored mirrors (93) are
adopted. **Rehearsal secrets and the ALLOW_UNTRUSTED variable have been deleted from the repo**
(verified empty) — dispatches are back to unsigned builds; production onboarding = set real
`JUSTSEARCH_CODESIGN_*` secrets in the chosen mode, nothing else.
