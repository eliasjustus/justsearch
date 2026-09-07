# Repair computer-use screenshots saved as JPEG bytes under .png filenames.
#
# Round 18 (tempdoc 941, the first Codex round): Computer Use wrote its
# captures straight to the coverage filenames -- but as JPEG bytes named
# `*.png`. Coverage credit is by filename token, so nothing failed; the
# evidence archive just contained files whose bytes did not match their name.
# The round built this repair script in-sandbox (`round-tools/`) and named it
# in its retrospective, so it is promoted here and staged with the rest of
# `gui/`.
#
# Usage:
#   powershell -File convert-cu-images.ps1 -Directory <evidence dir> [-Recurse]
#
# Prefer not needing it: verify the magic bytes right after your FIRST capture
# (`89 50 4E 47` = PNG, `FF D8` = JPEG) rather than at finalize --
#   $b = [System.IO.File]::ReadAllBytes($png); '{0:X2} {1:X2}' -f $b[0], $b[1]
# -- so a whole round's captures are not written in the wrong format.
#
# Exits NON-ZERO if any `.png` in scope is still not PNG bytes afterwards
# (same fail-loud contract as the capture scripts: the exit code is the
# signal, not the printed lines).
param(
  [Parameter(Mandatory = $true)][string]$Directory,
  [switch]$Recurse
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$resolved = (Resolve-Path -LiteralPath $Directory).Path
$files = @(Get-ChildItem -LiteralPath $resolved -File -Filter '*.png' -Recurse:$Recurse)
$converted = 0
$alreadyPng = 0
$unknown = @()

foreach ($file in $files) {
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  $isPng = ($bytes.Length -ge 4 -and $bytes[0] -eq 0x89 -and $bytes[1] -eq 0x50 -and $bytes[2] -eq 0x4E -and $bytes[3] -eq 0x47)
  $isJpeg = ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8)
  if ($isPng) {
    $alreadyPng++
    continue
  }
  if (-not $isJpeg) {
    $unknown += $file.FullName
    continue
  }
  $tmp = "$($file.FullName).converted.tmp"
  $image = [System.Drawing.Image]::FromFile($file.FullName)
  try {
    $image.Save($tmp, [System.Drawing.Imaging.ImageFormat]::Png)
  }
  finally {
    $image.Dispose()
  }
  Move-Item -LiteralPath $tmp -Destination $file.FullName -Force
  $converted++
  Write-Output "converted (JPEG bytes -> PNG): $($file.Name)"
}

Write-Output "checked $($files.Count) .png file(s) under $resolved -- $alreadyPng already PNG, $converted converted, $($unknown.Count) unrecognized"
if ($unknown.Count -gt 0) {
  foreach ($u in $unknown) {
    Write-Output "NOT AN IMAGE THIS SCRIPT CAN REPAIR (neither PNG nor JPEG bytes): $u"
  }
  exit 1
}
