# Native Windows screen capture via .NET - no computer-use tool required.
# Usage: powershell -File snap.ps1 <outfile.png>
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
#
# Exits NON-ZERO when the capture fails (sandbox round-10 finding H1): the
# "saved:" line is Write-Host, so the process exit code + the file's existence
# are the only signals a caller can honestly act on. Never soften this catch
# to a warning -- a zero exit here means "the PNG is on disk".
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
param([string]$Out = "snap.png")
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

try {
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  Write-Output "reported screen bounds (may be DPI-scaled, informational only): $($bounds.Width)x$($bounds.Height)"
  $savedPath = Save-DesktopShot -Out $Out
  $img = [System.Drawing.Image]::FromFile($savedPath)
  $writtenW = $img.Width
  $writtenH = $img.Height
  $img.Dispose()
  Write-Output "written (physical pixels, authoritative): ${writtenW}x${writtenH} -> $savedPath"
}
catch {
  Write-Output "CAPTURE FAILED: $($_.Exception.Message)"
  exit 1
}
