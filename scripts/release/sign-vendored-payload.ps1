#Requires -Version 5.1
<#
.SYNOPSIS
  Sign-once mirror producer for vendored third-party payload archives (tempdoc 760 §"Takeover + Phase 2 design" item 5; origin tempdoc 772 §K).

.DESCRIPTION
  Vendored third-party payloads (llama.cpp prebuilt zip, Tesseract runtime, ...) ship
  ~93 unsigned PEs that are pin-stable across releases. Tauri's `should_sign` re-signs every
  unsigned PE on EVERY release build, so per-release signings balloon. This tool signs those
  PEs ONCE per upstream pin bump; the signed archive is hosted as a mirror on
  `justsearch-releases`, so subsequent release builds see already-Valid signatures and skip them
  (mirror of Tauri's should_sign). Collapses per-release signings from ~101 to ~8.

  Flow: archive in -> extract (7z) -> for every MZ-bearing file: if Authenticode already
  Valid, SKIP (do not re-sign vendor-signed files); else sign it by invoking the existing
  scripts/ci/sign-windows.ps1 as a child (so all JUSTSEARCH_CODESIGN_MODE credential modes work
  here for free) -> re-zip preserving the archive's internal directory layout exactly ->
  emit <basename>-signed.zip + <output>.sha256.

  Credential modes / secrets are the child sign-windows.ps1's contract (JUSTSEARCH_CODESIGN_*
  env). This script sets JUSTSEARCH_REQUIRE_SIGNING=true for the child (its whole purpose is to
  sign) UNLESS -AllowUnsigned is passed, and independently verifies the complete extracted tree
  plus a second extraction of the repacked archive (defense in depth against skips and repack drift).

  Fail-closed: any PE that should be signed but is still not Valid after the signing attempt
  fails the run and lists the offending files -- UNLESS -AllowUnsigned (rehearsal only) is set.

.PARAMETER ArchivePath
  Input archive (zip) containing the vendored payload.

.PARAMETER OutDir
  Output directory for the signed mirror + sha256. Default: dist/signed-mirrors.

.PARAMETER AllowUnsigned
  REHEARSAL ONLY. Do not fail closed when a PE remains unsigned after the signing attempt
  (e.g. no credentials configured). Never use for a real mirror -- it produces an unsigned
  archive that defeats the whole purpose.

.PARAMETER MaxSignatures
  Reviewed maximum number of newly signed PEs permitted for this payload.

.PARAMETER ProviderRemainingSignatures
  Provider-portal remaining allocation captured before the run. Required for a production signing
  run and checked against the cumulative shared-ledger count plus this payload's pre-sign census.

.EXAMPLE
  # Real pin-bump run (PFX mode):
  $env:JUSTSEARCH_CODESIGN_MODE='pfx'; $env:JUSTSEARCH_CODESIGN_PFX_PATH='C:\keys\cs.pfx'; $env:JUSTSEARCH_CODESIGN_PFX_PASSWORD='***'; $env:JUSTSEARCH_CODESIGN_TIMESTAMP_URL='http://timestamp.digicert.com'
  scripts\release\sign-vendored-payload.ps1 -ArchivePath build\llama-b8571-bin-win-cpu-x64.zip -ProviderRemainingSignatures 100
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$ArchivePath,

  [string]$OutDir = "dist/signed-mirrors",

  [int]$MaxSignatures = 100,

  [int]$ProviderRemainingSignatures = 0,

  [switch]$AllowUnsigned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
  Write-Error $Message
  exit 1
}

function Info([string]$Message) {
  Write-Host $Message
}

function Resolve-SevenZip {
  $candidates = @(
    'F:\scoop\apps\7zip\current\7z.exe',
    (Join-Path ${env:ProgramFiles} '7-Zip\7z.exe')
  )
  foreach ($c in $candidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { return (Resolve-Path -LiteralPath $c).Path }
  }
  # Scoop shims are broken in some environments, so PATH is the last resort.
  $onPath = Get-Command '7z.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($onPath -and $onPath.Path) { return $onPath.Path }
  return $null
}

# A PE begins with the ASCII "MZ" DOS-header magic. Files matched by the include glob that are
# not real PEs (e.g. a text .dll) are reported as non-PE and left untouched.
function Test-IsPE([string]$Path) {
  try {
    $fs = [System.IO.File]::OpenRead($Path)
    try {
      if ($fs.Length -lt 2) { return $false }
      $b0 = $fs.ReadByte(); $b1 = $fs.ReadByte()
      return ($b0 -eq 0x4D -and $b1 -eq 0x5A)  # 'M','Z'
    } finally { $fs.Dispose() }
  } catch { return $false }
}

