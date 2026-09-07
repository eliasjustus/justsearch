# Round 18 charter — 0.3.0 candidate, first 0.3.0 round: upgrade-from-release over the published v0.2.0

Round numbering continues the 0.2.0 series (tempdoc 734 ended at round 17) so the launcher's
convergence-freshness guard and the evidence archive stay monotonic across releases.

Purpose: first Sandbox round for the 0.3.0 candidate. Mode `upgrade-from-release`: install
the exact published v0.2.0 installer first, use it enough to leave real state behind (index,
models, a chat with memory, a watched folder, a skin choice), then install the 0.3.0 candidate
over it. This is the lane tempdoc 823a §1 item 2 said must run before GA and that no round has
run since 0.2.0 shipped. The round also spot-verifies the five backend fixes and the frontend
undo change from PR #695, the naming convergence from #678, and the desktop Add Folder confirm
step from tempdoc 914 §O-2. Verifier is independent of the committer; the Codex harness is
first choice this round (tempdoc 939), Claude Code is the fallback.

Candidate: `JustSearch_0.3.0_x64-setup.exe` — UNSIGNED dispatch of `build-installer.yml` on
`main` after PR #695 merged (`sign=false`, `sandboxTestMode=false`, version from
`gradle.properties`). Read the exact SHA-256, size and CI run from `candidate-provenance.md`.
Because the candidate is unsigned, Authenticode reads NotSigned on every PE and SmartScreen
shows the unsigned-publisher warning: record the prompt text and screenshots, mark
`install-trust-prompts` **unobservable-signed** with that reason, do NOT file it. The signed
build is produced at the tag and is verified by `installer_verify` post-publish.

Previous release: `previous-release\JustSearch_0.2.0_x64-setup.exe` — the published GitHub
Release asset (2026-08-13), signed, CN=Elias Justus. `validation-mode.md` carries its SHA-256;
confirm it matches the staged `SHA256SUMS` from the release before installing.

Search v3 (tempdoc 852) is NOT promoted in this candidate unless `coverage-brief.md` says
otherwise: the chat surface is the shipped `UnifiedChatView`. A visible "v3" chat toggle or a
second chat surface is a finding.

## Sequence (the upgrade lane)

