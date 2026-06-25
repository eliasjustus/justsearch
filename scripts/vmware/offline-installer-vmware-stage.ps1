#Requires -Version 5.1
[CmdletBinding()]
param(
  # Path to the NSIS installer to test. If omitted, the newest bundle output is used.
  [string]$SetupExePath,

  # Directory containing one or more *-setup.exe installers (newest will be used).
  [string]$SetupExeDir,

  # Where to stage files for VMware Shared Folders.
  # Default is under repo tmp/ so paths are stable and writable.
  [string]$StageDir = "tmp/offline-installer-vmware",

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
$transcriptPath = Join-Path -Path $summaryDir -ChildPath ("offline-installer-vmware-stage-" + $timestamp + ".transcript.log")
$metaPath = Join-Path -Path $summaryDir -ChildPath ("offline-installer-vmware-stage-" + $timestamp + ".meta.json")
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

function Resolve-PathRelative {
  param([Parameter(Mandatory = $true)][string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return (Resolve-Path -LiteralPath $Path).Path
  }
  $candidate = Join-Path -Path $repoRoot -ChildPath $Path
  return (Resolve-Path -LiteralPath $candidate).Path
}

function Find-NewestSetupExe {
  param([Parameter(Mandatory = $true)][string[]]$Dirs)
  $candidates = @()
  foreach ($d in $Dirs) {
    if (-not $d) { continue }
    if (-not (Test-Path -LiteralPath $d)) { continue }
    $candidates += Get-ChildItem -LiteralPath $d -Filter "*-setup.exe" -File -ErrorAction SilentlyContinue
  }
  if (-not $candidates -or $candidates.Count -eq 0) { return $null }
  return ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}

if (-not (Test-Path -LiteralPath (Join-Path $repoRoot "gradlew.bat"))) {
  throw "Unable to resolve repo root from $scriptDir (gradlew.bat not found)."
}

try {
  $stageRoot = if ([System.IO.Path]::IsPathRooted($StageDir)) { $StageDir } else { Join-Path -Path $repoRoot -ChildPath $StageDir }
  $stageRoot = [System.IO.Path]::GetFullPath($stageRoot)
  $shareDir = Join-Path -Path $stageRoot -ChildPath "share"

  New-Item -ItemType Directory -Force -Path $shareDir | Out-Null

  # --- Resolve installer path ---
  if ($SetupExePath) {
    if (-not [System.IO.Path]::IsPathRooted($SetupExePath)) {
      $SetupExePath = Resolve-PathRelative -Path $SetupExePath
    } else {
      $SetupExePath = (Resolve-Path -LiteralPath $SetupExePath).Path
    }
  } else {
    $searchDirs = @()
    if ($SetupExeDir) {
      $searchDirs += (Resolve-PathRelative -Path $SetupExeDir)
    }
    # Prefer dist/installer (what packaging copies to), then fallback to Tauri bundle output.
    $searchDirs += (Join-Path -Path $repoRoot -ChildPath "dist\\installer")
    $searchDirs += (Join-Path -Path $repoRoot -ChildPath "modules\\shell\\src-tauri\\target\\release\\bundle\\nsis")
    $found = Find-NewestSetupExe -Dirs $searchDirs
    if (-not $found) {
      throw "No *-setup.exe found. Provide -SetupExePath or ensure one exists under dist/installer or target/release/bundle/nsis."
    }
    $SetupExePath = $found.FullName
  }

  if (-not (Test-Path -LiteralPath $SetupExePath)) {
    throw "Setup exe not found: $SetupExePath"
  }

  # --- Copy installer + helper scripts into share ---
  $setupName = [System.IO.Path]::GetFileName($SetupExePath)
  $destSetup = Join-Path -Path $shareDir -ChildPath $setupName
  if (-not [string]::Equals($SetupExePath, $destSetup, [System.StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $SetupExePath -Destination $destSetup -Force
  }

  $startScript = Join-Path -Path $scriptDir -ChildPath "offline-installer-vmware-start.ps1"
  $verifyScript = Join-Path -Path $scriptDir -ChildPath "offline-installer-vmware-verify.ps1"
  $stepsFile = Join-Path -Path $scriptDir -ChildPath "offline-installer-vmware-steps.txt"

  Copy-Item -LiteralPath $startScript -Destination (Join-Path $shareDir "offline-installer-vmware-start.ps1") -Force
  Copy-Item -LiteralPath $verifyScript -Destination (Join-Path $shareDir "offline-installer-vmware-verify.ps1") -Force
  Copy-Item -LiteralPath $stepsFile -Destination (Join-Path $shareDir "offline-installer-vmware-steps.txt") -Force

  Write-Host ""
  Write-Host "=== JustSearch Offline Installer Verification (VMware Workstation Pro) - staging ==="
  Write-Host "Staged folder (set this as a VMware Shared Folder):"
  Write-Host "  $shareDir"
  Write-Host ""
  Write-Host "Staged installer:"
  Write-Host "  $destSetup"
  Write-Host ""
  Write-Host "Next steps:"
  Write-Host "  1) In your Windows VM, install VMware Tools (required for Shared Folders)."
  Write-Host "  2) In VMware Workstation Pro: VM Settings > Options > Shared Folders > Always enabled > Add..."
  Write-Host "     Host path: $shareDir"
  Write-Host "  3) Disable networking in the VM (disconnect/disable the network adapter)."
  Write-Host "  4) In the VM, open the shared folder and run:"
  Write-Host "     - offline-installer-vmware-start.ps1 (opens checklist + launches installer UI)"
  Write-Host "     - offline-installer-vmware-verify.ps1 (after install + app launch; writes PASS/FAIL report)"
  Write-Host ""
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
        schema = "offline-installer-vmware-stage.v1"
        stageDir = $StageDir
        stageRoot = $stageRoot
        shareDir = $shareDir
        setupExePath = $SetupExePath
        stagedSetupPath = $destSetup
      }
      if ($destSetup -and (Test-Path -LiteralPath $destSetup)) {
        $meta.installer = [ordered]@{
          filename = [System.IO.Path]::GetFileName($destSetup)
          bytes = (Get-Item -LiteralPath $destSetup).Length
          sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $destSetup).Hash.ToLowerInvariant()
        }
      }
      $meta | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $metaPath -Encoding UTF8
    } catch {
      Write-Warning "Failed to write stage metadata (non-fatal): $($_.Exception.Message)"
    }

    try {
      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureArgs = @(
        "--scenario=offline-installer-vmware-stage",
        "--api-base-url=none",
        "--out-root=$EvidenceOutRoot",
        "--trace=false",
        "--attach-label=vmware_stage",
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


