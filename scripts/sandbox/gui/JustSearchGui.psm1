# Shared P/Invoke + capture/input primitives for the sandbox GUI harness.
#
# snap.ps1 / win-capture.ps1 / click.ps1 / crop.ps1 / gui-approve.ps1 are thin
# wrappers over this module -- the ~30 lines of Add-Type P/Invoke boilerplate
# and the window-capture/click/path-resolution logic used to be re-declared in
# every script; it now lives here once. Import with:
#   Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force
#
# Windows PowerShell 5.1 target (no &&/||, no ternary, no ??).

Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue

# Guard against "type already exists" if the module is re-imported with
# -Force in the same process (e.g. interactive testing).
if (-not ([System.Management.Automation.PSTypeName]'JustSearchGui.Native').Type) {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
namespace JustSearchGui {
  public struct RECT { public int Left, Top, Right, Bottom; }
  public class Native {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int width, int height, bool repaint);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    public const uint LEFTDOWN = 0x0002;
    public const uint LEFTUP = 0x0004;
    public const int SW_RESTORE = 9;
  }
}
"@
}

function Resolve-AppPath {
  # Resolves a path to an absolute, normalized path WITHOUT requiring the
  # file to already exist (Resolve-Path fails on a not-yet-created output
  # file). All capture/crop entry points route their -Out through this so
  # every echoed path is absolute, not a bare filename the operator can't
  # locate (snap.ps1 / win-capture.ps1 / click.ps1 all had this bug).
  param([Parameter(Mandatory = $true)][string]$Path)
  $combined = $Path
  if (-not [System.IO.Path]::IsPathRooted($Path)) {
    $combined = Join-Path -Path (Get-Location).Path -ChildPath $Path
  }
  return [System.IO.Path]::GetFullPath($combined)
}

function Connect-App {
  # Locates a top-level window by process name, restores + foregrounds it,
  # then VERIFIES the foreground window actually matches (GetForegroundWindow
  # == target hwnd) before returning -- the same check win-capture.ps1 always
  # did informationally. Returns $null if no window is found (caller decides
  # the exact "NO WINDOW" message/exit code, so wrapper output stays
  # unchanged). Returns a PSCustomObject with .Process/.Handle/.Foreground/
  # .Focused otherwise; .Focused is what Invoke-AppClick gates on.
  #
  # ALT-nudge focus retry (round 12, tempdoc 806 W3 item 5 / retrospective
  # A3): Windows refuses SetForegroundWindow from a background process when
  # the desktop shell ("Program Manager") currently holds foreground -- this
  # made SetForegroundWindow return $true while NOT actually moving focus,
  # for five consecutive attempts in round 12 (~20 minutes lost, plus two
  # captures that recorded a pre-click state under a post-click name).
  # WScript.Shell.AppActivate and minimize/restore did not help; a raw ALT
  # keypress immediately before the retry did -- it satisfies the Win32
  # foreground-lock rule ("the calling thread must have received the last
  # input event") that SetForegroundWindow silently enforces. Only engages
  # when the first attempt fails, so the common case (focus succeeds
  # immediately) pays no extra delay.
  [CmdletBinding()]
  param(
    [string]$ProcName = "JustSearch",
    [int]$FocusDelayMs = 700,
    [int]$MaxFocusAttempts = 4
  )
  $p = Get-Process -Name $ProcName -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
  if (-not $p) {
    return $null
  }
  $h = $p.MainWindowHandle
  [void][JustSearchGui.Native]::ShowWindow($h, [JustSearchGui.Native]::SW_RESTORE)
  [void][JustSearchGui.Native]::SetForegroundWindow($h)
  Start-Sleep -Milliseconds $FocusDelayMs
  $fg = [JustSearchGui.Native]::GetForegroundWindow()

  $attempt = 1
  while ($fg -ne $h -and $attempt -lt $MaxFocusAttempts) {
    [System.Windows.Forms.SendKeys]::SendWait("%")
    Start-Sleep -Milliseconds 400
    [void][JustSearchGui.Native]::SetForegroundWindow($h)
    Start-Sleep -Milliseconds $FocusDelayMs
    $fg = [JustSearchGui.Native]::GetForegroundWindow()
    $attempt++
  }

  [PSCustomObject]@{
    Process    = $p
    Handle     = $h
    Foreground = $fg
    Focused    = ($fg -eq $h)
  }
}

