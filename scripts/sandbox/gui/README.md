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
| `snap.ps1` | Full-desktop capture (`CopyFromScreen`). Use for the Step-0 capability probe and whole-screen evidence. Prints the PHYSICAL-pixel dimensions actually written (read back from the saved PNG), not `Screen.PrimaryScreen.Bounds`, which can be DPI-scaled and mismatch what got saved. |
| `win-capture.ps1` | Locate + focus + capture ONE window by process name (`GetWindowRect` + `SetForegroundWindow`). Optional `-Keys` sends keystrokes before capturing. |
| `click.ps1` | Click at window-relative coordinates in a target window, then capture. Fails closed (exits 1, no click sent) if the window did not actually take foreground focus, **and again at capture time** — after the `-DelayMs` sleep it re-asserts the clicked hwnd is still foreground and exits 1 WITHOUT writing a file if it is not (round 16 captured a terminal window under a health-surface filename this way). `-DelayMs` is capped at 3000 ms, checked before the click so a refusal has no side effect; `NO WINDOW` failures echo the actual `-ProcName` value searched for, not a hardcoded default. |
| `crop.ps1` | Crop + magnify a region of an existing PNG (for illegible small text). **The wrapper's parameters are `-In`/`-Out`** (`-InPath`/`-OutPath` belong to the module function it calls — see the table below). Dimension parameters are `-X -Y -W -H`, not `-Width`/`-Height` -- passing the wrong flag names fails loud instead of silently cropping the 100x100 default. |
| `gui-approve.ps1` | **EXAMPLE**, not a generic tool — see below. |

## Parameter-signature quick reference (round 12: `-Path` silently wrote ZERO PNGs for 10 minutes)

Each capture/connect function in `JustSearchGui.psm1` has a DIFFERENT
parameter name for "where does this go" / "what am I connecting to" -- a
round guessed wrong once and lost 10 minutes to a `try/catch` that swallowed
the resulting error while reporting nothing:

**This table lists the MODULE functions in `JustSearchGui.psm1`, not the
wrapper scripts.** The wrappers do not all pass their own parameter names
through: `crop.ps1` takes **`-In`/`-Out`** and forwards them to
`Save-AppShotRegion`'s `-InPath`/`-OutPath`. Round 16 read the module row for
the wrapper and got the names wrong (tempdoc 823 §4). Wrapper parameter names
are in the *What each script does* table above and each script's usage
comment; module parameter names are here.

