#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "install-codesign-tool.ps1"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-codesigntool-test-" + [guid]::NewGuid().ToString("N"))
$fixture = Join-Path $work "fixture\nested"
$archive = Join-Path $work "fixture.zip"
$out = Join-Path $work "out"

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
}

try {
  New-Item -ItemType Directory -Path $fixture -Force | Out-Null
  [System.IO.File]::WriteAllText((Join-Path $fixture "CodeSignTool.bat"), "@echo off`r`n", [System.Text.Encoding]::ASCII)
  [System.IO.File]::WriteAllText((Join-Path $fixture "CodeSignTool.jar"), "fixture", [System.Text.Encoding]::ASCII)
  Compress-Archive -Path (Join-Path $work "fixture\*") -DestinationPath $archive
  $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash

  & $scriptPath -Destination $out -ArchivePath $archive -ExpectedSha256 $sha -DownloadUrl "https://example.invalid/fixture.zip"
  Assert-True (Test-Path -LiteralPath (Join-Path $out "CodeSignTool.bat") -PathType Leaf) "CodeSignTool.bat was not normalized"
  $receipt = Get-Content -Raw -LiteralPath (Join-Path $out "justsearch-provenance.json") | ConvertFrom-Json
  Assert-True ($receipt.verified -eq $true) "provenance receipt is not verified"
  Assert-True ($receipt.archiveSha256 -eq $sha) "provenance receipt digest mismatch"

  $badOut = Join-Path $work "bad-out"
  $previousEap = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $badOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
      -Destination $badOut -ArchivePath $archive -ExpectedSha256 ("0" * 64) 2>&1 | Out-String
    $badExit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousEap
  }
  Assert-True ($badExit -ne 0) "digest mismatch unexpectedly succeeded"
  Assert-True ($badOutput -match "digest mismatch") "digest mismatch did not explain the failure"

  Write-Host "test-install-codesign-tool: PASS"
} finally {
  if (Test-Path -LiteralPath $work) {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
  }
}