function Get-AppWindowRect {
  [CmdletBinding()]
  param([Parameter(Mandatory = $true)][IntPtr]$Handle)
  $r = New-Object JustSearchGui.RECT
  [void][JustSearchGui.Native]::GetWindowRect($Handle, [ref]$r)
  return $r
}

function Set-AppWindowRect {
  # Moves/resizes the window at $Handle to an explicit rect via the Win32
  # MoveWindow API, in PHYSICAL pixels (same coordinate space as
  # GetWindowRect / Save-AppShot -- no DPI conversion here, keep it simple).
  #
  # README.md prescribes "Fix the window size at the start of a round for
  # determinism" but JustSearchGui.psm1 had no move/resize primitive -- round
  # 11 (tempdoc 805 item 6) had to write one from scratch, and its first
  # attempt called MoveWindow while the window was still maximized, which
  # corrupted the window's RESTORED geometry to 1520x32767; that bad geometry
  # then silently reappeared every time Connect-App's SW_RESTORE ran
  # afterward. This function restores the window BEFORE calling MoveWindow
  # (the same SW_RESTORE Connect-App uses), then reads the rect back and
  # THROWS if it does not match what was requested -- never a silent partial
  # resize a caller could mistake for success.
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][IntPtr]$Handle,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Width,
    [Parameter(Mandatory = $true)][int]$Height,
    [int]$SettleDelayMs = 200
  )
  [void][JustSearchGui.Native]::ShowWindow($Handle, [JustSearchGui.Native]::SW_RESTORE)
  Start-Sleep -Milliseconds $SettleDelayMs
  $ok = [JustSearchGui.Native]::MoveWindow($Handle, $X, $Y, $Width, $Height, $true)
  if (-not $ok) {
    throw "Set-AppWindowRect: MoveWindow failed for hwnd $Handle (requested $X,$Y ${Width}x${Height})"
  }
  Start-Sleep -Milliseconds $SettleDelayMs
  $r = Get-AppWindowRect -Handle $Handle
  $actualW = $r.Right - $r.Left
  $actualH = $r.Bottom - $r.Top
  if ($r.Left -ne $X -or $r.Top -ne $Y -or $actualW -ne $Width -or $actualH -ne $Height) {
    throw "Set-AppWindowRect: post-move rect ($($r.Left),$($r.Top) ${actualW}x${actualH}) does not match requested ($X,$Y ${Width}x${Height}) -- window may be maximized/snapped/minimized or under DPI virtualization; verify manually before trusting subsequent window-relative coordinates."
  }
  Write-Host "Set-AppWindowRect: OK -- hwnd $Handle now at $X,$Y ${Width}x${Height} (physical pixels)"
  return $r
}

function Invoke-AppClick {
  # Clicks at window-relative ($X,$Y) inside the window described by a
  # Connect-App connection. FAILS CLOSED: if $Connection.Focused is false
  # (the connected window is not actually foreground), this refuses to
  # click and returns $false -- it does NOT soften to a warning. A click
  # that lands in the wrong window can hit a Deny/Uninstall control in a
  # misfocused dialog; that is the failure this exists to prevent.
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][PSCustomObject]$Connection,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [int]$SettleDelayMs = 250,
    [int]$ClickHoldMs = 80
  )
  if (-not $Connection.Focused) {
    # Write-Host, NOT Write-Output: this function's return value ($true/$false)
    # is meant to be captured by callers (`if (-not (Invoke-AppClick ...))`).
    # A Write-Output here would land in the SAME success stream as the
    # `return`, so a capturing caller gets an array [message, $false] --
    # and `-not` on a non-empty array is always $false, silently defeating
    # the fail-closed gate this function exists to provide. (Caught live:
    # this exact bug shipped in the first draft and made click.ps1 click
    # through a focus mismatch instead of refusing.)
    Write-Host "FOCUS MISMATCH: foreground hwnd=$($Connection.Foreground) target hwnd=$($Connection.Handle) -- refusing to click"
    return $false
  }
  $r = Get-AppWindowRect -Handle $Connection.Handle
  $sx = $r.Left + $X
  $sy = $r.Top + $Y
  Write-Host "window at $($r.Left),$($r.Top) -> clicking screen ($sx,$sy) [window-rel $X,$Y]"
  [void][JustSearchGui.Native]::SetCursorPos($sx, $sy)
  Start-Sleep -Milliseconds $SettleDelayMs
  [JustSearchGui.Native]::mouse_event([JustSearchGui.Native]::LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds $ClickHoldMs
  [JustSearchGui.Native]::mouse_event([JustSearchGui.Native]::LEFTUP, 0, 0, 0, [IntPtr]::Zero)
  return $true
}