| Function (module) | Required parameters | Notes |
|---|---|---|
| `Connect-App` | either `-ProcName` (default `"JustSearch"`) **or** `-Hwnd <IntPtr>`, plus `-FocusDelayMs` (default `700`), `-MaxFocusAttempts` (default `4`) | Produces the connection object (`$conn` in every wrapper script's examples) that every other function below needs -- `$conn.Handle`, `$conn.Focused`, `$conn.Process`, `$conn.Foreground`. The README used to show `$conn.Handle` without ever naming this function. **`-Hwnd` addresses a window that is NOT a process's `MainWindowHandle`** -- the shell **Properties** dialog and the NSIS installer/uninstaller wizards, which the `-ProcName` lookup structurally cannot reach (round 16 needed all three for must-watch captures and rebuilt the plumbing in a scratchpad that was wiped with the sandbox). Everything after the lookup -- restore, foreground, ALT-nudge retry, `.Focused` verification -- is the same code path, so the fail-closed contract is identical. `.Process` is best-effort under `-Hwnd` (resolved from the owning pid) and can be `$null`; `.Handle`/`.Focused` are the load-bearing fields. Enumerate candidate hwnds with `[System.Diagnostics.Process]::GetProcesses()` / `EnumWindows` or read one off the process that owns the dialog. |
| `Save-DesktopShot` | `-Out` | Full-desktop capture. **NOT `-Path`.** |
| `Save-AppShot` | `-Handle`, `-Out` | Captures ONE window by hwnd (pass `$conn.Handle`). **NOT `-Path`, and there is NO `-ProcName`** on this function -- unlike `win-capture.ps1`, which takes `-ProcName` and calls `Connect-App` + `Save-AppShot` internally. |
| `Save-AppShotRegion` | `-InPath`, `-OutPath`, `-X -Y -W -H`, `-Scale` (default `3`) | Crop + magnify an EXISTING PNG. Different shape from the two above: `-InPath`/`-OutPath`, not `-Out`. |
| `Invoke-AppClick` | `-Connection`, `-X`, `-Y` | Clicks window-relative `-X`/`-Y` inside the window described by a `Connect-App` connection object. **Takes `-Connection` (the whole `$conn` object from `Connect-App`), NOT `-Handle`.** Passing `-Handle` binds to nothing, PowerShell does not error on the unrecognized parameter inside a `try/catch` that swallows it, and the click silently no-ops while the function still returns -- a round guessed `-Handle` (extrapolating from `Save-AppShot`'s shape) and got three confidently-named screenshots of a state the click never reached (round 15, tempdoc 817). Fails closed (returns `$false`, no click sent) if `$Connection.Focused` is `$false`. |
| `Send-AppKeys` | `-Keys` | Raw `SendKeys` typing to whatever element currently has focus. **Takes `-Keys` only -- there is no connection/window parameter on this function** (unlike `Invoke-AppClick`/`win-capture.ps1`, which do take one). Click the target field first (focus is not sticky -- gotcha #1 below); do not pass `-Handle` or `-Connection` here, it will bind to nothing. |

Passing `-Path` to `Save-DesktopShot`/`Save-AppShot`/`Save-AppShotRegion` is a
silent no-op from PowerShell's perspective in a script that also swallows
the resulting parameter-binding error (e.g. a driver wrapping the call in
`try/catch`) -- the function throws "CAPTURE FAILED" or a binding error, the
`catch` eats it, and nothing is written with no visible failure. Always
verify with `Test-Path` on the actual `-Out`/`-OutPath` value after a
capture call, not just a non-throwing script exit.

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

## Six mechanical gotchas (cost 3+ attempts each to discover — don't re-spend the round on them)

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
4. **The native "Select folder" dialog: pasting a full path + Enter NAVIGATES
   INTO the folder, it does not select it** (standard Windows Explorer
   behaviour, not a JustSearch quirk). Sending `^v` then `{ENTER}` into the
   folder-name field lands you one level DEEPER in the tree, not on the
   folder you meant to pick — costing round 15 ~4 minutes rediscovering this.
   The correct sequence is paste, `{ENTER}` (to commit the path into the
   field/navigate to it), THEN click the "Select Folder" button — do not
   treat Enter alone as a substitute for the click.
5. **The shell "Properties" dialog dies with the process that opened it**
   (round 16, tempdoc 823 §4 — cost one wasted capture, and it is written
   down nowhere else). `Start-Process`-ing it from a PowerShell that the tool
   call kills when it returns takes the dialog down with it, so the next call
   finds no window. Open it from a **detached** PowerShell that outlives the
   tool call, then address it by hwnd:
   ```powershell
   Start-Process powershell -ArgumentList '-NoProfile','-Command',
     '(New-Object -ComObject Shell.Application).Namespace((Split-Path $exe)).ParseName((Split-Path $exe -Leaf)).InvokeVerb("Properties"); Start-Sleep 120'
   # then, from the next call: find the dialog hwnd and
   $conn = Connect-App -Hwnd $dialogHwnd
   ```
6. **Click and capture in one call only for fast surfaces; otherwise split
   them.** `click.ps1` sleeps `-DelayMs` between the click and its capture,
   and anything that steals the foreground during that sleep gets captured
   instead (round 16, with `-DelayMs 6000`). The script now re-asserts the
   clicked hwnd before capturing and refuses to write a file on a mismatch,
   and caps `-DelayMs` at 3000 ms — so for a surface that takes longer to
   settle, use the **click-then-separate-capture** pattern: `click.ps1` with
   a short delay for the ACTION, then a separate `win-capture.ps1` call for
   the EVIDENCE frame (it connects and focuses immediately before capturing,
   so it cannot drift). This does not contradict gotcha #2 — that one is
   about splitting *type* and *click*, which resets the dialog; splitting the
   *evidence capture* off a completed click is safe and is now the
   recommended shape.

## Pixel-coordinate caveat

There is no element-based targeting into the Tauri WebView2 content (see
tested-negative list below) — every click is a raw pixel coordinate.
Mitigations:

- **Fix the window size** at the start of a round for determinism across
  screenshots, via `Set-AppWindowRect` in `JustSearchGui.psm1`
  (`Set-AppWindowRect -Handle $conn.Handle -X 0 -Y 0 -Width 1600 -Height 900`
  -- physical pixels, no DPI conversion). It restores the window before
  calling `MoveWindow` and reads the rect back afterward, throwing if the
  result does not match what was requested -- round 11 had no primitive for
  this at all and its first hand-written attempt corrupted the window's
  restored geometry to 1520x32767 (tempdoc 805 item 6).
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
