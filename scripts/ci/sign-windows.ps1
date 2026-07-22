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

# Tee everything to a log file too: Tauri's bundler swallows this script's stdout/stderr on
# failure ("failed to run powershell.exe" with zero detail — tempdoc 760 rehearsal run
# 29910543640), so the log file is the only way a CI failure here is diagnosable. The workflow
# prints it in an if:failure() step.
$script:signLogPath = Join-Path $env:TEMP "justsearch-sign-windows.log"

function Write-SignLog([string]$Message) {
  try { Add-Content -LiteralPath $script:signLogPath -Value ("[" + (Get-Date).ToString("HH:mm:ss") + "] " + $Message) } catch { }
}

# Last-resort diagnosability: ANY terminating error that escapes normal handling gets tee'd with
# its position before the script dies — under the bundler, stderr is swallowed, so without this a
# terminating error is invisible (the failure mode that cost CI runs 29911439832 + 29912444815).
trap {
  Write-SignLog ("TRAP terminating error: " + $_.Exception.GetType().Name + ": " + $_.Exception.Message +
    " at " + (([string]$_.InvocationInfo.PositionMessage) -replace "\r?\n", " "))
  Write-Error $_
  exit 1
}

function Fail([string]$Message) {
  Write-SignLog ("FAIL: " + $Message)
  Write-Error $Message
  exit 1
}

# Runs a native command with stderr made NON-TERMINATING and all output captured. Under
# $ErrorActionPreference=Stop with a piped stderr (exactly how Tauri's bundler runs this script),
# a native process's first stderr line becomes a terminating NativeCommandError BEFORE our
# exit-code check runs — the script dies mid-call and the real error is swallowed (tempdoc 760
# rehearsal run 29911439832: tee log ends at "Signing (pfx):", no FAIL line ever written).
# Deliberately does NOT log the arguments (pfx passwords travel in them) — only exe, exit, output.
function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [string[]]$Arguments = @()
  )
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  $output = @()
  $exit = -1
  try {
    $output = @(& $Exe @Arguments 2>&1 | ForEach-Object { "$_" })
    $exit = $LASTEXITCODE
  } catch {
    $output = @("launch failed: " + $_.Exception.Message)
    $exit = -1
  } finally {
    $ErrorActionPreference = $prevEap
  }
  Write-SignLog (([System.IO.Path]::GetFileName($Exe)) + " exit=" + $exit + " :: " + (($output | Select-Object -First 12) -join " || "))
  return [pscustomobject]@{ ExitCode = $exit; Output = $output }
}

# Sign attempts hit the timestamp server once per file; 100+ sequential requests from one CI IP
# is a realistic rate-limit shape, so allow a few attempts with a pause before failing.
function Invoke-NativeWithRetry {
  param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [string[]]$Arguments = @(),
    [int]$Attempts = 3,
    [int]$DelaySec = 3
  )
  for ($i = 1; $i -le $Attempts; $i++) {
    $res = Invoke-Native -Exe $Exe -Arguments $Arguments
    if ($res.ExitCode -eq 0) { return $res }
    if ($i -lt $Attempts) {
      Write-SignLog ("attempt " + $i + "/" + $Attempts + " failed (exit=" + $res.ExitCode + "), retrying in " + $DelaySec + "s")
      Start-Sleep -Seconds $DelaySec
    }
  }
  return $res
}

function Info([string]$Message) {
  Write-SignLog $Message
  Write-Host $Message
}

# Credential mode selects how the key is presented (all additive; no env renames):
#   pfx     - PFX file/base64 + password + timestamp (default; exactly today's behavior).
#   store   - cert in the Windows cert store by thumbprint; how USB-token/HSM CSP-backed
#             nonexportable keys present locally (signtool /sha1 <thumbprint> /s <store>).
#   command - a full command-line template with a {file} placeholder, run per file. Lets any
#             vendor CLI (Azure Trusted Signing, eSigner, ...) plug in with no further changes.
$mode = $env:JUSTSEARCH_CODESIGN_MODE
if (-not $mode -or -not $mode.Trim()) { $mode = "pfx" } else { $mode = $mode.Trim().ToLowerInvariant() }