# --- Resolve inputs ------------------------------------------------------------------------
$resolvedArchive = if ([System.IO.Path]::IsPathRooted($ArchivePath)) { $ArchivePath } else { (Resolve-Path -LiteralPath $ArchivePath -ErrorAction SilentlyContinue).Path }
if (-not $resolvedArchive -or -not (Test-Path -LiteralPath $resolvedArchive)) {
  Fail "ArchivePath not found: $ArchivePath"
}

$sevenZip = Resolve-SevenZip
if (-not $sevenZip) {
  Fail "7z.exe not found (looked in F:\scoop\apps\7zip\current, `${env:ProgramFiles}\7-Zip, then PATH). Install 7-Zip."
}

$scriptDir = Split-Path -Parent $PSCommandPath
$signWindows = Join-Path $scriptDir "..\ci\sign-windows.ps1"
if (-not (Test-Path -LiteralPath $signWindows)) {
  Fail "sign-windows.ps1 not found at expected path: $signWindows"
}
$signWindows = (Resolve-Path -LiteralPath $signWindows).Path
$verifyPeSignatures = Join-Path $scriptDir "..\ci\verify-pe-signatures.ps1"
if (-not (Test-Path -LiteralPath $verifyPeSignatures)) {
  Fail "verify-pe-signatures.ps1 not found at expected path: $verifyPeSignatures"
}
$verifyPeSignatures = (Resolve-Path -LiteralPath $verifyPeSignatures).Path

