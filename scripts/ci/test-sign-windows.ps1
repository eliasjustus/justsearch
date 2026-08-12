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
  "JUSTSEARCH_REQUIRE_SIGNING"
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

  # --- Case 3: command mode (template invokes signtool with the pfx) + ALLOW_UNTRUSTED => exit 0, signed ---
  if ($signtool) {
    $t = New-Target "command"
    $template = '"' + $signtool + '" sign /fd SHA256 /f "' + $pfxPath + '" /p ' + $pass + ' "{file}"'
    $r = Invoke-SignCase @{
      JUSTSEARCH_CODESIGN_MODE = "command"
      JUSTSEARCH_CODESIGN_COMMAND = $template
      JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED = "1"
    } $t
    Write-Host $r.Output
    Record "3. command + ALLOW_UNTRUSTED" (($r.ExitCode -eq 0) -and (Test-CaseSigned $t)) ("exit=" + $r.ExitCode + " " + (Signed-Detail $t))
  } else {
    Record "3. command + ALLOW_UNTRUSTED" $true "SKIPPED (no signtool)"
  }

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