1. Install v0.2.0 from `previous-release\`. Accept the default install directory. Launch, run
   Install AI (full download — this is a no-models round), add the SciFact folder and one small
   folder of your own, let enrichment finish, run three searches (one HYBRID), one chat turn that
   stores a memory, set a non-default skin, set chat encryption and note the recovery key
   (redact it in evidence). Record `docCount`, `embeddingFingerprintCurrent`,
   `embeddingCompatState`, `chunkEmbeddingReady`, the runtime manifest `instanceId`, and the
   registry entry (`HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\*JustSearch*`:
   `DisplayVersion`, `Publisher`, `InstallLocation`) — this is the pre-upgrade control.
2. Close the app fully (all four processes, verify by manifest `pid`), then run the 0.3.0
   installer over it. Capture every installer page.
3. Post-upgrade: launch, repeat every measurement from step 1, then the coverage brief's
   `sandbox`-tier items, then the NEW items below. One genuine four-process cold restart
   between Install-AI-state confirmation and the final assessment (skill rule 10).

## What is NEW in this candidate vs the published 0.2.0 (verify, don't re-file)

1. **Upgrade over a same-publisher-key install (823a §1).** Both binaries carry
   `publisher = "Elias Justus"`, so the manufacturer key is `Software\Elias Justus` on both
   sides. HEALTHY: the installer's "Already Installed" page detects 0.2.0, the mid-upgrade
   uninstaller runs against the EXISTING install directory (the `_?=` handoff is populated —
   watch the uninstaller's working directory / the log line, and confirm the old
   `%LOCALAPPDATA%\JustSearch` is emptied before the new files land), `InstallLocation`
   unchanged, exactly one HKCU uninstall entry after, `Publisher` still "Elias Justus",
   `DisplayVersion` 0.3.0, zero 0.2.0 jars left beside 0.3.0 jars (skill rule 4). BROKEN: a
   second install directory, two registry entries, an empty `_?=` (the round-16-era defect
   823a documents — it should NOT reproduce here because 0.2.0 already wrote the new key),
   or a leftover old jar. Do NOT tick "Delete the application data" on the mid-upgrade
   uninstaller (must-watch `round-10-f11-mid-upgrade-uninstaller-window`).
2. **The five registered upgrade must-watch ids** (`upgrade-index-survives`,
   `upgrade-user-data-survives`, `upgrade-embedding-compat-continuity`,
   `upgrade-nsis-over-install`, `upgrade-chunk-embedding-continuity`): the step-1 control
   values must be unchanged post-upgrade, `chunkEmbeddingReady` must stay true, a HYBRID
   search must execute the dense leg (`searchTrace.stages`), the memory and the skin survive,
   chat encryption unlocks with the same passphrase. A changed fingerprint is a finding only if
   unexplained — 0.3.0 ships no embedding-model change, so expect FINGERPRINT_MATCH.
3. **Undo is trust-gated (875 fix, PR #695).** Trigger: in chat, ask the agent to create or
   rename a file inside a watched folder, approve it, then use the toast/inbox "Undo" for that
   operation. HEALTHY: the undo raises the same authorization ceremony as the forward
   operation (typed-confirm dialog), and after consent the file is restored; the response text
   with non-ASCII filenames is intact. BROKEN: undo runs with no prompt, or fails with an
   unexplained error where 0.2.0 showed a prompt-less undo. Raw check: `curl.exe -i` on any
   JSON endpoint must show `Content-Type: application/json; charset=utf-8`.
4. **Worker-restart tool failures are honest (877 fix).** Trigger: start an agent run that
   uses a search tool, and while it runs kill the Worker `java.exe` (filter by Path). HEALTHY:
   the tool card reads "the knowledge worker is not reachable; the index is restarting — retry
   shortly" (or the model relays it) and the run does not surface a raw internal error; the SSE
   `tool_exec_completed` event carries `errorCode` and `retryable`. Then the Worker recovers
   on its own (`/api/health` back to READY without a shell restart).
5. **Withheld tools are named (868 fix).** Trigger: with the Worker down (same window as item
   4) or before enrichment is ready, send a chat turn with tools selected. HEALTHY: the answer
   says which tools are withheld and why ("… withheld while it is unavailable …"), not a bare
   "No tools available".
6. **Transcript written during a Worker restart is indexed later (909 fix).** Trigger: complete
   one chat turn while the Worker is down (item 4 window). HEALTHY: after the Worker is back, a
   search scoped to the `agent-history` collection finds that turn within a couple of minutes
   and the `<sessionId>.md.pending` marker under the agent-history data dir is gone. BROKEN:
   the marker stays, or the transcript is never searchable.
7. **Failed-files drawer shows the real scan id (911 fix).** Trigger: put a zero-byte `.pdf`
   or a file locked open by another process in a watched folder and rescan. HEALTHY: the
   drawer lists it with a scan id that matches `/api/indexing/...` failed-jobs JSON, and retry
   chips are reachable (914 D1/D2). BROKEN: a placeholder id, or a drawer that overflows.
8. **Desktop Add Folder needs one confirm click (914 §O-2).** Trigger: Library → Add Folder
   in the Tauri shell. HEALTHY: native picker → form with the chosen path and a collection
   field → one click on Add → root appears with that collection label. BROKEN: the pick is
   dropped, or the header button submits instead of reopening the picker.
9. **Failure facts survive into UI and MCP (#688)** and **"Detailed mode" / "Search" naming
   (#678)**: spot-check one failed search or ingest error renders its reason, and the labels
   match across the palette, the surface headers and the MCP tool descriptions.
10. **Install AI first-hour behaviour is unchanged from 0.2.0 round 17**: transport retries,
    per-package truthfulness, Repair convergence — reachability spot-check only, expand on any
    failure. If Install AI does not complete, the round's shape is the round's call and the
    failure is the finding to characterise; run `probe-download.ps1` immediately.

## Round-plan obligations

- Budget `expired-pending-approval-ceremony` right after the first TYPED_CONFIRM approval.
- `webview-performs-one-search` on BOTH the 0.2.0 and the 0.3.0 install (a UI search that
  returns results; the trace must show the mutating call 2xx with the session token).
- Golden parity: the per-candidate baseline `golden-parity.json` IS staged (generated
  2026-09-06 from the dev stack on the candidate commit, 5189-doc SciFact corpus, warm cuda
  EP, all six retrieval legs); capture the golden queries post-upgrade after enrichment is drained
  and the EP is warm. If no baseline is staged, the capture still runs and the comparison is
  pre-declared unmeasurable for this round — say so in the retrospective.
- Codex only: confirm the charter's last section ("Independence invariant") is visible before
  anything else, and record in `session-analysis.md` whether the Computer Use plugin saved
  screenshots to the coverage filenames directly or via `gui\snap.ps1`, whether the
  `JustSearch.exe` allowlist suppressed the prompt, and any rate-limit stalls (tempdoc 939
  open items 1-4).
- All six process artifacts; `findings.md` standalone; tools built in-round saved under the
  mapped share.

## Blocker classification (round-scheduling gate, runbook step 3)

Every item above is **needs-round**: each requires a clean machine, a real over-install, the
real shell, or a real Worker kill. There are no open needs-dig blockers on this candidate.

## Carried over / do NOT re-file

- **Known Issues in the 0.3.0 CHANGELOG** (root `CHANGELOG.md`, not staged; listed here): in-app update from 0.2.0
  not exercised (617 §9 — the running 0.2.0 will not offer an update because 0.3.0 is not
  published; the installer-over-install IS this round's lane); one overall download bar
  (840); an empty quick answer with reasoning on (845/848); out-of-root documents not
  removable (875); the 10-iteration agent ceiling (868). Record as observed-known, not as
  findings, unless the behaviour is worse than described.
- Locked-chat `POST /api/chat/dispatch` returning 200 and discarding the question (0.2.0
  round 14 F4, 817 §1): open owner question.
- Health card 2–4 min staleness in the safe direction (0.2.0 round-16 F5): re-file only if
  it worsens or flips optimistic.
- Restart-era bare "AI installed." beside `installedFully:false`: known copy residual.
- SmartScreen *reputation* verdicts and elevation prompts: structurally unobservable in this
  image (`EnableLUA=0`, folder-mount has no MOTW) — record unobservable-with-reason.
- Golden parity q06/q08 sub-floor overlap is descriptive under the blocking
  golden-#1-in-top-3 assertion.