if (-not (Test-Path -LiteralPath $OutDir)) {
  New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$resolvedOutDir = (Resolve-Path -LiteralPath $OutDir).Path

$baseName = [System.IO.Path]::GetFileNameWithoutExtension($resolvedArchive)
$outputZip = Join-Path $resolvedOutDir ($baseName + "-signed.zip")
$outputSha = $outputZip + ".sha256"

Info "sign-vendored-payload: input   = $resolvedArchive"
Info "                       output  = $outputZip"
Info "                       7z      = $sevenZip"
if ($AllowUnsigned) { Info "                       MODE    = REHEARSAL (-AllowUnsigned; unsigned PEs tolerated)" }

# --- Extract -------------------------------------------------------------------------------
$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("svp-" + [guid]::NewGuid().ToString("N"))
$extractDir = Join-Path $workRoot "extract"
New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
$previousLedgerPath = $env:JUSTSEARCH_CODESIGN_LEDGER_PATH
$previousLedgerMax = $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES

try {
  Info ""
  Info "Extracting..."
  & $sevenZip x "-o$extractDir" -y -- $resolvedArchive | Out-Null
  if ($LASTEXITCODE -ne 0) { Fail "7z extraction failed (exit=$LASTEXITCODE) for $resolvedArchive" }

  # --- Sign pass ---------------------------------------------------------------------------
  $signed = 0
  $skipped = 0
  $nonPe = 0
  $allFiles = @(Get-ChildItem -LiteralPath $extractDir -Recurse -File)
  $peFiles = @($allFiles | Where-Object { Test-IsPE $_.FullName })
  $nonPe = $allFiles.Count - $peFiles.Count
  $unsignedBefore = @($peFiles | Where-Object { (Get-AuthenticodeSignature -LiteralPath $_.FullName).Status -ne 'Valid' }).Count
  if ($MaxSignatures -lt 1) { Fail "MaxSignatures must be positive." }
  if ($unsignedBefore -gt $MaxSignatures) {
    Fail "Pre-sign MZ census requires $unsignedBefore signature(s), exceeding reviewed maximum $MaxSignatures."
  }
  $ledgerPath = if ($previousLedgerPath) { [System.IO.Path]::GetFullPath($previousLedgerPath) } else { Join-Path $workRoot "signing-ledger.jsonl" }
  $attemptLedgerPath = $ledgerPath + ".attempts.jsonl"
  $alreadySpent = if (Test-Path -LiteralPath $attemptLedgerPath) {
    @(
      Get-Content -LiteralPath $attemptLedgerPath |
        Where-Object { $_ -and $_.Trim() } |
        ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { $_.event -eq "attempt-start" }
    ).Count
  } else { 0 }
  $requiredTotal = $alreadySpent + $unsignedBefore
  if (-not $AllowUnsigned -and $ProviderRemainingSignatures -lt $requiredTotal) {
    Fail "Provider-authoritative remaining budget ($ProviderRemainingSignatures) is below the run total after this census ($requiredTotal)."
  }
  if ($unsignedBefore -gt 0) {
    $env:JUSTSEARCH_CODESIGN_LEDGER_PATH = $ledgerPath
    $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES = [string]$(if ($AllowUnsigned) { $requiredTotal } else { $ProviderRemainingSignatures })
  }
  Info "Pre-sign MZ census: $($peFiles.Count) PE(s), $unsignedBefore require signing, run total $requiredTotal, reviewed per-payload max $MaxSignatures."

  foreach ($f in $peFiles) {
    $rel = $f.FullName.Substring($extractDir.Length).TrimStart('\', '/')

    $sig = Get-AuthenticodeSignature -LiteralPath $f.FullName
    if ($sig.Status -eq 'Valid') {
      Info "  already-signed (skip): $rel"
      $skipped++
      continue
    }

    Info "  signing: $rel  (was: $($sig.Status))"

    # Invoke the existing signer as a child so every credential mode (pfx/store/command) works
    # here for free (its JUSTSEARCH_CODESIGN_* env contract is respected as-set, not overridden).
    # A NON-ZERO child exit is a genuine signtool/credential failure -> fail closed immediately.
    # A ZERO exit is necessary but not sufficient (the child exits 0 when it deliberately SKIPS,
    # e.g. no credentials): the independent Authenticode re-verification below is what collects
    # the full unsigned-and-unsignable list so the operator sees every offender at once.
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $signWindows -BinaryPath $f.FullName
    $childExit = $LASTEXITCODE

    if ($childExit -ne 0 -and -not $AllowUnsigned) {
      Fail "sign-windows.ps1 failed (exit=$childExit) for $rel"
    }

    if ($childExit -eq 0) { $signed++ }
  }

  if (-not $AllowUnsigned) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyPeSignatures -Path $extractDir
    if ($LASTEXITCODE -ne 0) { Fail "Extracted mirror PE verification failed (exit=$LASTEXITCODE): $resolvedArchive" }
  }

  # --- Re-zip preserving internal layout exactly -------------------------------------------
  # 7z 'a' from inside the extract root with '*' recurses and stores paths relative to cwd,
  # reproducing the original archive's internal directory structure.
  if (Test-Path -LiteralPath $outputZip) { Remove-Item -LiteralPath $outputZip -Force }
  Info ""
  Info "Re-zipping..."
  Push-Location -LiteralPath $extractDir
  try {
    & $sevenZip a -tzip -- $outputZip "*" | Out-Null
    $zipExit = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  if ($zipExit -ne 0) { Fail "7z re-zip failed (exit=$zipExit)" }
  if (-not (Test-Path -LiteralPath $outputZip)) { Fail "Re-zip produced no output: $outputZip" }

  if (-not $AllowUnsigned) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyPeSignatures -Path $outputZip -Extract
    if ($LASTEXITCODE -ne 0) { Fail "Repacked mirror PE verification failed (exit=$LASTEXITCODE): $outputZip" }
  } else {
    Info "Post-repack strict PE verification skipped for rehearsal (-AllowUnsigned)."
  }

  # --- SHA256 sidecar (uppercase hex; sha256sum line format: hash + two spaces + basename) ---
  $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputZip).Hash.ToUpperInvariant()
  $outBase = [System.IO.Path]::GetFileName($outputZip)
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($outputSha, "$hash  $outBase`n", $utf8NoBom)
  $ledgerSource = $ledgerPath
  if (Test-Path -LiteralPath $ledgerSource) {
    Copy-Item -LiteralPath $ledgerSource -Destination ($outputZip + ".signing-ledger.jsonl") -Force
  }
  if (Test-Path -LiteralPath $attemptLedgerPath) {
    Copy-Item -LiteralPath $attemptLedgerPath -Destination ($outputZip + ".signing-attempts.jsonl") -Force
  }

  # --- Summary -----------------------------------------------------------------------------
  Info ""
  Info "==================== sign-vendored-payload summary ===================="
  Info ("  signed (newly):          {0}" -f $signed)
  Info ("  skipped (already-signed): {0}" -f $skipped)
  Info ("  non-PE (unmodified):      {0}" -f $nonPe)
  if ($AllowUnsigned) { Info "  verification:            skipped (rehearsal; unsigned PEs tolerated)" }
  Info ("  output:  {0}" -f $outputZip)
  Info ("  sha256:  {0}  {1}" -f $hash, $outBase)
  Info ("  sidecar: {0}" -f $outputSha)
  Info "======================================================================="
}
finally {
  if ($null -eq $previousLedgerPath) { Remove-Item Env:JUSTSEARCH_CODESIGN_LEDGER_PATH -ErrorAction SilentlyContinue }
  else { $env:JUSTSEARCH_CODESIGN_LEDGER_PATH = $previousLedgerPath }
  if ($null -eq $previousLedgerMax) { Remove-Item Env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES -ErrorAction SilentlyContinue }
  else { $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES = $previousLedgerMax }
  if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
