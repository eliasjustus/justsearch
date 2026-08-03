# Sandbox GUI Capture/Input Harness

Native PowerShell/.NET GUI capture and input for Windows Sandbox validation
rounds. Proven end-to-end (tempdoc 727-followup smoke round): drives the
**real Tauri WebView2 shell**, needs no computer-use tool, no extension, no
pairing, no account, no network. Coverage credits screenshots as image files
on disk — nothing requires the PNG come from a tool call.

Staged into the sandbox at `<mapped folder>\gui\` by `sandbox-launch.py`
alongside `collect-evidence.ps1`.

## What each script does

| Script | Purpose |
|---|---|
| `JustSearchGui.psm1` | Shared module — P/Invoke boilerplate, window connect/click/capture, and the assert-then-act primitive. The other scripts are thin wrappers over this; import it directly if you're scripting something ad hoc (`Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force`). |
| `snap.ps1` | Full-desktop capture (`CopyFromScreen`). Use for the Step-0 capability probe and whole-screen evidence. |
| `win-capture.ps1` | Locate + focus + capture ONE window by process name (`GetWindowRect` + `SetForegroundWindow`). Optional `-Keys` sends keystrokes before capturing. |
| `click.ps1` | Click at window-relative coordinates in a target window, then capture. Fails closed (exits 1, no click sent) if the window did not actually take foreground focus. |
| `crop.ps1` | Crop + magnify a region of an existing PNG (for illegible small text). |
| `gui-approve.ps1` | **EXAMPLE**, not a generic tool — see below. |

The observe -> locate -> act -> observe loop: capture a PNG with `snap.ps1`
or `win-capture.ps1`, `Read` it to see the UI, read the target's pixel
coordinates off the image, act with `click.ps1` / `SendKeys`, then
re-capture to confirm the action registered. This is functionally what a
computer-use tool does, assembled from parts native to Windows.

**A capture that fails exits non-zero.** Every capture entry point
(`Save-DesktopShot` / `Save-AppShot` / `Save-AppShotRegion`) creates the
output's parent directory if missing, and then THROWS if the PNG is not on
disk afterwards -- so `snap.ps1` / `win-capture.ps1` / `click.ps1` exit
non-zero instead of printing a `saved:` line for a file that was never
written (sandbox round 10, finding H1: a whole round's screenshot evidence
was reported as captured and did not exist). Judge a capture by the process
exit code and `Test-Path`, never by the `saved:` line -- it is `Write-Host`
output, invisible to a caller that redirects stdout. Regression test:
`scripts/sandbox/test_gui_capture_failure.py`.

**Crop before you read.** A full-window screenshot easily runs to hundreds
of KB and burns a large chunk of an agent's context just to check one small
area (a button label, a status line). If you already know the region you
need — you just clicked a specific control, or you're re-checking one piece
of text — run `crop.ps1` on the fresh screenshot first and `Read` the
cropped, magnified PNG instead of the full-resolution one. A whole round
reading 74 full-resolution screenshots when `crop.ps1` was staged the entire
time was the dominant token cost of that round (tempdoc 727-followup);
don't repeat it.

**Assert, don't guess, that a click landed.** `Assert-AppSurface` (in
`JustSearchGui.psm1`) checks `GET /api/action-ledger` for the most recent
navigation and compares its target surface against what you expected. A
round can otherwise burn a step on a silent no-op click (nothing renders
differently, and a screenshot alone can't tell you whether that's "no-op"
or "feature broken") — six of those in one round were the second-largest
false-finding generator after the missing-crop issue above.

## `gui-approve.ps1` is a worked EXAMPLE, not a library function

It combines focus -> click a field -> type a phrase -> click Approve -> capture
into one script, because splitting those steps across multiple invocations is
one of the two gotchas below. Its default `-ApproveX`/`-ApproveY`/`-FieldX`/
`-FieldY` values are **pixel coordinates captured from one specific dialog at
one specific window size/theme/DPI** in the round that produced it — they will
not match a different resolution, a different dialog, or even the same dialog
after a theme change. Treat it as a template: copy the pattern, re-derive the
coordinates fresh each round from a screenshot of *that* round's dialog.

## Three mechanical gotchas (cost 3+ attempts each to discover — don't re-spend the round on them)

1. **Focus is not sticky.** `SendKeys` goes to whatever element currently has
   focus. Click the target field first, *then* type — never assume a field
   is still focused from an earlier step.
2. **Never split type+click across two separate script invocations.** Each
   PowerShell invocation that calls `SetForegroundWindow` re-focuses the
   window, which can reset the dialog and clear anything already typed. Do
   focus -> type -> click -> capture in **one script, one invocation**
   (`gui-approve.ps1` is the pattern).
3. **`SendKeys` cannot type JSON.** Its metacharacters (`{}`, `+`, `^`, `%`,
   `~`, `()`) are control syntax, not literal text — sending a JSON payload
   through `Send-AppKeys`/`SendKeys::SendWait` mangles or truncates it. Two
   sandbox surfaces need JSON typed into them (the presentation-editor
   surface and the skin-import box), so this is a real blocker, not an edge
   case. Workaround: paste instead of type — stage the text on the clipboard
   and send `^v`. `Send-AppText` in `JustSearchGui.psm1` does exactly this
   (`Set-Clipboard -Value $Text; [System.Windows.Forms.SendKeys]::SendWait("^v")`);
   use it instead of `Send-AppKeys`/raw `SendKeys` for any payload that isn't
   plain, metacharacter-free text.

## Pixel-coordinate caveat

There is no element-based targeting into the Tauri WebView2 content (see
tested-negative list below) — every click is a raw pixel coordinate.
Mitigations:

- **Fix the window size** at the start of a round for determinism across
  screenshots.
- **Re-locate from a fresh screenshot every time**, not from memory or a
  prior round's coordinates. A coordinate that worked yesterday may not work
  today (theme, DPI, window position, dialog content length can all shift
  it).
- **Re-capture after every action** to confirm it landed, before trusting the
  next step's coordinates.

## Tested negative (do not re-investigate these)

| Approach | Result |
|---|---|
| UIA element targeting inside WebView2 (`AutomationElement.FromHandle`) | Web content is **opaque** to UI Automation — the root is `class='Tauri Window'` with only ~17 descendant elements (drag/resize region, a couple of named panes), no DOM tree. Element-based targeting is not available this way. |
| `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` (e.g. `--force-renderer-accessibility`, `--remote-debugging-port`) | **Ignored.** WebView2 only honours this env var when the host app does not set `AdditionalBrowserArguments` itself, and Tauri does set it. Enabling CDP/accessibility this way needs a build/config change, not an in-sandbox trick — and per the `/start` skill's rule against patching product code in-sandbox, that change belongs in the repo, not the round. |
| UAC secure desktop | **Still uncapturable by design** — no script here changes that. The no-admin claim itself is now verified host-side (`scripts/ci/check-installer-execution-level.mjs` against the built installer); the round's residual job per `sandbox-CLAUDE.md` is just to report an elevation prompt as a finding if one appears. |

## Structured alternative (not superseded by this tier)

`tauri-driver`/WebView2 (tempdoc 374 item 4, already POC'd) drives the real
shell with element-based targeting instead of pixel coordinates, and remains
worth having for robustness. It is not currently blocking — this native
PowerShell tier unblocks GUI rounds today without it.
