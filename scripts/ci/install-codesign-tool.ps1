#Requires -Version 5.1
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Destination,

  [string]$ArchivePath,

  [string]$DownloadUrl = "https://www.ssl.com/download/codesigntool-for-windows/",

  [string]$ExpectedSha256 = "317D429BE3AA12A5F2C1FFDD575EAB0CB0CE5E2408AB0056BCDCAAB29875F73D"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$pinnedVersion = "1.3.3"
$expected = $ExpectedSha256.Trim().ToUpperInvariant()
if ($expected -notmatch '^[0-9A-F]{64}$') {
  throw "ExpectedSha256 must be exactly 64 hexadecimal characters."
}
$resolvedDestination = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $resolvedDestination) {
  $existing = @(Get-ChildItem -LiteralPath $resolvedDestination -Force -ErrorAction Stop)
  if ($existing.Count -gt 0) {
    throw "CodeSignTool destination must be absent or empty: $resolvedDestination"
  }
} else {
  New-Item -ItemType Directory -Path $resolvedDestination -Force | Out-Null
}

$downloadedArchive = $false
$archive = $ArchivePath
if ([string]::IsNullOrWhiteSpace($archive)) {
  $archive = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-codesigntool-" + [guid]::NewGuid().ToString("N") + ".zip")
  Invoke-WebRequest -Uri $DownloadUrl -OutFile $archive
  $downloadedArchive = $true
}

try {
  $resolvedArchive = (Resolve-Path -LiteralPath $archive -ErrorAction Stop).Path
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedArchive).Hash.ToUpperInvariant()
  if ($actual -ne $expected) {
    throw "CodeSignTool archive digest mismatch: expected $expected, got $actual. Refusing to execute mutable vendor bytes."
  }

  Expand-Archive -LiteralPath $resolvedArchive -DestinationPath $resolvedDestination -Force
  $bats = @(Get-ChildItem -LiteralPath $resolvedDestination -Recurse -File -Filter "CodeSignTool.bat")
  if ($bats.Count -ne 1) {
    throw "Expected exactly one CodeSignTool.bat after extraction; found $($bats.Count)."
  }

  $bat = $bats[0]
  if (-not [string]::Equals($bat.DirectoryName, $resolvedDestination, [System.StringComparison]::OrdinalIgnoreCase)) {
    $nestedFiles = @(Get-ChildItem -LiteralPath $bat.DirectoryName -Force)
    foreach ($item in $nestedFiles) {
      Move-Item -LiteralPath $item.FullName -Destination $resolvedDestination -Force
    }
  }

  $rootBat = Join-Path $resolvedDestination "CodeSignTool.bat"
  if (-not (Test-Path -LiteralPath $rootBat -PathType Leaf)) {
    throw "CodeSignTool.bat was not normalized to the destination root."
  }

  $receipt = [ordered]@{
    schemaVersion = 1
    tool = "SSL.com CodeSignTool"
    version = $pinnedVersion
    sourceUrl = $DownloadUrl
    archiveSha256 = $actual
    verified = $true
  }
  $receiptPath = Join-Path $resolvedDestination "justsearch-provenance.json"
  [System.IO.File]::WriteAllText(
    $receiptPath,
    (($receipt | ConvertTo-Json -Depth 3) + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))

  Write-Host "CodeSignTool $pinnedVersion verified: $actual"
  Write-Host "CodeSignTool ready at $rootBat"
} finally {
  if ($downloadedArchive -and (Test-Path -LiteralPath $archive)) {
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
  }
}
