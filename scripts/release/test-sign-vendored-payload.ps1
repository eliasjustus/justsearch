#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$producer = Join-Path $PSScriptRoot "sign-vendored-payload.ps1"
$signer = Join-Path (Split-Path $PSScriptRoot -Parent) "ci\sign-windows.ps1"
$verifier = Join-Path (Split-Path $PSScriptRoot -Parent) "ci\verify-pe-signatures.ps1"
$shimReceipt = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) "dist\uninstaller-signing-receipt.json"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-vendored-sign-test-" + [guid]::NewGuid().ToString("N"))
$cert = $null

try {
  $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
  if (-not (Test-Path -LiteralPath $csc -PathType Leaf)) { throw "csc.exe is required." }
  $sevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $sevenZip) {
    $candidate = Join-Path $env:ProgramFiles "7-Zip\7z.exe"
    if (-not (Test-Path -LiteralPath $candidate)) { throw "7z.exe is required." }
    $sevenZipPath = $candidate
  } else {
    $sevenZipPath = $sevenZip.Source
  }

  $input = Join-Path $work "input"
  $output = Join-Path $work "output"
  New-Item -ItemType Directory -Path $input, $output -Force | Out-Null
  $source = Join-Path $work "fixture.cs"
  [System.IO.File]::WriteAllText($source, 'public static class Fixture { public static void Main() {} }', [System.Text.Encoding]::ASCII)
  $alreadySigned = Join-Path $input "vendor-valid.exe"
  $unsigned = Join-Path $input "nested\needs-signing.payload"
  New-Item -ItemType Directory -Path (Split-Path $unsigned -Parent) -Force | Out-Null
  $embeddedSignedSource = (Get-Command pwsh.exe -ErrorAction Stop).Source
  Copy-Item -LiteralPath $embeddedSignedSource -Destination $alreadySigned -Force
  if ((Get-AuthenticodeSignature -LiteralPath $alreadySigned).Status -ne "Valid") {
    throw "The copied pwsh.exe fixture is not a valid embedded-signature fixture."
  }
  & $csc /nologo /target:exe /out:$unsigned $source
  if ($LASTEXITCODE -ne 0) { throw "unsigned fixture compilation failed." }

  $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=JustSearch Vendored Payload Test" -CertStoreLocation "Cert:\CurrentUser\My"
  $env:JUSTSEARCH_CODESIGN_MODE = "store"
  $env:JUSTSEARCH_CODESIGN_THUMBPRINT = $cert.Thumbprint
  $env:JUSTSEARCH_REQUIRE_SIGNING = "true"
  $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"

  $archive = Join-Path $work "mixed.zip"
  Push-Location $input
  try {
    & $sevenZipPath a -tzip -- $archive "*" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "fixture archive creation failed." }
  } finally { Pop-Location }

  $ledger = Join-Path $work "ledger.jsonl"
  $env:JUSTSEARCH_CODESIGN_LEDGER_PATH = $ledger
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $producer -ArchivePath $archive -OutDir $output -MaxSignatures 1 -ProviderRemainingSignatures 1
  if ($LASTEXITCODE -ne 0) { throw "mixed archive producer failed." }

  $produced = Join-Path $output "mixed-signed.zip"
  if (-not (Test-Path -LiteralPath $produced)) { throw "producer emitted no signed archive." }
  $lines = @(Get-Content -LiteralPath $ledger | Where-Object { $_ -and $_.Trim() })
  if ($lines.Count -ne 1) { throw "expected exactly one new signature, found $($lines.Count)." }
  $attempts = @(
    Get-Content -LiteralPath ($ledger + ".attempts.jsonl") |
      Where-Object { $_ -and $_.Trim() } |
      ForEach-Object { $_ | ConvertFrom-Json } |
      Where-Object { $_.event -eq "attempt-start" }
  )
  if ($attempts.Count -ne 1) { throw "expected exactly one reserved provider attempt, found $($attempts.Count)." }
  if (-not (Test-Path -LiteralPath ($produced + ".signing-attempts.jsonl"))) {
    throw "producer emitted no signing-attempt sidecar."
  }
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier -Path $produced -Extract -AllowUntrusted
  if ($LASTEXITCODE -ne 0) { throw "produced archive did not pass strict complete-PE verification." }

  Write-Host "test-sign-vendored-payload: PASS"
} finally {
  Remove-Item Env:JUSTSEARCH_CODESIGN_MODE, Env:JUSTSEARCH_CODESIGN_THUMBPRINT, Env:JUSTSEARCH_REQUIRE_SIGNING, Env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED, Env:JUSTSEARCH_CODESIGN_LEDGER_PATH, Env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES -ErrorAction SilentlyContinue
  if ($cert -and $cert.Thumbprint) {
    Remove-Item -LiteralPath ("Cert:\CurrentUser\My\" + $cert.Thumbprint) -Force -ErrorAction SilentlyContinue
  }
  Remove-Item -LiteralPath $shimReceipt -Force -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
