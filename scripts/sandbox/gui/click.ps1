# Click at window-relative coords in a target window, then capture.
# Usage: click.ps1 -ProcName JustSearch -X 738 -Y 537 -Out after.png
# Thin wrapper over JustSearchGui.psm1. FAILS CLOSED: Invoke-AppClick refuses
# to click (exits 1, no click sent) if the target window did not actually
# take foreground focus -- ported from win-capture.ps1's foreground
# assertion, which this script previously lacked (it clicked blind).
param([string]$ProcName = "JustSearch", [int]$X = 0, [int]$Y = 0, [string]$Out = "after.png", [int]$DelayMs = 2000)
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

$conn = Connect-App -ProcName $ProcName -FocusDelayMs 600
if (-not $conn) {
  Write-Output "NO WINDOW"
  exit 1
}

if (-not (Invoke-AppClick -Connection $conn -X $X -Y $Y -SettleDelayMs 250 -ClickHoldMs 80)) {
  exit 1
}

Start-Sleep -Milliseconds $DelayMs
[void](Save-AppShot -Handle $conn.Handle -Out $Out)
