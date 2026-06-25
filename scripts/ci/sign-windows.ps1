#Requires -Version 5.1
[CmdletBinding()]
param(
  # The file to sign. (Tauri will pass the binary path as %1.)
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$BinaryPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-Bool([string]$Value) {
  if (-not $Value) { return $false }
  switch ($Value.Trim().ToLowerInvariant()) {
    "1" { return $true }
    "true" { return $true }
    "yes" { return $true }
    default { return $false }
  }
}

function Fail([string]$Message) {
  Write-Error $Message
  exit 1
}

function Info([string]$Message) {
  Write-Host $Message
}

$pfxPath = $env:JUSTSEARCH_CODESIGN_PFX_PATH
$requireSigning = To-Bool $env:JUSTSEARCH_REQUIRE_SIGNING
$pfxB64 = $env:JUSTSEARCH_CODESIGN_PFX_B64
$pfxPassword = $env:JUSTSEARCH_CODESIGN_PFX_PASSWORD
$timestampUrl = $env:JUSTSEARCH_CODESIGN_TIMESTAMP_URL

if (-not $BinaryPath) {
  Fail "BinaryPath is required"
}

$resolvedBinary = if ([System.IO.Path]::IsPathRooted($BinaryPath)) { $BinaryPath } else { (Resolve-Path -LiteralPath $BinaryPath).Path }
if (-not (Test-Path -LiteralPath $resolvedBinary)) {
  Fail "Binary not found: $resolvedBinary"
}

function Find-SignTool {
  $cmd = Get-Command "signtool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd -and $cmd.Path) { return $cmd.Path }

  $roots = @()
  if ($env:ProgramFiles -and $env:ProgramFiles.Trim()) {
    $roots += (Join-Path $env:ProgramFiles "Windows Kits\\10\\bin")
    $roots += (Join-Path $env:ProgramFiles "Windows Kits\\11\\bin")
  }
  $pf86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
  if ($pf86 -and $pf86.Trim()) {
    $roots += (Join-Path $pf86 "Windows Kits\\10\\bin")
    $roots += (Join-Path $pf86 "Windows Kits\\11\\bin")
  }

  foreach ($root in ($roots | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $versions = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($v in $versions) {
      $candidate = Join-Path $v.FullName "x64\\signtool.exe"
      if (Test-Path -LiteralPath $candidate) { return $candidate }
      $candidate2 = Join-Path $v.FullName "signtool.exe"
      if (Test-Path -LiteralPath $candidate2) { return $candidate2 }
    }
  }
  return $null
}

$hasPfx = $false
$resolvedPfxPath = $null
if ($pfxPath -and $pfxPath.Trim()) {
  $resolvedPfxPath = if ([System.IO.Path]::IsPathRooted($pfxPath)) { $pfxPath } else { (Resolve-Path -LiteralPath $pfxPath).Path }
  if (-not (Test-Path -LiteralPath $resolvedPfxPath)) {
    Fail "JUSTSEARCH_CODESIGN_PFX_PATH points to a missing file: $resolvedPfxPath"
  }
  $hasPfx = $true
} elseif ($pfxB64 -and $pfxB64.Trim()) {
  $hasPfx = $true
}

if (-not $hasPfx -or -not $pfxPassword -or -not $timestampUrl) {
  $msg = "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_PFX_PATH or JUSTSEARCH_CODESIGN_PFX_B64 / JUSTSEARCH_CODESIGN_PFX_PASSWORD / JUSTSEARCH_CODESIGN_TIMESTAMP_URL)."
  if ($requireSigning) {
    Fail ("Signing is required but inputs are missing. " + $msg)
  }
  Info $msg
  exit 0
}

# Locate signtool (Windows SDK). Prefer PATH but also search Windows Kits default locations.
$signtoolPath = Find-SignTool
if (-not $signtoolPath) {
  $msg = "signtool.exe not found on PATH. Install the Windows SDK (SignTool) or add it to PATH."
  if ($requireSigning) {
    Fail $msg
  }
  Info "Signing skipped for '$resolvedBinary' ($msg)"
  exit 0
}

$tmpPfx = Join-Path -Path $env:TEMP -ChildPath ("justsearch-codesign-" + [guid]::NewGuid().ToString("N") + ".pfx")
try {
  $pfxToUse = $tmpPfx
  if ($resolvedPfxPath) {
    $pfxToUse = $resolvedPfxPath
  } else {
    [byte[]]$bytes = [Convert]::FromBase64String($pfxB64)
    [System.IO.File]::WriteAllBytes($tmpPfx, $bytes)
  }

  Info "Signing: $resolvedBinary"
  & $signtoolPath sign `
    /fd SHA256 `
    /td SHA256 `
    /tr $timestampUrl `
    /f $pfxToUse `
    /p $pfxPassword `
    $resolvedBinary | Out-Null

  if ($LASTEXITCODE -ne 0) {
    Fail "signtool sign failed (exit=$LASTEXITCODE) for $resolvedBinary"
  }

  & $signtoolPath verify /pa /v $resolvedBinary | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Fail "signtool verify failed (exit=$LASTEXITCODE) for $resolvedBinary"
  }

  Info "Signed OK: $resolvedBinary"
} finally {
  # Only delete temp PFX when we created it from base64.
  if (-not $resolvedPfxPath) {
    try { Remove-Item -LiteralPath $tmpPfx -Force -ErrorAction SilentlyContinue } catch { }
  }
}


