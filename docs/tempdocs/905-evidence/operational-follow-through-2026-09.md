<!-- Sidecar of docs/tempdocs/905-release-unblock.md. Bulk operational evidence moved here after CI's tempdoc-size gate rejected growth beyond 800 lines. -->

# Tempdoc 905 operational follow-through evidence (September 2026)

- PR `justsearch-app/justsearch#629` merged through the protected merge queue as
  `7aa4a916fbee342ac4678bcf5603570f5fc1a0a0`. Merge-group CI run `33797308631` and post-merge
  `main` CI run `33797753403` passed. The published merge tree exactly matched the reviewed PR head.
- GitHub Environment `release-signing` now requires owner review, disables administrator bypass,
  and restricts normal deployment refs to branch `main` or tags matching `v*`.
- Repository variable `JUSTSEARCH_RELEASE_DESCRIPTOR_URL` now points to
  `https://github.com/justsearch-app/justsearch/releases/latest/download/release.v1.json`.
- Environment configuration was completed through a no-echo, non-persisting credential-entry path.
  Provider account identifiers, authentication factors, subscription details, balance, and recovery
  history are intentionally omitted from the public record.
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
- With explicit owner authorization, non-release `build-installer.yml` run `33807983478` exercised
  the protected Environment under the workflow's fail-closed signing ceiling. The signing/build job,
  local signature verification, and extracted-installer executable census passed, and no release was
  published. This proves the installer workflow can use the Environment-backed signer without putting
  provider account state into the repository.
- The separate packaged-verification job then passed its fresh-install, restart/session-token, and
  upgrade-arrival product legs before its EvidenceBundle Node process aborted during stdout teardown
  with Windows exit `0xC0000409` / libuv `UV_HANDLE_CLOSING`. The bundle path had already been emitted;
  the failure was the capture CLI's direct `process.exit()` racing Node's delayed fetch/Undici and
  stdout-pipe cleanup, not a signing or packaged-product failure. The follow-up replaces forced exit
  with `process.exitCode` so Node drains naturally, adds a loopback-fetch child-process regression that
  enforces the one-line stdout contract and forbids forced exits on both completion paths, and wires
  that test into public CI. No retry of the paid signing build was justified for this harness-only
  failure. The regression passed on Windows under the workflow's exact Node `24.14.0`; the post-merge
  Gradle build/test suites, frontend typecheck, and all 6,269 frontend unit tests passed. Ordinary
  no-signing CI run `33810680856` then passed every lane, including the new Windows regression.

## Publication preflight evidence (2026-09-06)

After incorporating current `origin/main`, the full Gradle build and test suite, frontend typecheck,
all 6,333 frontend unit tests, the focused evidence-capture regression, and the complete publication
preflight passed. The preflight covered deterministic public claims, notice/license, build, PMD, JVM
unit shards, a 663-commit secret scan, and jseval with 3,094 passed and 12 skipped.

The caught-up run exposed two publication-runner defects. On Windows, `cmd.exe` rejected the
`./gradlew.bat` spelling, so execution now normalizes only that wrapper prefix to `.\\gradlew.bat`.
The sequential preflight's directory-mode secret scan later inspected ignored generated protobuf
output from earlier lanes and produced a false match. Execution now rejects any staged, unstaged, or
non-ignored untracked candidate before scanning committed Git content and history; the hosted
clean-checkout directory scan remains unchanged. Both repairs have focused tests.

## Publication outcome (2026-09-06)

Fresh PR `justsearch-app/justsearch#692` reviewed head
`acd896684d5a89b48dff370a85fd08db8f65b44c`. Branch CI run `34029407235` passed all required
contexts after earlier run `34029125903` exposed the 800-line tempdoc cap and the bulk chronology
moved into this evidence sidecar. The managed review record and squash-message preview both passed.

The protected merge queue tested synthetic SHA
`e1ed33e8a932e49d270461cb5515534a9523c7ec` in merge-group CI run `34029800355`; every lane passed.
PR `#692` then squash-merged as that same SHA. A direct tree diff against reviewed head `acd896684`
was empty, and post-merge `main` CI run `34030213192` passed on the exact landed commit. GitHub removed
the remote topic branch automatically. No release, signing-provider call, WinGet/Scoop mutation, or
model publication occurred during this publication.
