# Native Windows screen capture via .NET - no computer-use tool required.
# Usage: powershell -File snap.ps1 <outfile.png>
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
#
# Exits NON-ZERO when the capture fails (sandbox round-10 finding H1): the
# "saved:" line is Write-Host, so the process exit code + the file's existence
# are the only signals a caller can honestly act on. Never soften this catch
# to a warning -- a zero exit here means "the PNG is on disk".
param([string]$Out = "snap.png")
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

try {
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  Write-Output "screen bounds: $($bounds.Width)x$($bounds.Height)"
  [void](Save-DesktopShot -Out $Out)
}
catch {
  Write-Output "CAPTURE FAILED: $($_.Exception.Message)"
  exit 1
}