function Send-AppKeys {
  # Raw SendKeys typing. Goes to whatever element currently has focus (see
  # README.md gotcha #1) -- click the target field first. Do NOT use this
  # for JSON payloads: SendKeys metacharacters ({} + ^ % ~ ()) are control
  # syntax, not literal text (README.md gotcha #3). Use Send-AppText for
  # JSON-safe input.
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][string]$Keys,
    [int]$DelayMs = 1200
  )
  Write-Host "sending keys: $Keys"
  [System.Windows.Forms.SendKeys]::SendWait($Keys)
  if ($DelayMs -gt 0) {
    Start-Sleep -Milliseconds $DelayMs
  }
}

function Send-AppText {
  # JSON-safe text entry: stages $Text on the clipboard and pastes it
  # (Ctrl+V) instead of typing it via SendKeys. SendKeys interprets
  # {}, +, ^, %, ~, () as control syntax, so any JSON payload (the
  # presentation-editor surface, the skin-import box) MUST go through this
  # path, not Send-AppKeys. Click the target field first (same focus-is-
  # not-sticky caveat as Send-AppKeys).
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][string]$Text,
    [int]$DelayMs = 1200
  )
  Set-Clipboard -Value $Text
  Write-Host "pasting $($Text.Length) chars via clipboard"
  [System.Windows.Forms.SendKeys]::SendWait("^v")
  if ($DelayMs -gt 0) {
    Start-Sleep -Milliseconds $DelayMs
  }
}

function Save-PngChecked {
  # FAIL-LOUD PNG writer shared by every capture entry point.
  #
  # Sandbox round 10 finding H1 (second round reporting it): a capture whose
  # $bmp.Save() failed -- missing parent directory, unwritable path -- printed
  # its "saved: <path>" line anyway and the wrapper still exited 0, so the
  # harness reported evidence it had never produced. Three defences, in order:
  #   1. create the parent directory if missing (the common cause);
  #   2. Save inside try/catch that disposes AND rethrows (never swallows);
  #   3. assert the file actually exists afterwards and throw if it does not
  #      -- only then print "saved:", with the byte size read AFTER the assert.
  # The "saved:" line is Write-Host (host stream, not capturable), so a caller
  # can never treat it as evidence; existence + a nonzero process exit code
  # are the only honest signals, which is what this enforces.
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][System.Drawing.Bitmap]$Bitmap,
    [Parameter(Mandatory = $true)][string]$ResolvedOut,
    [System.Drawing.Graphics[]]$Graphics = @()
  )
  try {
    $parent = [System.IO.Path]::GetDirectoryName($ResolvedOut)
    if ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
      if (Test-Path -LiteralPath $parent) {
        throw "parent path '$parent' exists but is not a directory"
      }
      New-Item -ItemType Directory -Path $parent -Force -ErrorAction Stop | Out-Null
    }
    $Bitmap.Save($ResolvedOut, [System.Drawing.Imaging.ImageFormat]::Png)
  }
  catch {
    throw "CAPTURE FAILED: could not save '$ResolvedOut': $($_.Exception.Message)"
  }
  finally {
    foreach ($gfx in $Graphics) {
      if ($gfx) {
        $gfx.Dispose()
      }
    }
    $Bitmap.Dispose()
  }
  if (-not (Test-Path -LiteralPath $ResolvedOut -PathType Leaf)) {
    throw "CAPTURE FAILED: '$ResolvedOut' does not exist after Save() reported no error -- the capture produced NO evidence."
  }
  $bytes = (Get-Item -LiteralPath $ResolvedOut).Length
  Write-Host "saved: $ResolvedOut ($bytes bytes)"
  return $ResolvedOut
}

