#Requires -Version 5.1
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$BinaryPath,

  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [string]$EvidenceOutRoot = "tmp/agent-evidence",

  # Disable EvidenceBundle capture for this script.
  [switch]$NoEvidence,

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [switch]$ValidateEvidenceBundle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"


$repoRoot = (Resolve-Path -LiteralPath (Join-Path -Path $PSScriptRoot -ChildPath "..\\..")).Path
$evidenceEnabled = -not $NoEvidence.IsPresent
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$transcriptPath = Join-Path -Path $summaryDir -ChildPath ("verify-windows-signature-" + $timestamp + ".transcript.log")
$metaPath = Join-Path -Path $summaryDir -ChildPath ("verify-windows-signature-" + $timestamp + ".meta.json")
$transcriptStarted = $false

if ($evidenceEnabled) {
  try {
    Start-Transcript -Path $transcriptPath -Force | Out-Null
    $transcriptStarted = $true
  } catch {
    $transcriptStarted = $false
    Write-Warning "Start-Transcript failed (non-fatal): $($_.Exception.Message)"
  }
}

function Fail([string]$Message) {
  throw $Message
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

if (-not $BinaryPath) {
  Fail "BinaryPath is required"
}

$resolved = if ([System.IO.Path]::IsPathRooted($BinaryPath)) { $BinaryPath } else { (Resolve-Path -LiteralPath $BinaryPath).Path }
if (-not (Test-Path -LiteralPath $resolved)) {
  Fail "Binary not found: $resolved"
}


$exitCode = 0
$mainError = $null
$meta = [ordered]@{
  schema = "verify-windows-signature.v1"
  binary = [ordered]@{
    path = $resolved
    filename = [System.IO.Path]::GetFileName($resolved)
    bytes = (Get-Item -LiteralPath $resolved).Length
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash.ToLowerInvariant()
  }
  verifiedAt = (Get-Date).ToString("o")
  method = $null
  result = $null
}

try {
  $signtoolPath = Find-SignTool
  if ($signtoolPath) {
    Write-Host "Verifying signature via signtool: $resolved"
    $out = & $signtoolPath verify /pa /v $resolved 2>&1 | Out-String
    $code = $LASTEXITCODE
    $meta.method = "signtool"
    $meta.result = [ordered]@{ ok = ($code -eq 0); exitCode = $code; outputSnippet = ($out | Select-Object -First 60) -join "" }
    if ($code -ne 0) {
      Fail "signtool verify failed (exit=$code) for $resolved"
    }
    Write-Host "Signature OK (signtool): $resolved"
  } else {
    # Fallback: Authenticode signature presence/validity.
    Write-Warning "signtool.exe not found; falling back to Get-AuthenticodeSignature (less strict)."
    $sig = Get-AuthenticodeSignature -FilePath $resolved
    $meta.method = "authenticode"
    $meta.result = [ordered]@{ status = [string]$sig.Status; statusMessage = [string]$sig.StatusMessage }
    if ($sig.Status -ne "Valid") {
      Fail "Authenticode signature invalid/missing. Status=$($sig.Status). Path=$resolved"
    }
    Write-Host "Signature OK (Authenticode): $resolved"
  }
} catch {
  $exitCode = 1
  $mainError = $_
  $meta.error = [ordered]@{ message = $_.Exception.Message }
} finally {
  try { $meta | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $metaPath -Encoding UTF8 } catch { }

  if ($evidenceEnabled) {
    if ($transcriptStarted) {
      try { Stop-Transcript | Out-Null } catch { }
    }

    try {
      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureArgs = @(
        "--scenario=verify-windows-signature",
        "--api-base-url=none",
        "--out-root=$EvidenceOutRoot",
        "--trace=false",
        "--attach-label=signature",
        "--attach-file=$metaPath"
      )
      if ($transcriptPath -and (Test-Path -LiteralPath $transcriptPath)) {
        $captureArgs += "--attach-file=$transcriptPath"
      }

      if ($exitCode -eq 0) {
        $captureArgs += "--external-status=passed"
      } else {
        $captureArgs += "--external-status=failed"
        if ($mainError) { $captureArgs += ("--external-error=" + $mainError.Exception.Message) }
      }

      $bundleDirRaw = $null
      $capExit = 0
      Push-Location $repoRoot
      try {
        $bundleDirRaw = & node $captureScript @captureArgs
        $capExit = $LASTEXITCODE
      } finally {
        Pop-Location
      }

      $bundleDir = ([string]$bundleDirRaw).Trim()
      if ([string]::IsNullOrWhiteSpace($bundleDir)) {
        $msg = "Evidence capture produced no bundle path (exit=$capExit)."
        if ($exitCode -ne 0) {
          Write-Warning $msg
        } else {
          throw $msg
        }
      }

      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if ($exitCode -eq 0 -and $capExit -ne 0) {
        throw "Evidence capture reported failed status (exit=$capExit). bundle=$bundleDir"
      }

      if ($ValidateEvidenceBundle.IsPresent -and -not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Push-Location $repoRoot
        try {
          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs") $bundleDir
          if ($LASTEXITCODE -ne 0) { throw "validate-evidencebundle-v1 failed (exit=$LASTEXITCODE)" }
          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs") $bundleDir | Out-Null
        } finally {
          Pop-Location
        }
      }

      if (-not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Write-Host ("EvidenceBundle: " + $bundleDir)
      }
    } catch {
      # Keep the primary signature failure visible; do not override with evidence failures.
      if ($exitCode -ne 0) {
        Write-Warning ("Evidence capture/validation failed (non-fatal): " + $_.Exception.Message)
      } else {
        throw
      }
    }
  }
}

if ($exitCode -ne 0 -and $mainError) {
  # Preserve original error formatting for callers.
  throw $mainError
}

exit $exitCode