$requireSigning = To-Bool $env:JUSTSEARCH_REQUIRE_SIGNING
# Rehearsal relaxation: accept a hash-valid but chain-untrusted (e.g. self-signed) signature so
# the pipeline can be dry-run end-to-end without a production cert. Off => behavior unchanged.
$allowUntrusted = To-Bool $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED

# pfx-mode inputs (also the back-compat default set).
# Trailing CR/LF stripping: secrets piped into `gh secret set` from files easily pick up a
# trailing newline (Set-Content/echo both append one), and signtool then fails with "The
# specified PFX password is not correct" while the bundler swallows this script's output —
# an opaque CI failure for a whitespace bug (empirically hit, tempdoc 760 rehearsal run
# 29910543640). A trailing newline is never a legitimate part of any of these values; inner
# and leading characters are preserved (passwords may contain spaces).
function Strip-TrailingNewlines([string]$Value) {
  if ($null -eq $Value) { return $Value }
  return $Value.TrimEnd("`r", "`n")
}

$pfxPath = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_PFX_PATH
$pfxB64 = $env:JUSTSEARCH_CODESIGN_PFX_B64
$pfxPassword = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_PFX_PASSWORD
$timestampUrl = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_TIMESTAMP_URL

# store-mode inputs.
$thumbprint = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_THUMBPRINT
$certStore = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_STORE
if (-not $certStore -or -not $certStore.Trim()) { $certStore = "My" }

# command-mode input.
$commandTemplate = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_COMMAND

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

# Not-configured => skip (exit 0) unless JUSTSEARCH_REQUIRE_SIGNING, in which case fail-closed.
function Skip-Or-Fail([string]$SkipMessage) {
  if ($requireSigning) {
    Fail ("Signing is required but inputs are missing. " + $SkipMessage)
  }
  Info $SkipMessage
  exit 0
}

# Post-sign verification. Default is today's strict `signtool verify /pa` hard-check. With
# JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED a present-and-hash-valid but chain-untrusted signature
# is accepted (SignerCertificate present AND status != NotSigned); NotSigned still fails.
function Assert-Signed([string]$Path, [string]$SigntoolPath) {
  Write-SignLog ("Assert-Signed enter (allowUntrusted=" + $allowUntrusted + "): " + $Path)
  if ($allowUntrusted) {
    # Freshly-signed bundler outputs can be transiently locked/settling (CI run 29912444815 died
    # exactly here with nothing logged: signtool exit=0, then silent death before any Info).
    # Read the signature defensively: catch + retry a few times, never die without a log line.
    $sig = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
      try {
        $sig = Get-AuthenticodeSignature -FilePath $Path -ErrorAction Stop
        break
      } catch {
        Write-SignLog ("Get-AuthenticodeSignature attempt " + $attempt + "/3 failed: " + $_.Exception.Message)
        if ($attempt -lt 3) { Start-Sleep -Milliseconds 500 }
      }
    }
    if ($null -eq $sig) {
      Fail ("Get-AuthenticodeSignature failed 3x for " + $Path + " (see sign log)")
    }
    if ($sig.Status -eq "Valid") {
      Info "Signed OK: $Path"
      return
    }
    if ($sig.SignerCertificate -and ([string]$sig.Status -ne "NotSigned")) {
      Info ("Signed (chain untrusted, status=" + $sig.Status + ") - accepted under JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED: " + $Path)
      return
    }
    Fail ("Authenticode signature missing after signing (status=" + $sig.Status + ") for " + $Path)
  }

  if (-not $SigntoolPath) {
    Fail "signtool.exe not found; cannot verify signature for $Path"
  }
  $verifyRes = Invoke-Native -Exe $SigntoolPath -Arguments @("verify", "/pa", "/v", $Path)
  if ($verifyRes.ExitCode -ne 0) {
    Fail ("signtool verify failed (exit=" + $verifyRes.ExitCode + ") for " + $Path + " :: " + (($verifyRes.Output | Select-Object -First 4) -join " | "))
  }
  Info "Signed OK: $Path"
}

