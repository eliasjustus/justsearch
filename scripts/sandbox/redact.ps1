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

  BUG FIXED (round 12, tempdoc 806 W3 item 3): the previous
  `[IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))` unconditionally
  re-joined $Out onto the current directory even when $Out was already
  absolute, producing a malformed doubled-drive-letter string that
  GetFullPath rejects with "The given path's format is not supported." That
  failure then cascaded into `$bmp.Save($null)` -- also an error -- and
  because nothing upstream stopped the script on error, it still reached the
  final line and printed a success-shaped `redacted -> ( bytes)` -- the
  round-10 "H1" class (claimed captures, zero files) living inside the one
  tool whose job is protecting a secret. Round 12 had already deleted the
  unredacted original on the strength of that line; the capture was only
  recoverable because the secret was still on screen. `$ErrorActionPreference
  = "Stop"` plus the rooted/relative branch below close both holes: any
  failure anywhere is now a terminating, non-zero-exit error, an absolute
  -Out is resolved as-is (never re-joined to the CWD), and the final
  `redacted ->` line is reached only after Test-Path AND a non-zero byte
  count confirm the file genuinely exists. Regression test:
  scripts/sandbox/test_redact_failure.py.
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

# Make every failure terminating -- a caught .NET exception below still
# aborts the script (via the explicit throw/catch), but this also covers any
# cmdlet call that would otherwise degrade to a non-terminating error and
# let execution limp forward to the final "success" line.
$ErrorActionPreference = "Stop"

try {
  Add-Type -AssemblyName System.Drawing
  $src = [System.Drawing.Image]::FromFile((Resolve-Path $In).ProviderPath)
  $bmp = New-Object System.Drawing.Bitmap($src.Width, $src.Height)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.DrawImage($src, 0, 0, $src.Width, $src.Height)
  $g.FillRectangle([System.Drawing.Brushes]::Black, $X, $Y, $W, $H)
  $font = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
  $g.DrawString($Label, $font, [System.Drawing.Brushes]::White, ($X + 8), ($Y + ($H/2) - 12))
  $g.Dispose(); $src.Dispose()

  if ([System.IO.Path]::IsPathRooted($Out)) {
    # Already absolute -- resolve as-is. Re-joining onto the CWD (the old
    # unconditional behavior) is what produced the malformed path above.
    $outAbs = [System.IO.Path]::GetFullPath($Out)
  } else {
    $outAbs = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
  }

  $bmp.Save($outAbs, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  if (-not (Test-Path -LiteralPath $outAbs)) {
    throw "redact FAILED: no file was written at $outAbs"
  }
  $bytes = (Get-Item -LiteralPath $outAbs).Length
  if ($bytes -le 0) {
    throw "redact FAILED: $outAbs exists but is empty (0 bytes)"
  }

  # Only reached once Test-Path AND a non-zero byte count both confirm a
  # genuine file on disk -- never print this line on the strength of an
  # assumption.
  "redacted -> $outAbs ($bytes bytes)"
} catch {
  Write-Error "redact.ps1 FAILED: $($_.Exception.Message)"
  exit 1
}
