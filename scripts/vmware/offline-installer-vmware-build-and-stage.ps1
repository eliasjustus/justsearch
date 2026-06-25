#Requires -Version 5.1
[CmdletBinding()]
param(
  # Skip building the installer (assume an existing *-setup.exe already exists under dist/installer or target/release/bundle/nsis).
  [switch]$SkipBuild,

  # Skip running the deterministic Phase 1 installer verification gate.
  [switch]$SkipVerify,

  # Optional: override which setup exe to package/verify.
  [string]$SetupExePath,

  # Where to stage files for VMware Shared Folders (contains a share/ subfolder).
  [string]$StageDir = "tmp/offline-installer-vmware"
  ,
  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [string]$EvidenceOutRoot = "tmp/agent-evidence",

  # Disable EvidenceBundle capture for this script.
  [switch]$NoEvidence,

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [switch]$ValidateEvidenceBundle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/vmware -> scripts -> repo root

$evidenceEnabled = -not $NoEvidence.IsPresent
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$transcriptPath = Join-Path -Path $summaryDir -ChildPath ("offline-installer-vmware-build-and-stage-" + $timestamp + ".transcript.log")
$metaPath = Join-Path -Path $summaryDir -ChildPath ("offline-installer-vmware-build-and-stage-" + $timestamp + ".meta.json")
$transcriptStarted = $false
$mainError = $null
$exitCode = 0

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

try {
  if (-not (Test-Path -LiteralPath (Join-Path $repoRoot "gradlew.bat"))) {
    Fail "Unable to resolve repo root from $scriptDir (gradlew.bat not found)."
  }

  Push-Location $repoRoot
  try {
    $pkgArgs = @()
    if ($SkipBuild.IsPresent) { $pkgArgs += "-SkipBuild" }
    if ($SkipVerify.IsPresent) { $pkgArgs += "-SkipVerify" }
    if ($SetupExePath) { $pkgArgs += @("-SetupExePath", $SetupExePath) }
    $pkgArgs += @("-EvidenceOutRoot", $EvidenceOutRoot)
    if ($ValidateEvidenceBundle.IsPresent) { $pkgArgs += "-ValidateEvidenceBundle" }

    Write-Host "Packaging installer (unsigned) + optional deterministic backend verification..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\package-installer-win.ps1 @pkgArgs
    if ($LASTEXITCODE -ne 0) { Fail "package-installer-win.ps1 failed (exit=$LASTEXITCODE)" }

    Write-Host ""
    Write-Host "Staging VMware Shared Folder contents..."
    $stageArgs = @("-StageDir", $StageDir, "-EvidenceOutRoot", $EvidenceOutRoot)
    if ($ValidateEvidenceBundle.IsPresent) { $stageArgs += "-ValidateEvidenceBundle" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\vmware\offline-installer-vmware-stage.ps1 @stageArgs
    if ($LASTEXITCODE -ne 0) { Fail "offline-installer-vmware-stage.ps1 failed (exit=$LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
} catch {
  $exitCode = 1
  $mainError = $_
} finally {
  if ($evidenceEnabled) {
    if ($transcriptStarted) {
      try { Stop-Transcript | Out-Null } catch { }
    }

    try {
      $meta = [ordered]@{
        schema = "offline-installer-vmware-build-and-stage.v1"
        stageDir = $StageDir
        setupExePath = $SetupExePath
        skipBuild = [bool]$SkipBuild.IsPresent
        skipVerify = [bool]$SkipVerify.IsPresent
      }
      $meta | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $metaPath -Encoding UTF8
    } catch {
      Write-Warning "Failed to write build-and-stage metadata (non-fatal): $($_.Exception.Message)"
    }

    try {
      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureArgs = @(
        "--scenario=offline-installer-vmware-build-and-stage",
        "--api-base-url=none",
        "--out-root=$EvidenceOutRoot",
        "--trace=false",
        "--attach-label=vmware_build_and_stage",
        "--attach-file=$metaPath"
      )
      if ($transcriptPath -and (Test-Path -LiteralPath $transcriptPath)) {
        $captureArgs += "--attach-file=$transcriptPath"
      }

      if ($mainError) {
        $captureArgs += "--external-status=failed"
        $captureArgs += ("--external-error=" + $mainError.Exception.Message)
      } else {
        $captureArgs += "--external-status=passed"
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
        if ($mainError) { Write-Warning $msg } else { throw $msg }
      }
      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if (-not $mainError -and $capExit -ne 0) {
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
      if ($mainError) {
        Write-Warning ("Evidence capture/validation failed (non-fatal): " + $_.Exception.Message)
      } else {
        throw
      }
    }
  }
}

if ($mainError) { throw $mainError }
exit $exitCode