switch ($mode) {
  "pfx" {
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
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_PFX_PATH or JUSTSEARCH_CODESIGN_PFX_B64 / JUSTSEARCH_CODESIGN_PFX_PASSWORD / JUSTSEARCH_CODESIGN_TIMESTAMP_URL)."
    }

    # Locate signtool (Windows SDK). Prefer PATH but also search Windows Kits default locations.
    $signtoolPath = Find-SignTool
    if (-not $signtoolPath) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (signtool.exe not found on PATH. Install the Windows SDK (SignTool) or add it to PATH.)"
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

      Info "Signing (pfx): $resolvedBinary"
      $signRes = Invoke-NativeWithRetry -Exe $signtoolPath -Arguments @(
        "sign", "/fd", "SHA256", "/td", "SHA256", "/tr", $timestampUrl,
        "/f", $pfxToUse, "/p", $pfxPassword, $resolvedBinary)
      if ($signRes.ExitCode -ne 0) {
        Fail ("signtool sign failed (exit=" + $signRes.ExitCode + ") for " + $resolvedBinary + " :: " + (($signRes.Output | Select-Object -First 4) -join " | "))
      }

      Assert-Signed $resolvedBinary $signtoolPath
    } finally {
      # Only delete temp PFX when we created it from base64.
      if (-not $resolvedPfxPath) {
        try { Remove-Item -LiteralPath $tmpPfx -Force -ErrorAction SilentlyContinue } catch { }
      }
    }
  }

  "store" {
    if (-not $thumbprint -or -not $thumbprint.Trim()) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_THUMBPRINT for store mode)."
    }
    $thumb = $thumbprint.Trim()

    $signtoolPath = Find-SignTool
    if (-not $signtoolPath) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (signtool.exe not found on PATH. Install the Windows SDK (SignTool) or add it to PATH.)"
    }

    # /sha1 <thumbprint> /s <store> selects the store cert (CSP/HSM keys stay nonexportable).
    # Timestamp is optional here; include /tr only when a URL is configured.
    $signArgs = @("sign", "/sha1", $thumb, "/s", $certStore, "/fd", "SHA256")
    if ($timestampUrl -and $timestampUrl.Trim()) {
      $signArgs += @("/td", "SHA256", "/tr", $timestampUrl.Trim())
    }
    $signArgs += $resolvedBinary

    Info ("Signing (store '" + $certStore + "', thumbprint " + $thumb + "): " + $resolvedBinary)
    $signRes = Invoke-NativeWithRetry -Exe $signtoolPath -Arguments $signArgs
    if ($signRes.ExitCode -ne 0) {
      Fail ("signtool sign failed (exit=" + $signRes.ExitCode + ") for " + $resolvedBinary + " :: " + (($signRes.Output | Select-Object -First 4) -join " | "))
    }

    Assert-Signed $resolvedBinary $signtoolPath
  }

  "command" {
    if (-not $commandTemplate -or -not $commandTemplate.Trim()) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_COMMAND for command mode)."
    }
    if ($commandTemplate -notmatch '\{file\}') {
      Fail "JUSTSEARCH_CODESIGN_COMMAND must contain a {file} placeholder."
    }

    $rendered = $commandTemplate.Replace("{file}", $resolvedBinary)
    # Run the rendered command line via a temp .cmd to avoid PowerShell/cmd quoting hazards with
    # paths that contain spaces. `& $batch` propagates the vendor CLI's exit code to $LASTEXITCODE.
    $batch = Join-Path -Path $env:TEMP -ChildPath ("justsearch-codesign-cmd-" + [guid]::NewGuid().ToString("N") + ".cmd")
    try {
      [System.IO.File]::WriteAllText($batch, ("@echo off`r`n" + $rendered + "`r`n"), [System.Text.Encoding]::ASCII)
      Info ("Signing (command): " + $rendered)
      $cmdRes = Invoke-Native -Exe $batch
      $cmdExit = $cmdRes.ExitCode
    } finally {
      try { Remove-Item -LiteralPath $batch -Force -ErrorAction SilentlyContinue } catch { }
    }
    if ($cmdExit -ne 0) {
      Fail ("Signing command failed (exit=" + $cmdExit + ") for " + $resolvedBinary + " :: " + (($cmdRes.Output | Select-Object -First 4) -join " | "))
    }

    # A vendor CLI may or may not ship signtool; locate it for the strict verify path (the
    # ALLOW_UNTRUSTED path uses Get-AuthenticodeSignature and needs no signtool).
    Assert-Signed $resolvedBinary (Find-SignTool)
  }

  default {
    Fail "Unknown JUSTSEARCH_CODESIGN_MODE '$mode' (expected pfx | store | command)."
  }
}
