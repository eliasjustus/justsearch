#Requires -Version 5.1
[CmdletBinding()]
param()

# End-to-end local rehearsal of scripts/ci/sign-windows.ps1 needing NO real cert.
# Creates a throwaway self-signed code-signing cert, exercises every credential mode
# (pfx / store / command) plus the skip / fail-closed paths, and always cleans up.
# Requires the Windows SDK (signtool). SAC does not block signtool sign/verify.
# (Tempdoc 760 Phase 2 item 3 rehearsal path.)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$signScript = Join-Path -Path $scriptDir -ChildPath "sign-windows.ps1"
if (-not (Test-Path -LiteralPath $signScript)) { throw "sign-windows.ps1 not found next to this test: $signScript" }
# Same location sign-windows.ps1 resolves for its uninstaller signing receipt (case 7).
$receiptPath = Join-Path -Path (Split-Path -Parent (Split-Path -Parent $scriptDir)) -ChildPath "dist\uninstaller-signing-receipt.json"

$signEnvNames = @(
  "JUSTSEARCH_CODESIGN_MODE",
  "JUSTSEARCH_CODESIGN_PFX_PATH",
  "JUSTSEARCH_CODESIGN_PFX_B64",
  "JUSTSEARCH_CODESIGN_PFX_PASSWORD",
  "JUSTSEARCH_CODESIGN_TIMESTAMP_URL",
  "JUSTSEARCH_CODESIGN_THUMBPRINT",
  "JUSTSEARCH_CODESIGN_STORE",
  "JUSTSEARCH_CODESIGN_COMMAND",
  "JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED",
  "JUSTSEARCH_REQUIRE_SIGNING",
  "JUSTSEARCH_CODESIGN_LEDGER_PATH",
  "JUSTSEARCH_CODESIGN_MAX_SIGNATURES",
  "TAURI_SIGNING_PRIVATE_KEY",
  "TAURI_SIGNING_PRIVATE_KEY_PASSWORD",
  "JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH",
  "METADATA_PRIVATE_KEY_PEM",
  "GITHUB_TOKEN",
  "GH_TOKEN"
)

function Clear-SignEnv {
  foreach ($n in $signEnvNames) {
    if (Test-Path -Path ("Env:" + $n)) { Remove-Item -Path ("Env:" + $n) -ErrorAction SilentlyContinue }
  }
}

# Run sign-windows.ps1 as a child process with a controlled env; return exit code + output.
function Invoke-SignCase {
  param([hashtable]$Env, [string]$TargetFile)
  Clear-SignEnv
  foreach ($k in $Env.Keys) { Set-Item -Path ("Env:" + $k) -Value $Env[$k] }
  # Local Continue: fail-closed cases make the child write to stderr, and 2>&1-merging a native
  # exe's stderr under ErrorActionPreference=Stop would raise a terminating NativeCommandError in
  # THIS process. Scope it to Continue so we can capture the child's output + real exit code.
  $ErrorActionPreference = "Continue"
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $signScript $TargetFile 2>&1 | Out-String
  $code = $LASTEXITCODE
  Clear-SignEnv
  return [pscustomobject]@{ ExitCode = $code; Output = $out }
}

# Assert against OUR throwaway cert's subject: a signing case must end up with our subject as the
# signer, proving the pipeline actually applied our cert. A system exe like where.exe is catalog-
# signed -- embedding a signature doesn't change its Authenticode hash, so Get-AuthenticodeSignature
# keeps reporting the OS catalog's Microsoft signer and masks our signature (verified: false-green).
# So the primary target is a freshly csc-compiled PE (never catalogued); where.exe is a fallback that
# can only prove "a signature is present", noted per-case.
$OurCertMarker = "JustSearch Signing Rehearsal"
function Get-SignerSubject([string]$Path) {
  $sig = Get-AuthenticodeSignature -FilePath $Path
  if ($sig.SignerCertificate) { return [string]$sig.SignerCertificate.Subject }
  return ("(none:" + [string]$sig.Status + ")")
}
function Test-SignedByOurCert([string]$Path) {
  return ((Get-SignerSubject $Path) -like ("*" + $OurCertMarker + "*"))
}
function Test-SignaturePresent([string]$Path) {
  $sig = Get-AuthenticodeSignature -FilePath $Path
  return (($sig.SignerCertificate -ne $null) -and ([string]$sig.Status -ne "NotSigned"))
}