function Save-AppShot {
  # Captures the window described by $Handle's current rect and saves it as
  # a PNG at an absolute, resolved path (Resolve-AppPath). Throws (does not
  # return quietly) if the capture cannot be produced -- see Save-PngChecked.
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][IntPtr]$Handle,
    [Parameter(Mandatory = $true)][string]$Out
  )
  $resolvedOut = Resolve-AppPath -Path $Out
  $r = Get-AppWindowRect -Handle $Handle
  $w = $r.Right - $r.Left
  $ht = $r.Bottom - $r.Top
  if ($w -le 0 -or $ht -le 0) {
    # Same false-success class as a failed Save: returning $null here left
    # callers ([void](Save-AppShot ...)) exiting 0 with no PNG on disk.
    throw "CAPTURE FAILED: BAD RECT for hwnd $Handle ($($w)x$($ht)) -- no capture written to '$resolvedOut'."
  }
  $bmp = New-Object System.Drawing.Bitmap($w, $ht)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen((New-Object System.Drawing.Point($r.Left, $r.Top)), [System.Drawing.Point]::Empty, (New-Object System.Drawing.Size($w, $ht)))
  return (Save-PngChecked -Bitmap $bmp -ResolvedOut $resolvedOut -Graphics @($g))
}

function Save-DesktopShot {
  # Full-desktop capture (CopyFromScreen over the primary screen bounds).
  # Used by snap.ps1 for the Step-0 capability probe / whole-screen evidence.
  # Throws if the capture cannot be produced -- see Save-PngChecked.
  [CmdletBinding()]
  param([Parameter(Mandatory = $true)][string]$Out)
  $resolvedOut = Resolve-AppPath -Path $Out
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
  return (Save-PngChecked -Bitmap $bmp -ResolvedOut $resolvedOut -Graphics @($gfx))
}

function Save-AppShotRegion {
  # Crops a region out of an existing PNG and scales it up (nearest-neighbor)
  # for legibility -- e.g. illegible small text in a full-window capture.
  # Read the CROPPED region, not the full-resolution screenshot, whenever
  # you only need to confirm one small area (README.md guidance).
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][string]$InPath,
    [Parameter(Mandatory = $true)][string]$OutPath,
    [int]$X = 0,
    [int]$Y = 0,
    [int]$W = 100,
    [int]$H = 100,
    [int]$Scale = 3
  )
  $resolvedIn = (Resolve-Path -LiteralPath $InPath).ProviderPath
  $resolvedOut = Resolve-AppPath -Path $OutPath
  $src = [System.Drawing.Image]::FromFile($resolvedIn)
  Write-Host "source: $($src.Width)x$($src.Height)"
  if ($X + $W -gt $src.Width) {
    $W = $src.Width - $X
  }
  if ($Y + $H -gt $src.Height) {
    $H = $src.Height - $Y
  }
  $rect = New-Object System.Drawing.Rectangle($X, $Y, $W, $H)
  $crop = New-Object System.Drawing.Bitmap($W, $H)
  $g1 = [System.Drawing.Graphics]::FromImage($crop)
  $g1.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $W, $H)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
  $g1.Dispose()
  $big = New-Object System.Drawing.Bitmap(($W * $Scale), ($H * $Scale))
  $g2 = [System.Drawing.Graphics]::FromImage($big)
  $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
  $g2.DrawImage($crop, 0, 0, ($W * $Scale), ($H * $Scale))
  $g2.Dispose()
  $src.Dispose()
  $crop.Dispose()
  Write-Host "cropped region: $($W * $Scale)x$($H * $Scale)"
  return (Save-PngChecked -Bitmap $big -ResolvedOut $resolvedOut)
}

function Get-AppApiPort {
  # Canonical port discovery (same convention as collect-evidence.ps1, tempdoc
  # 501): read .head.apiPort out of the runtime manifest. Returns $null (does
  # NOT guess/fallback) if the manifest is missing or unparsable -- callers
  # decide what to do with a $null port.
  [CmdletBinding()]
  param([string]$DataDir = "$env:APPDATA\io.justsearch.shell")
  $manifestPath = Join-Path -Path $DataDir -ChildPath "runtime\manifest.json"
  if (-not (Test-Path -LiteralPath $manifestPath)) {
    return $null
  }
  try {
    $manifestRaw = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop
    $manifest = $manifestRaw | ConvertFrom-Json -ErrorAction Stop
    if ($manifest.head -and $manifest.head.apiPort) {
      return [int]$manifest.head.apiPort
    }
  } catch {
    return $null
  }
  return $null
}

