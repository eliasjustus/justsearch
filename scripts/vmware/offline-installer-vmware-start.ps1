#Requires -Version 5.1
[CmdletBinding()]
param(
  # Do not auto-launch the installer UI (only open the checklist + folder).
  [switch]$NoLaunchInstaller
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function BestEffort([scriptblock]$Block) {
  try { & $Block } catch { }
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$steps = Join-Path -Path $here -ChildPath "offline-installer-vmware-steps.txt"
$legacySteps = Join-Path -Path $here -ChildPath "phase3-vm-steps.txt"
if (-not (Test-Path -LiteralPath $steps) -and (Test-Path -LiteralPath $legacySteps)) {
  $steps = $legacySteps
}
$installer = Get-ChildItem -LiteralPath $here -Filter "*-setup.exe" -File -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

Write-Host ""
Write-Host "=== JustSearch Offline Installer Verification (VMware VM) ==="
Write-Host "Folder: $here"
if (Test-Path -LiteralPath $steps) {
  Write-Host "Steps:  $steps"
} else {
  Write-Host "Steps:  (offline-installer-vmware-steps.txt not found)"
}
if ($installer) {
  Write-Host "Setup:  $($installer.FullName)"
} else {
  Write-Host "Setup:  (no *-setup.exe found in folder)"
}
Write-Host ""
Write-Host "Tip: Make sure VM networking is DISABLED before installing (offline WebView2 validation)."
Write-Host ""

# Bring up instructions in Notepad + open folder in Explorer.
BestEffort { if (Test-Path -LiteralPath $steps) { Start-Process -FilePath "notepad.exe" -ArgumentList @($steps) | Out-Null } }
BestEffort { Start-Process -FilePath "explorer.exe" -ArgumentList @($here) | Out-Null }

if ($installer -and -not $NoLaunchInstaller.IsPresent) {
  Write-Host "Launching installer UI..."
  BestEffort { Start-Process -FilePath $installer.FullName | Out-Null }
} elseif (-not $installer) {
  Write-Warning "No *-setup.exe found in this folder. Copy the installer here (from the host staged share) and re-run."
}

Write-Host ""
Write-Host "After install + launching JustSearch, run:"
Write-Host "  powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$here\offline-installer-vmware-verify.ps1`""
Write-Host ""


