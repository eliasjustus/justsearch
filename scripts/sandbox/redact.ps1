<#
.SYNOPSIS
  Black out a rectangular region of a PNG and drop a labeled placeholder.

.DESCRIPTION
  The chat-encryption recovery-key ceremony (and similar secret-bearing
  surfaces) renders a secret VALUE on screen, and the round charter forbids
  transcribing that value into evidence. With nothing staged that can
  redact, a round must either lose the capture entirely or leak the secret.
  This copies the source PNG, fills the given rectangle black, draws a
  label over it, and saves the result -- keep the capture, remove the
  secret, then delete the unredacted original.

.PARAMETER In
  Source PNG path.

.PARAMETER Out
  Destination PNG path for the redacted copy.

.PARAMETER X
.PARAMETER Y
.PARAMETER W
.PARAMETER H
  Rectangle to black out, in the SAME physical-pixel coordinate space as
  the source PNG (see gui/README.md and snap.ps1's printed-dimensions fix,
  tempdoc 805 item 6 -- window/screen captures are physical pixels, not
  DPI-scaled).

.PARAMETER Label
  Text drawn over the blacked-out rectangle (default "REDACTED").

.EXAMPLE
  .\redact.ps1 -In .\evidence\42-recovery-key.png -Out .\evidence\42-recovery-key-redacted.png -X 95 -Y 522 -W 705 -H 44
  Remove-Item .\evidence\42-recovery-key.png   # delete the unredacted original

.NOTES
  Provenance: adopted from round 11 (tmp/sandbox-round11/share/redact.ps1)
  per tempdoc 805 Part E / G.5 -- flagged for staging because "a round must
  either lose the capture or leak the secret" without it.
#>
param(
  [Parameter(Mandatory=$true)][string]$In,
  [Parameter(Mandatory=$true)][string]$Out,
  [Parameter(Mandatory=$true)][int]$X,
  [Parameter(Mandatory=$true)][int]$Y,
  [Parameter(Mandatory=$true)][int]$W,
  [Parameter(Mandatory=$true)][int]$H,
  [string]$Label = "REDACTED"
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $In).ProviderPath)
$bmp = New-Object System.Drawing.Bitmap($src.Width, $src.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($src, 0, 0, $src.Width, $src.Height)
$g.FillRectangle([System.Drawing.Brushes]::Black, $X, $Y, $W, $H)
$font = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
$g.DrawString($Label, $font, [System.Drawing.Brushes]::White, ($X + 8), ($Y + ($H/2) - 12))
$g.Dispose(); $src.Dispose()
$outAbs = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
$bmp.Save($outAbs, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
if (-not (Test-Path $outAbs)) { throw "redact FAILED: $outAbs" }
"redacted -> $outAbs ($((Get-Item $outAbs).Length) bytes)"