# csc.exe (.NET Framework, present on all Win10/11) compiles a pristine, un-catalogued PE so our
# signature is observable. Falls back to where.exe (catalog-masked) only if csc is absent.
$csc = Join-Path -Path $env:WINDIR -ChildPath "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$haveCsc = [System.IO.File]::Exists($csc)

# A signing case passes when our cert is the signer (rigorous, csc target) or -- on the where.exe
# fallback -- when any signature is present (weaker; note appended to the case detail).
function Test-CaseSigned([string]$Path) {
  if ($haveCsc) { return (Test-SignedByOurCert $Path) }
  return (Test-SignaturePresent $Path)
}
function Signed-Detail([string]$Path) {
  if ($haveCsc) { return ("ourCert=" + (Test-SignedByOurCert $Path)) }
  return ("signaturePresent=" + (Test-SignaturePresent $Path) + " [where.exe fallback: cannot confirm OUR cert]")
}

# Locate signtool so we can report cleanly if the SDK is absent.
function Find-SignToolLocal {
  $cmd = Get-Command "signtool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd -and $cmd.Path) { return $cmd.Path }
  $roots = @()
  if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles "Windows Kits\10\bin"); $roots += (Join-Path $env:ProgramFiles "Windows Kits\11\bin") }
  $pf86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
  if ($pf86) { $roots += (Join-Path $pf86 "Windows Kits\10\bin"); $roots += (Join-Path $pf86 "Windows Kits\11\bin") }
  foreach ($root in ($roots | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    foreach ($v in (Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
      $c = Join-Path $v.FullName "x64\signtool.exe"
      if (Test-Path -LiteralPath $c) { return $c }
      $c2 = Join-Path $v.FullName "signtool.exe"
      if (Test-Path -LiteralPath $c2) { return $c2 }
    }
  }
  return $null
}

$tmpDir = Join-Path -Path $env:TEMP -ChildPath ("justsearch-sign-rehearsal-" + [guid]::NewGuid().ToString("N"))
$cert = $null
$results = New-Object System.Collections.ArrayList
$overallOk = $true

function Record([string]$Name, [bool]$Ok, [string]$Detail) {
  $script:results.Add([pscustomobject]@{ Case = $Name; Result = $(if ($Ok) { "PASS" } else { "FAIL" }); Detail = $Detail }) | Out-Null
  if (-not $Ok) { $script:overallOk = $false }
  $tag = if ($Ok) { "PASS" } else { "FAIL" }
  Write-Host ("[{0}] {1} - {2}" -f $tag, $Name, $Detail)
}

try {
  New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

  $signtool = Find-SignToolLocal
  if (-not $signtool) {
    Write-Warning "signtool.exe NOT found (Windows SDK absent). pfx/store/command signing cases cannot be exercised on this machine; only the skip / fail-closed cases will run."
  } else {
    Write-Host "Using signtool: $signtool"
  }

  # --- Create throwaway self-signed code-signing cert + export PFX ---
  $pass = "RehearsalPass123"   # ASCII-only, no cmd-special chars (used inside the command template)
  $securePass = ConvertTo-SecureString -String $pass -Force -AsPlainText
  $cert = New-SelfSignedCertificate -Type CodeSigningCert `
    -Subject "CN=JustSearch Signing Rehearsal (throwaway)" `
    -CertStoreLocation "Cert:\CurrentUser\My" `
    -KeyExportPolicy Exportable -KeyUsage DigitalSignature -KeySpec Signature
  $thumb = $cert.Thumbprint
  $pfxPath = Join-Path -Path $tmpDir -ChildPath "rehearsal.pfx"
  Export-PfxCertificate -Cert ("Cert:\CurrentUser\My\" + $thumb) -FilePath $pfxPath -Password $securePass | Out-Null
  Write-Host ("Created self-signed cert thumbprint={0}; PFX={1}" -f $thumb, $pfxPath)

  $timestampUrl = "http://timestamp.digicert.com"

  $script:targetCounter = 0
  function New-Target([string]$Label) {
    $script:targetCounter++
    $t = Join-Path -Path $tmpDir -ChildPath ("target-" + $Label + ".exe")
    if ($haveCsc) {
      $src = Join-Path -Path $tmpDir -ChildPath ("src-" + $Label + ".cs")
      [System.IO.File]::WriteAllText($src, ("public class P{public static void Main(){System.Console.WriteLine(" + $script:targetCounter + ");}}"))
      & $csc /nologo ("/out:" + $t) $src | Out-Null
      if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $t)) { throw ("csc failed to build rehearsal target '" + $Label + "'") }
    } else {
      Copy-Item -LiteralPath (Join-Path $env:SystemRoot "System32\where.exe") -Destination $t -Force
    }
    return $t
  }

  # --- Case 1: pfx mode + ALLOW_UNTRUSTED => exit 0, file signed ---
  if ($signtool) {
    $t = New-Target "pfx"
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "pfx"
      JUSTSEARCH_CODESIGN_PFX_PATH = $pfxPath
      JUSTSEARCH_CODESIGN_PFX_PASSWORD = $pass
      JUSTSEARCH_CODESIGN_TIMESTAMP_URL = $timestampUrl
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
    } $t
    Write-Host $r.Output
    Record "1. pfx + ALLOW_UNTRUSTED" (($r.ExitCode -eq 0) -and (Test-CaseSigned $t)) ("exit=" + $r.ExitCode + " " + (Signed-Detail $t))
  } else {
    Record "1. pfx + ALLOW_UNTRUSTED" $true "SKIPPED (no signtool)"
  }

  # --- Case 2: store mode (thumbprint) + ALLOW_UNTRUSTED => exit 0, signed ---
  if ($signtool) {
    $t = New-Target "store"
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "store"
      JUSTSEARCH_CODESIGN_THUMBPRINT = $thumb
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
    } $t
    Write-Host $r.Output
    Record "2. store + ALLOW_UNTRUSTED" (($r.ExitCode -eq 0) -and (Test-CaseSigned $t)) ("exit=" + $r.ExitCode + " " + (Signed-Detail $t))
  } else {
    Record "2. store + ALLOW_UNTRUSTED" $true "SKIPPED (no signtool)"
  }

  # --- Case 3: command/vendor mode rejects rehearsal trust and a missing spend ledger pre-invocation ---
  $t = New-Target "command-admission"
  $commandMarker = Join-Path $tmpDir "forbidden-command-invocations.txt"
  $guard = Join-Path $tmpDir "forbidden-command-signer.cmd"
  [System.IO.File]::WriteAllText($guard, @"
@echo off
echo invoked>>"%~1"
exit /b 0
"@, [System.Text.Encoding]::ASCII)
  $template = '"' + $guard + '" "' + $commandMarker + '" "{file}"'
  $untrustedCommand = Invoke-SignCase @{
    JUSTSEARCH_CODESIGN_MODE = "command"
    JUSTSEARCH_CODESIGN_COMMAND = $template
    JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
  } $t
  $unbudgetedCommand = Invoke-SignCase @{
    JUSTSEARCH_CODESIGN_MODE = "command"
    JUSTSEARCH_CODESIGN_COMMAND = $template
  } $t
  Write-Host $untrustedCommand.Output
  Write-Host $unbudgetedCommand.Output
  $commandAdmissionOk = ($untrustedCommand.ExitCode -eq 1) -and
    ($untrustedCommand.Output -match "JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED") -and
    ($unbudgetedCommand.ExitCode -eq 1) -and
    ($unbudgetedCommand.Output -match "JUSTSEARCH_CODESIGN_LEDGER_PATH") -and
    (-not (Test-Path -LiteralPath $commandMarker))
  Record "3. command trust + budget admission" $commandAdmissionOk `
    ("untrustedExit=" + $untrustedCommand.ExitCode + " unbudgetedExit=" + $unbudgetedCommand.ExitCode + " vendorInvoked=" + (Test-Path -LiteralPath $commandMarker))

  # --- Case 4: REQUIRE_SIGNING=true + no inputs => exit 1 (fail-closed) ---
  $t = New-Target "require"
  $r = Invoke-SignCase @{ JUSTSEARCH_REQUIRE_SIGNING = "true" } $t
  Write-Host $r.Output
  Record "4. REQUIRE + no inputs" ($r.ExitCode -eq 1) ("exit=" + $r.ExitCode + " (expected 1)")

  # --- Case 5: no inputs, no REQUIRE => exit 0, "skipped" message ---
  $t = New-Target "skip"
  $r = Invoke-SignCase @{} $t
  Write-Host $r.Output
  $skipMsgOk = ($r.Output -match "skipped")
  $notOurs = -not (Test-SignedByOurCert $t)   # skip must not have re-signed with our cert
  Record "5. no inputs, no REQUIRE" (($r.ExitCode -eq 0) -and $skipMsgOk -and $notOurs) ("exit=" + $r.ExitCode + " skippedMsg=" + $skipMsgOk + " notOurCert=" + $notOurs)

  # --- Case 6: real public timestamp (DigiCert) with retry, tolerant of network failure ---
  if ($signtool) {
    $t = New-Target "timestamp"
    $tsOutcome = "unknown"
    $tsExit = -1
    for ($attempt = 1; $attempt -le 2; $attempt++) {
      $r = Invoke-SignCase @{
        JUSTSEARCH_CODESIGN_MODE = "pfx"
        JUSTSEARCH_CODESIGN_PFX_PATH = $pfxPath
        JUSTSEARCH_CODESIGN_PFX_PASSWORD = $pass
        JUSTSEARCH_CODESIGN_TIMESTAMP_URL = $timestampUrl
        JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
      } $t
      $tsExit = $r.ExitCode
      Write-Host ("  [timestamp attempt " + $attempt + "] exit=" + $tsExit)
      if ($tsExit -eq 0) { break }
    }
    if ($tsExit -eq 0) {
      $sig = Get-AuthenticodeSignature -FilePath $t
      $hasTs = ($sig.TimeStamperCertificate -ne $null)
      $tsOutcome = if ($hasTs) { "timestamped-ok (countersignature present)" } else { "signed-ok (no countersignature detected)" }
      Write-Host $r.Output
      Record "6. timestamp (DigiCert)" (Test-CaseSigned $t) ($tsOutcome + " " + (Signed-Detail $t))
    } else {
      # Timestamp server unreachable after retry: fall back to a no-timestamp signing variant
      # (store mode omits /tr) to prove signing still works offline, and record which happened.
      Write-Host "  Timestamp server unreachable after retry; falling back to no-timestamp (store) variant."
      $r = Invoke-SignCase @{
        JUSTSEARCH_CODESIGN_MODE = "store"
        JUSTSEARCH_CODESIGN_THUMBPRINT = $thumb
        JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
      } $t
      Write-Host $r.Output
      $tsOutcome = "missing-timestamp variant (timestamp server unreachable; signed WITHOUT countersignature)"
      Record "6. timestamp (DigiCert)" (($r.ExitCode -eq 0) -and (Test-CaseSigned $t)) ($tsOutcome + " " + (Signed-Detail $t))
    }
  } else {
    Record "6. timestamp (DigiCert)" $true "SKIPPED (no signtool)"
  }

  # --- Case 7: non-signable extension (the NSIS uninstaller shape) => shim path + receipt ---
  # Reproduces `!uninstfinalize` handing over `...\nstXXXX.tmp`: the script must sign an .exe-named
  # shim, write the signed bytes back over the .tmp, leave no shim behind, and drop the signing
  # receipt package-installer-win.ps1 asserts on (round-16 F3 follow-up).
  if ($signtool) {
    $src = New-Target "uninst"
    $tmpTarget = Join-Path -Path $tmpDir -ChildPath "uninstaller-nst1234.tmp"
    Copy-Item -LiteralPath $src -Destination $tmpTarget -Force
    if (Test-Path -LiteralPath $receiptPath) { Remove-Item -LiteralPath $receiptPath -Force }
    $shimsBefore = @(Get-ChildItem -LiteralPath $env:TEMP -Filter "justsearch-codesign-shim-*.exe" -ErrorAction SilentlyContinue).Count
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "pfx"
      JUSTSEARCH_CODESIGN_PFX_PATH = $pfxPath
      JUSTSEARCH_CODESIGN_PFX_PASSWORD = $pass
      JUSTSEARCH_CODESIGN_TIMESTAMP_URL = $timestampUrl
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
    } $tmpTarget
    Write-Host $r.Output
    # Get-AuthenticodeSignature dispatches on extension too, so check the written-back bytes
    # through an .exe-named copy.
    $writtenBack = Join-Path -Path $tmpDir -ChildPath "uninstaller-writtenback.exe"
    Copy-Item -LiteralPath $tmpTarget -Destination $writtenBack -Force
    $shimsAfter = @(Get-ChildItem -LiteralPath $env:TEMP -Filter "justsearch-codesign-shim-*.exe" -ErrorAction SilentlyContinue).Count
    $receiptOk = $false
    $receiptDetail = "receipt=missing"
    if (Test-Path -LiteralPath $receiptPath) {
      $receiptJson = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
      $receiptOk = ($receiptJson.verified -eq $true) -and ([string]$receiptJson.target -eq $tmpTarget) -and
        (-not [string]::IsNullOrWhiteSpace([string]$receiptJson.signedAtUtc))
      $receiptDetail = "receipt verified=" + $receiptJson.verified + " targetMatch=" + ([string]$receiptJson.target -eq $tmpTarget)
      Remove-Item -LiteralPath $receiptPath -Force
    }
    $noShimLeft = ($shimsAfter -le $shimsBefore)
    Record "7. extension shim (.tmp) + receipt" `
      (($r.ExitCode -eq 0) -and (Test-CaseSigned $writtenBack) -and $receiptOk -and $noShimLeft) `
      ("exit=" + $r.ExitCode + " " + (Signed-Detail $writtenBack) + " " + $receiptDetail + " noShimLeft=" + $noShimLeft)
  } else {
    Record "7. extension shim (.tmp) + receipt" $true "SKIPPED (no signtool)"
  }

  # --- Case 8: terminating error after the shim exists => trap path still cleans the shim up ---
  # A relative, missing PFX path makes Resolve-Path throw, which ONLY the top-of-file trap sees
  # (no Fail call runs). Before the trap called Remove-ExtensionShim, this leaked a full copy of
  # the binary into TEMP on every such failure.
  $t = New-Target "trap"
  $tmpTrapTarget = Join-Path -Path $tmpDir -ChildPath "trap-nst9999.tmp"
  Copy-Item -LiteralPath $t -Destination $tmpTrapTarget -Force
  $shimsBeforeTrap = @(Get-ChildItem -LiteralPath $env:TEMP -Filter "justsearch-codesign-shim-*.exe" -ErrorAction SilentlyContinue).Count
  $r = Invoke-SignCase @{
    JUSTSEARCH_CODESIGN_MODE = "pfx"
    JUSTSEARCH_CODESIGN_PFX_PATH = "no-such-rehearsal-file.pfx"
    JUSTSEARCH_CODESIGN_PFX_PASSWORD = "unused"
    JUSTSEARCH_CODESIGN_TIMESTAMP_URL = $timestampUrl
  } $tmpTrapTarget
  $shimsAfterTrap = @(Get-ChildItem -LiteralPath $env:TEMP -Filter "justsearch-codesign-shim-*.exe" -ErrorAction SilentlyContinue).Count
  $trapNoShim = ($shimsAfterTrap -le $shimsBeforeTrap)
  Record "8. trap path cleans shim" (($r.ExitCode -eq 1) -and $trapNoShim) `
    ("exit=" + $r.ExitCode + " (expected 1) shimsBefore=" + $shimsBeforeTrap + " shimsAfter=" + $shimsAfterTrap)

  # --- Cases 9-10: a verified signature appends once; the next target is refused pre-invocation ---
  if ($signtool) {
    $ledgerPath = Join-Path $tmpDir "signing-ledger.jsonl"
    $first = New-Target "ledger-first"
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "store"
      JUSTSEARCH_CODESIGN_THUMBPRINT = $thumb
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
      JUSTSEARCH_CODESIGN_LEDGER_PATH = $ledgerPath
      JUSTSEARCH_CODESIGN_MAX_SIGNATURES = "1"
    } $first
    Write-Host $r.Output
    [string[]]$ledgerLines = if (Test-Path -LiteralPath $ledgerPath) { @(Get-Content -LiteralPath $ledgerPath | Where-Object { $_ -and $_.Trim() }) } else { @() }
    [string[]]$attemptLines = if (Test-Path -LiteralPath ($ledgerPath + ".attempts.jsonl")) { @(Get-Content -LiteralPath ($ledgerPath + ".attempts.jsonl") | Where-Object { $_ -and $_.Trim() }) } else { @() }
    $attemptEvents = @($attemptLines | ForEach-Object { $_ | ConvertFrom-Json })
    $attemptStarts = @($attemptEvents | Where-Object { $_.event -eq "attempt-start" })
    $attemptFinishes = @($attemptEvents | Where-Object { $_.event -eq "attempt-finish" })
    $ledgerRecord = if ($ledgerLines.Count -eq 1) { $ledgerLines[0] | ConvertFrom-Json } else { $null }
    $ledgerOk = ($r.ExitCode -eq 0) -and (Test-CaseSigned $first) -and ($ledgerLines.Count -eq 1) -and
      $ledgerRecord -and ($ledgerRecord.verified -eq $true) -and ($ledgerRecord.ordinal -eq 1) -and
      ($ledgerRecord.signerMode -eq "store") -and ($ledgerRecord.attemptOrdinal -eq 1) -and
      ($ledgerRecord.sha256 -eq (Get-FileHash -Algorithm SHA256 -LiteralPath $first).Hash) -and
      ($attemptStarts.Count -eq 1) -and ($attemptFinishes.Count -eq 1) -and ($attemptFinishes[0].outcome -eq "vendor-exit-zero")
    Record "9. verified ledger append" $ledgerOk ("exit=" + $r.ExitCode + " verified=" + $ledgerLines.Count + " attempts=" + $attemptStarts.Count)

    $second = New-Target "ledger-cap"
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "store"
      JUSTSEARCH_CODESIGN_THUMBPRINT = $thumb
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
      JUSTSEARCH_CODESIGN_LEDGER_PATH = $ledgerPath
      JUSTSEARCH_CODESIGN_MAX_SIGNATURES = "1"
    } $second
    Write-Host $r.Output
    [string[]]$capLines = @(Get-Content -LiteralPath $ledgerPath | Where-Object { $_ -and $_.Trim() })
    $capAttemptStarts = @((Get-Content -LiteralPath ($ledgerPath + ".attempts.jsonl") | ForEach-Object { $_ | ConvertFrom-Json }) | Where-Object { $_.event -eq "attempt-start" })
    $capOk = ($r.ExitCode -eq 1) -and ($r.Output -match "Signing ceiling\s+reached before vendor") -and
      ($capLines.Count -eq 1) -and ($capAttemptStarts.Count -eq 1) -and (-not (Test-SignedByOurCert $second))
    Record "10. ledger ceiling pre-invocation" $capOk ("exit=" + $r.ExitCode + " verified=" + $capLines.Count + " attempts=" + $capAttemptStarts.Count + " unsigned=" + (-not (Test-SignedByOurCert $second)))
  } else {
    Record "9. verified ledger append" $true "SKIPPED (no signtool)"
    Record "10. ledger ceiling pre-invocation" $true "SKIPPED (no signtool)"
  }

  # --- Cases 11-12: a failed vendor invocation consumes its durable slot; retry is refused. ---
  $failedLedgerPath = Join-Path $tmpDir "failed-signing-ledger.jsonl"
  $invocationMarker = Join-Path $tmpDir "failed-vendor-invocations.txt"
  $failingSigner = Join-Path $tmpDir "always-fail-signer.cmd"
  [System.IO.File]::WriteAllText($failingSigner, @"
@echo off
if defined TAURI_SIGNING_PRIVATE_KEY exit /b 90
if defined TAURI_SIGNING_PRIVATE_KEY_PASSWORD exit /b 91
if defined JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH exit /b 92
if defined GITHUB_TOKEN exit /b 93
echo invoked>>"%~1"
exit /b 42
"@, [System.Text.Encoding]::ASCII)
  $failingTemplate = '"' + $failingSigner + '" "' + $invocationMarker + '" "{file}"'
  $failedFirst = New-Target "failed-ledger-first"
  $r = Invoke-SignCase @{
    JUSTSEARCH_CODESIGN_MODE = "command"
    JUSTSEARCH_CODESIGN_COMMAND = $failingTemplate
    JUSTSEARCH_REQUIRE_SIGNING = "true"
    JUSTSEARCH_CODESIGN_LEDGER_PATH = $failedLedgerPath
    JUSTSEARCH_CODESIGN_MAX_SIGNATURES = "1"
    TAURI_SIGNING_PRIVATE_KEY = "must-not-reach-vendor"
    TAURI_SIGNING_PRIVATE_KEY_PASSWORD = "must-not-reach-vendor"
    JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH = "must-not-reach-vendor"
    GITHUB_TOKEN = "must-not-reach-vendor"
  } $failedFirst
  Write-Host $r.Output
  $failedEvents = @((Get-Content -LiteralPath ($failedLedgerPath + ".attempts.jsonl") | ForEach-Object { $_ | ConvertFrom-Json }))
  $failedStarts = @($failedEvents | Where-Object { $_.event -eq "attempt-start" })
  $failedFinishes = @($failedEvents | Where-Object { $_.event -eq "attempt-finish" })
  $vendorCalls = if (Test-Path -LiteralPath $invocationMarker) { @(Get-Content -LiteralPath $invocationMarker).Count } else { 0 }
  $failedReservationOk = ($r.ExitCode -eq 1) -and ($failedStarts.Count -eq 1) -and
    ($failedFinishes.Count -eq 1) -and ($failedFinishes[0].outcome -eq "vendor-failed") -and
    ($failedFinishes[0].exitCode -eq 42) -and ($vendorCalls -eq 1) -and (-not (Test-Path -LiteralPath $failedLedgerPath))
  Record "11. failed vendor consumes attempt" $failedReservationOk `
    ("exit=" + $r.ExitCode + " attempts=" + $failedStarts.Count + " vendorCalls=" + $vendorCalls)

  $failedSecond = New-Target "failed-ledger-cap"
  $r = Invoke-SignCase @{
    JUSTSEARCH_CODESIGN_MODE = "command"
    JUSTSEARCH_CODESIGN_COMMAND = $failingTemplate
    JUSTSEARCH_REQUIRE_SIGNING = "true"
    JUSTSEARCH_CODESIGN_LEDGER_PATH = $failedLedgerPath
    JUSTSEARCH_CODESIGN_MAX_SIGNATURES = "1"
  } $failedSecond
  Write-Host $r.Output
  $retryEvents = @((Get-Content -LiteralPath ($failedLedgerPath + ".attempts.jsonl") | ForEach-Object { $_ | ConvertFrom-Json }))
  $retryStarts = @($retryEvents | Where-Object { $_.event -eq "attempt-start" })
  $vendorCallsAfterRetry = @(Get-Content -LiteralPath $invocationMarker).Count
  $failedCapOk = ($r.ExitCode -eq 1) -and ($r.Output -match "Signing ceiling\s+reached before vendor") -and
    ($retryStarts.Count -eq 1) -and ($vendorCallsAfterRetry -eq 1)
  Record "12. failed-attempt ceiling pre-invocation" $failedCapOk `
    ("exit=" + $r.ExitCode + " attempts=" + $retryStarts.Count + " vendorCalls=" + $vendorCallsAfterRetry)

} finally {
  # ALWAYS clean up: remove the throwaway cert from the store + delete temp files.
  if ($cert -and $cert.Thumbprint) {
    try { Remove-Item -Path ("Cert:\CurrentUser\My\" + $cert.Thumbprint) -Force -ErrorAction SilentlyContinue } catch { }
    Write-Host ("Removed cert " + $cert.Thumbprint + " from Cert:\CurrentUser\My")
  }
  if ($tmpDir -and (Test-Path -LiteralPath $tmpDir)) {
    try { Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue } catch { }
    Write-Host ("Removed temp dir " + $tmpDir)
  }
  Clear-SignEnv
}

Write-Host ""
Write-Host "===== Rehearsal summary ====="
$results | Format-Table -AutoSize | Out-String | Write-Host
if ($overallOk) {
  Write-Host "OVERALL: PASS"
  exit 0
} else {
  Write-Host "OVERALL: FAIL"
  exit 1
}
