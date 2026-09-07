# Native Windows screen capture via .NET - no computer-use tool required.
# Usage: powershell -File snap.ps1 <outfile.png>
#        powershell -File snap.ps1 -Out win.png -ForceWindowCapture [-ProcessName notepad]
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
#
# Exits NON-ZERO when the capture fails (sandbox round-10 finding H1): the
# "saved:" line is Write-Host, so the process exit code + the file's existence
# are the only signals a caller can honestly act on. Never soften this catch
# to a warning -- a zero exit here means "the PNG is on disk".
#
# Round 18 (tempdoc 941 finding H2): the full-desktop path is NOT universally
# available. On Windows Sandbox's RDP indirect display (rdpidd.inf) every
# CopyFromScreen throws "The handle is invalid" (E_HANDLE), under BOTH
# PowerShell 7 and 5.1, and no PNG is written -- while per-window capture
# still works. So this script no longer fails the round when the desktop DC is
# unusable: it falls back to a per-window PrintWindow(PW_RENDERFULLCONTENT)
# capture of the JustSearch shell (resolved from the OS process
# ExecutablePath %LOCALAPPDATA%\JustSearch\JustSearch.exe -- never from a
# computer-use app identifier), or of whatever -Hwnd / -ProcessName /
# -ProcessPath names. The fallback captures ONE window, not the desktop: a
# dialog outside the target window will not be in the frame, so prefer
# win-capture.ps1 / -Hwnd for those.
#
# A SAVE failure (unwritable -Out) still exits 1 without any fallback -- the
# fallback exists for a dead capture SOURCE, and retrying a bad path would
# only re-fail more slowly.
#
# -ForceWindowCapture takes the fallback path directly, with no desktop
# attempt. It is how the fallback is exercised on a host whose desktop DC
# works (there is no way to make a normal desktop throw E_HANDLE on demand).
#
# Round 11 (tempdoc 805 item 6): this used to print
# [System.Windows.Forms.Screen]::PrimaryScreen.Bounds as "screen bounds:" and
# treat that as the capture's dimensions. Bounds can be DPI-virtualized (round
# 11 saw it report 1536x832) while CopyFromScreen/Bitmap.Save always write
# PHYSICAL pixels (round 11's actual PNG was 1920x1000) -- window/screen
# captures and GetWindowRect are both physical, so window-relative clicking
# was never affected, but any round doing full-desktop coordinate math from
# the printed "screen bounds:" line was off by the DPI scale factor. The
# reported Bounds line below is kept for diagnostics only, labeled as
# possibly-scaled; the authoritative "written:" line is read back from the
# saved PNG's actual pixel dimensions, which is what was really written.
param(
  [string]$Out = "snap.png",
  [switch]$ForceWindowCapture,
  [long]$Hwnd = 0,
  # -ProcName is accepted as an alias so this does not become another
  # "same idea, different parameter name" trap (README: the round that lost
  # 10 minutes to -Path vs -Out). win-capture.ps1 spells it -ProcName.
  [Alias("ProcName")][string]$ProcessName = "",
  [string]$ProcessPath = ""
)
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

# A failure whose message matches this is a SAVE failure, not a capture-source
# failure -- see the header. Keep in sync with Save-PngChecked's throw text
# (and with test_gui_capture_failure.py's _SAVE_FAILURE_MARKERS).
$SaveFailurePattern = 'could not save|does not exist after Save\(\)'

# These two write diagnostics to the OUTPUT stream, so they must not also
# return a value -- a function that does both hands its caller an ARRAY of
# [the printed lines + the value], and `if (Invoke-WindowCapture)` would then
# be true on a non-empty array even when the capture failed. They communicate
# through $script: variables instead.
$script:FallbackHandle = [IntPtr]::Zero
$script:WindowCaptureOk = $false

function Resolve-FallbackHandle {
  $script:FallbackHandle = [IntPtr]::Zero
  if ($Hwnd -ne 0) {
    Write-Output "fallback target: explicit -Hwnd $Hwnd"
    $script:FallbackHandle = [IntPtr]$Hwnd
    return
  }
  $target = Get-AppShellWindow -ProcessPath $ProcessPath -ProcessName $ProcessName
  if (-not $target) {
    return
  }
  Write-Output "fallback target: $($target.Process.ProcessName) pid=$($target.Process.Id) hwnd=$($target.Handle) path='$($target.Path)'"
  $script:FallbackHandle = $target.Handle
}

function Write-WrittenLine {
  param([string]$Path, [string]$How)
  $img = [System.Drawing.Image]::FromFile($Path)
  $writtenW = $img.Width
  $writtenH = $img.Height
  $img.Dispose()
  Write-Output "written (physical pixels, authoritative): ${writtenW}x${writtenH} -> $Path [$How]"
}

function Invoke-WindowCapture {
  # Sets $script:WindowCaptureOk; prints the reason when it stays $false.
  $script:WindowCaptureOk = $false
  Resolve-FallbackHandle
  $handle = $script:FallbackHandle
  if ($handle -eq [IntPtr]::Zero) {
    $wanted = $ProcessPath
    if (-not $wanted) {
      $wanted = Join-Path $env:LOCALAPPDATA "JustSearch\JustSearch.exe"
    }
    Write-Output "NO WINDOW for per-window capture (ProcessName='$ProcessName', ProcessPath='$wanted') -- pass -Hwnd/-ProcessName to name one"
    return
  }
  try {
    $savedPath = Save-AppShotPrintWindow -Handle $handle -Out $Out
    Write-WrittenLine -Path $savedPath -How "per-window PrintWindow(PW_RENDERFULLCONTENT)"
    $script:WindowCaptureOk = $true
  }
  catch {
    Write-Output "PER-WINDOW CAPTURE FAILED: $($_.Exception.Message)"
  }
}

if ($ForceWindowCapture) {
  Invoke-WindowCapture
  if ($script:WindowCaptureOk) {
    exit 0
  }
  Write-Output "CAPTURE FAILED: -ForceWindowCapture could not produce a PNG"
  exit 1
}

try {
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  Write-Output "reported screen bounds (may be DPI-scaled, informational only): $($bounds.Width)x$($bounds.Height)"
  $savedPath = Save-DesktopShot -Out $Out
  Write-WrittenLine -Path $savedPath -How "full desktop (CopyFromScreen)"
}
catch {
  $desktopError = $_.Exception.Message
  if ($desktopError -match $SaveFailurePattern) {
    Write-Output "CAPTURE FAILED: $desktopError"
    exit 1
  }
  Write-Output "DESKTOP CAPTURE FAILED: $desktopError"
  Write-Output "falling back to per-window capture (round 18 H2: the desktop DC is unusable on some display stacks)"
  Invoke-WindowCapture
  if ($script:WindowCaptureOk) {
    exit 0
  }
  Write-Output "CAPTURE FAILED: $desktopError"
  exit 1
}
