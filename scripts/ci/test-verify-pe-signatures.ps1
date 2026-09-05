#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$verifier = Join-Path $PSScriptRoot "verify-pe-signatures.ps1"
$signer = Join-Path $PSScriptRoot "sign-windows.ps1"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-pe-verifier-test-" + [guid]::NewGuid().ToString("N"))
$cert = $null

function Invoke-ExpectedFailure([string]$Target) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier -Path $Target -AllowUntrusted 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
  } finally {
    $ErrorActionPreference = $previous
  }
}

try {
  $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
  if (-not (Test-Path -LiteralPath $csc -PathType Leaf)) { throw "csc.exe is required for the PE verifier fixture." }
  New-Item -ItemType Directory -Path $work -Force | Out-Null
  $source = Join-Path $work "fixture.cs"
  [System.IO.File]::WriteAllText($source, 'public static class Fixture { public static void Main() {} }', [System.Text.Encoding]::ASCII)
  $signedExe = Join-Path $work "signed.exe"
  $unsignedExe = Join-Path $work "unsigned.exe"
  & $csc /nologo /target:exe /out:$signedExe $source
  if ($LASTEXITCODE -ne 0) { throw "fixture compilation failed" }
  Copy-Item -LiteralPath $signedExe -Destination $unsignedExe -Force

  $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=JustSearch PE Census Test" -CertStoreLocation "Cert:\CurrentUser\My"
  $env:JUSTSEARCH_CODESIGN_MODE = "store"
  $env:JUSTSEARCH_CODESIGN_THUMBPRINT = $cert.Thumbprint
  $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $signer $signedExe
  if ($LASTEXITCODE -ne 0) { throw "fixture signing failed" }
  Remove-Item Env:JUSTSEARCH_CODESIGN_MODE, Env:JUSTSEARCH_CODESIGN_THUMBPRINT, Env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED -ErrorAction SilentlyContinue

  $renamed = Join-Path $work "signed-payload.bin"
  Copy-Item -LiteralPath $signedExe -Destination $renamed -Force
  Remove-Item -LiteralPath $signedExe -Force

  $failed = Invoke-ExpectedFailure $work
  if ($failed.ExitCode -eq 0 -or $failed.Output -notmatch '1 MZ-bearing file') {
    throw "mixed signed/unsigned census did not fail closed as expected: $($failed.Output)"
  }

  Remove-Item -LiteralPath $unsignedExe -Force
  $report = Join-Path $work "report.json"
  & $verifier -Path $work -AllowUntrusted -ReportPath $report
  $parsed = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
  if ($parsed.peCount -ne 1 -or $parsed.rejectedCount -ne 0 -or $parsed.files[0].path -ne 'signed-payload.bin') {
    throw "renamed signed MZ census report was incorrect"
  }

  Write-Host "test-verify-pe-signatures: PASS"
} finally {
  Remove-Item Env:JUSTSEARCH_CODESIGN_MODE, Env:JUSTSEARCH_CODESIGN_THUMBPRINT, Env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED -ErrorAction SilentlyContinue
  if ($cert -and $cert.Thumbprint) {
    Remove-Item -Path ("Cert:\CurrentUser\My\" + $cert.Thumbprint) -Force -ErrorAction SilentlyContinue
  }
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
