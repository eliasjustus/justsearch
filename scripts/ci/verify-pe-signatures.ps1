#Requires -Version 5.1
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Path,

  [switch]$Extract,

  [switch]$IncludeOuter,

  [switch]$AllowUntrusted,

  [string]$ReportPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-Bool([string]$Value) {
  if (-not $Value) { return $false }
  return @("1", "true", "yes") -contains $Value.Trim().ToLowerInvariant()
}

function Resolve-SevenZip {
  $candidates = @(
    'F:\scoop\apps\7zip\current\7z.exe',
    (Join-Path ${env:ProgramFiles} '7-Zip\7z.exe')
  )
  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  $command = Get-Command '7z.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($command -and $command.Path) { return $command.Path }
  return $null
}

function Test-IsMz([string]$FilePath) {
  $stream = [System.IO.File]::OpenRead($FilePath)
  try {
    return $stream.Length -ge 2 -and $stream.ReadByte() -eq 0x4D -and $stream.ReadByte() -eq 0x5A
  } finally {
    $stream.Dispose()
  }
}

function Get-PeSignature([string]$FilePath) {
  $extension = [System.IO.Path]::GetExtension($FilePath).ToLowerInvariant()
  $nativeExtensions = @('.exe', '.dll', '.sys', '.ocx', '.msi', '.msix', '.appx', '.cab', '.cat', '.ps1')
  if ($nativeExtensions -contains $extension) {
    return Get-AuthenticodeSignature -LiteralPath $FilePath
  }

  $shim = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-pe-verify-" + [guid]::NewGuid().ToString("N") + ".exe")
  try {
    Copy-Item -LiteralPath $FilePath -Destination $shim -Force
    return Get-AuthenticodeSignature -LiteralPath $shim
  } finally {
    Remove-Item -LiteralPath $shim -Force -ErrorAction SilentlyContinue
  }
}

$allow = $AllowUntrusted.IsPresent -or (To-Bool $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED)
$resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
$workRoot = $null
$scanRoot = $resolved
$outer = $null

try {
  if ($Extract.IsPresent) {
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
      throw "-Extract requires an archive/installer file: $resolved"
    }
    $sevenZip = Resolve-SevenZip
    if (-not $sevenZip) { throw "7z.exe is required to inspect an archive or NSIS installer." }
    $workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-pe-census-" + [guid]::NewGuid().ToString("N"))
    $scanRoot = Join-Path $workRoot "extract"
    New-Item -ItemType Directory -Path $scanRoot -Force | Out-Null
    & $sevenZip x "-o$scanRoot" -y -- $resolved | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "7z extraction failed (exit=$LASTEXITCODE): $resolved" }
    if ($IncludeOuter.IsPresent -and (Test-IsMz $resolved)) { $outer = Get-Item -LiteralPath $resolved }
  }

  $files = if (Test-Path -LiteralPath $scanRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $scanRoot -Recurse -File)
  } else {
    @(Get-Item -LiteralPath $scanRoot)
  }
  if ($outer) { $files += $outer }

  $records = New-Object System.Collections.ArrayList
  foreach ($file in $files) {
    if (-not (Test-IsMz $file.FullName)) { continue }
    $signature = Get-PeSignature $file.FullName
    $valid = $signature.Status -eq 'Valid'
    $accepted = $valid -or ($allow -and $signature.SignerCertificate -and ([string]$signature.Status -ne 'NotSigned'))
    $relative = if ($outer -and $file.FullName -eq $outer.FullName) {
      '<outer>/' + $file.Name
    } elseif (Test-Path -LiteralPath $scanRoot -PathType Container) {
      $file.FullName.Substring($scanRoot.Length).TrimStart('\', '/')
    } else {
      $file.Name
    }
    $records.Add([pscustomobject]@{
      path = $relative
      sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToUpperInvariant()
      status = [string]$signature.Status
      subject = if ($signature.SignerCertificate) { [string]$signature.SignerCertificate.Subject } else { $null }
      accepted = [bool]$accepted
    }) | Out-Null
  }

  if ($records.Count -eq 0) { throw "PE census found no MZ-bearing files under $resolved" }
  $rejected = @($records | Where-Object { -not $_.accepted })

  $report = [ordered]@{
    schemaVersion = 1
    source = $resolved
    extracted = $Extract.IsPresent
    allowUntrusted = $allow
    peCount = $records.Count
    acceptedCount = $records.Count - $rejected.Count
    rejectedCount = $rejected.Count
    files = @($records)
  }
  $json = ($report | ConvertTo-Json -Depth 6)
  if ($ReportPath) {
    $reportFull = [System.IO.Path]::GetFullPath($ReportPath)
    $reportDir = Split-Path -Parent $reportFull
    if (-not (Test-Path -LiteralPath $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
    [System.IO.File]::WriteAllText($reportFull, ($json + "`n"), (New-Object System.Text.UTF8Encoding($false)))
  }

  Write-Host "PE signature census: $($records.Count) MZ file(s), $($rejected.Count) rejected."
  foreach ($record in $records) {
    Write-Host ("  [{0}] {1} ({2})" -f $(if ($record.accepted) { 'OK' } else { 'REJECT' }), $record.path, $record.status)
  }
  if ($rejected.Count -gt 0) {
    throw "Fail-closed: $($rejected.Count) MZ-bearing file(s) lack an accepted Authenticode signature."
  }
} finally {
  if ($workRoot -and (Test-Path -LiteralPath $workRoot)) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