function Assert-AppSurface {
  # Assert-then-act: fetches GET /api/action-ledger and checks that the most
  # recent NAVIGATION entry's targetSurface matches $ExpectedSurface. Throws
  # (terminating error) on mismatch or on any fetch failure -- this is meant
  # to distinguish "the click was a silent no-op" from "the surface changed
  # but not to the one we expected" for a caller that just drove a nav click.
  #
  # $ExpectedSurface accepts either a bare surface id (e.g. "core.library")
  # or a full justsearch://surface/<id>[?...] address -- the justsearch://
  # scheme prefix and any query string are stripped before comparing, since
  # the ledger's wire row (ActionLedgerProjection.toWireRow) carries the bare
  # SurfaceRef id in "targetSurface", not the full address.
  #
  # Wire shape actually returned by the controller (verified against
  # modules/ui/.../ActionLedgerController.java + app-observability's
  # ActionLedgerProjection.java, NOT the "subject" field the brief assumed):
  #   GET /api/action-ledger -> { entries: [ { kind, occurredAt, originator,
  #     transport, ..., targetSurface, sourceId } ] }, oldest first (most
  #   recent last). Navigation rows carry kind == "navigation" and
  #   "targetSurface" -- there is no "subject" field on a navigation row
  #   (only Grant/Effect rows carry "subject").
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][string]$ExpectedSurface,
    [int]$ApiPort,
    [string]$BaseUrl,
    [string]$DataDir = "$env:APPDATA\io.justsearch.shell",
    [int]$TimeoutSec = 10
  )
  if (-not $BaseUrl) {
    $port = $ApiPort
    if (-not $port) {
      $port = Get-AppApiPort -DataDir $DataDir
    }
    if (-not $port) {
      throw "Assert-AppSurface: could not resolve an API port -- pass -ApiPort or -BaseUrl explicitly (no manifest.json found under '$DataDir')"
    }
    $BaseUrl = "http://127.0.0.1:$port"
  }

  $expectedId = $ExpectedSurface
  if ($expectedId -match '^justsearch://surface/([^?]+)') {
    $expectedId = $Matches[1]
  }

  $url = "$BaseUrl/api/action-ledger"
  try {
    $resp = Invoke-RestMethod -Uri $url -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
  } catch {
    $respBody = $null
    if ($_.Exception.Response) {
      try {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $respBody = $reader.ReadToEnd()
      } catch {
        $respBody = $null
      }
    }
    $detail = $_.Exception.Message
    if ($respBody) {
      $detail = "$detail body: $respBody"
    }
    throw "Assert-AppSurface: GET $url failed: $detail"
  }

  $entries = @($resp.entries)
  $navEntries = @($entries | Where-Object { $_.kind -eq 'navigation' })
  if ($navEntries.Count -eq 0) {
    throw "Assert-AppSurface: no navigation entries in action-ledger (expected surface '$expectedId')"
  }
  # Entries are oldest-first, most recent last (controller doc comment) --
  # the last navigation entry is the most recent navigation.
  $last = $navEntries[$navEntries.Count - 1]
  if ($last.targetSurface -ne $expectedId) {
    throw "Assert-AppSurface: most recent navigation targetSurface='$($last.targetSurface)' occurredAt=$($last.occurredAt) does not match expected '$expectedId'"
  }
  Write-Host "Assert-AppSurface: OK -- targetSurface='$($last.targetSurface)' occurredAt=$($last.occurredAt)"
  return $last
}

Export-ModuleMember -Function `
  Resolve-AppPath, `
  Connect-App, `
  Get-AppWindowRect, `
  Set-AppWindowRect, `
  Invoke-AppClick, `
  Send-AppKeys, `
  Send-AppText, `
  Save-AppShot, `
  Save-DesktopShot, `
  Save-AppShotRegion, `
  Get-AppApiPort, `
  Assert-AppSurface
