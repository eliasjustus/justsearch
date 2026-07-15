# Find, focus, and capture a specific top-level window by process name.
# Proves per-window GUI capture without any computer-use tool.
# Usage: win-capture.ps1 -ProcName JustSearch -Out shot.png [-Keys "^k"] [-DelayMs 1200]
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
param(
  [string]$ProcName = "JustSearch",
  [string]$Out = "win.png",
  [string]$Keys = "",
  [int]$DelayMs = 1200
)
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

$conn = Connect-App -ProcName $ProcName -FocusDelayMs 700
if (-not $conn) {
  Write-Output "NO WINDOW for process '$ProcName'"
  exit 1
}
Write-Output "process: $($conn.Process.ProcessName) pid=$($conn.Process.Id) hwnd=$($conn.Handle) title='$($conn.Process.MainWindowTitle)'"
Write-Output "foreground hwnd after focus: $($conn.Foreground)  (match=$($conn.Focused))"

if ($Keys -ne "") {
  Send-AppKeys -Keys $Keys -DelayMs $DelayMs
}

$r = Get-AppWindowRect -Handle $conn.Handle
$w = $r.Right - $r.Left
$ht = $r.Bottom - $r.Top
Write-Output "window rect: $($r.Left),$($r.Top) ${w}x${ht}"
if ($w -le 0 -or $ht -le 0) {
  Write-Output "BAD RECT"
  exit 1
}

[void](Save-AppShot -Handle $conn.Handle -Out $Out)
