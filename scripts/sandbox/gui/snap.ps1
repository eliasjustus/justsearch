# Native Windows screen capture via .NET - no computer-use tool required.
# Usage: powershell -File snap.ps1 <outfile.png>
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
param([string]$Out = "snap.png")
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
Write-Output "screen bounds: $($bounds.Width)x$($bounds.Height)"
[void](Save-DesktopShot -Out $Out)
