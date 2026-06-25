#Requires -Version 5.1
[CmdletBinding()]
param(
  # Repo root directory (defaults to two levels up from this script).
  [string]$RepoRoot,

  # Require the version to be a strict release SemVer: x.y.z
  [switch]$RequireReleaseSemver,

  # Print the resolved version and what would change, but do not write files.
  [switch]$WhatIf
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
  Write-Error $Message
  exit 1
}

function Resolve-RepoRoot {
  param([string]$RootOverride)
  if ($RootOverride) {
    return (Resolve-Path -LiteralPath $RootOverride).Path
  }
  # Use $PSScriptRoot (stable for scripts; avoids function invocation path quirks).
  if ($PSScriptRoot) {
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\\..")).Path
  }
  $scriptDir = Split-Path -Parent $MyInvocation.PSCommandPath
  return (Resolve-Path -LiteralPath (Join-Path $scriptDir "..\\..")).Path
}

function Read-GradleVersion {
  param([string]$Root)
  $gradleProps = Join-Path $Root "gradle.properties"
  if (-not (Test-Path -LiteralPath $gradleProps)) {
    Fail "gradle.properties not found at $gradleProps"
  }
  $lines = Get-Content -LiteralPath $gradleProps -Encoding UTF8
  foreach ($line in $lines) {
    $trim = $line.Trim()
    if (-not $trim -or $trim.StartsWith("#")) { continue }
    if ($trim -match "^\s*version\s*=\s*(.+)\s*$") {
      $v = $Matches[1].Trim()
      if (-not $v) { break }
      return $v
    }
  }
  Fail "No 'version=' entry found in $gradleProps"
}

function Assert-Version {
  param([string]$Version, [switch]$RequireRelease)
  if (-not $Version -or $Version.Trim().Length -eq 0) {
    Fail "Resolved version is empty."
  }
  # Basic sanity: must start with digits.
  if ($Version -notmatch '^\d+\.\d+\.\d+') {
    Fail "Gradle version '$Version' does not look like SemVer (expected x.y.z...)."
  }
  if ($RequireRelease.IsPresent) {
    # Two-step check: (1) SemVer-shaped, (2) not a project-convention snapshot.
    # Step 1 -- SemVer 2.0.0 release or pre-release shape (MAJOR.MINOR.PATCH[-PRERELEASE]).
    if ($Version -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9]+(\.[A-Za-z0-9]+)*)?$') {
      Fail "Release builds require SemVer x.y.z or x.y.z-PRERELEASE (e.g. 2.0.0, 2.0.0-alpha.1, 2.0.0-rc.2). Got: '$Version'."
    }
    # Step 2 -- reject the -SNAPSHOT convention (legal SemVer per spec, but this
    # project reserves SNAPSHOT to mean "work in progress, not releasable").
    if ($Version -match '(?i)SNAPSHOT') {
      Fail "Release builds cannot contain SNAPSHOT. Bump gradle.properties to a release version first (e.g. 2.0.0-alpha.1). Got: '$Version'."
    }
  }
}

function Update-FileRegex {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Pattern,
    [Parameter(Mandatory = $true)][string]$Replacement,
    [Parameter(Mandatory = $true)][string]$Label,
    [switch]$DryRun
  )
  if (-not (Test-Path -LiteralPath $Path)) {
    Fail "$Label file not found: $Path"
  }
  $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
  $next = [regex]::Replace($raw, $Pattern, $Replacement, [System.Text.RegularExpressions.RegexOptions]::Multiline)
  if ($raw -eq $next) {
    Write-Host "${Label}: already up-to-date ($Path)"
    return $false
  }
  Write-Host "${Label}: updating ($Path)"
  if (-not $DryRun.IsPresent) {
    # PowerShell 5.1's `Set-Content -Encoding UTF8` inserts a UTF-8 BOM,
    # which serde_json (used by Tauri to parse tauri.conf.json) cannot
    # handle -- the build fails with "expected value at line 1 column 1".
    # The cargo_toml crate also rejects BOMs. Use .NET APIs with the
    # no-BOM UTF-8 encoding to write cleanly across all three targets.
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $next, $utf8NoBom)
  }
  return $true
}

$root = Resolve-RepoRoot -RootOverride $RepoRoot
$version = Read-GradleVersion -Root $root
Assert-Version -Version $version -RequireRelease:$RequireReleaseSemver

Write-Host "Gradle canonical version: $version"

$tauriConf = Join-Path $root "modules\\shell\\src-tauri\\tauri.conf.json"
$shellPkg = Join-Path $root "modules\\shell\\package.json"
$cargoToml = Join-Path $root "modules\\shell\\src-tauri\\Cargo.toml"

$changed = $false
$changed = (Update-FileRegex -Path $tauriConf -Label "tauri.conf.json" -Pattern '\"version\"\s*:\s*\"[^\"]+\"' -Replacement ('"version": "' + $version + '"') -DryRun:$WhatIf) -or $changed
$changed = (Update-FileRegex -Path $shellPkg -Label "modules/shell/package.json" -Pattern '\"version\"\s*:\s*\"[^\"]+\"' -Replacement ('"version": "' + $version + '"') -DryRun:$WhatIf) -or $changed
$changed = (Update-FileRegex -Path $cargoToml -Label "Cargo.toml" -Pattern '^(\s*version\s*=\s*\")[^\"]+(\"\s*)$' -Replacement ('${1}' + $version + '${2}') -DryRun:$WhatIf) -or $changed

if ($WhatIf.IsPresent) {
  if ($changed) {
    Write-Host "WhatIf: changes would be applied."
  } else {
    Write-Host "WhatIf: no changes needed."
  }
} else {
  if ($changed) {
    Write-Host "Version sync applied successfully."
  } else {
    Write-Host "No version changes were needed."
  }
}


