#requires -Version 5.1
param(
  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [string]$EvidenceOutRoot = "tmp/agent-evidence",

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [switch]$ValidateEvidenceBundle
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path -Path $PSScriptRoot -ChildPath "..\\..")).Path
$tools = Join-Path $repoRoot 'SSOT/tools'

$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$runStdout = Join-Path -Path $summaryDir -ChildPath ("ssot-generate-" + $timestamp + ".stdout.log")
$runStderr = Join-Path -Path $summaryDir -ChildPath ("ssot-generate-" + $timestamp + ".stderr.log")

$scenario = "ssot-generate"
$exitCode = 0
$errMsg = $null
$bundleDir = $null

Push-Location $tools
try {
  $p = Start-Process -FilePath "node" -ArgumentList @("--version") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $runStdout -RedirectStandardError $runStderr
  if ($p.ExitCode -ne 0) { throw "node --version failed (exit=$($p.ExitCode))" }

  $p1 = Start-Process -FilePath "npm" -ArgumentList @("run", "generate-gbnf") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $runStdout -RedirectStandardError $runStderr
  if ($p1.ExitCode -ne 0) { throw "npm run generate-gbnf failed (exit=$($p1.ExitCode))" }

  $p2 = Start-Process -FilePath "npm" -ArgumentList @("run", "resolve-pipeline") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $runStdout -RedirectStandardError $runStderr
  if ($p2.ExitCode -ne 0) { throw "npm run resolve-pipeline failed (exit=$($p2.ExitCode))" }

  $p3 = Start-Process -FilePath "npm" -ArgumentList @("run", "synonyms-compile") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $runStdout -RedirectStandardError $runStderr
  if ($p3.ExitCode -ne 0) { throw "npm run synonyms-compile failed (exit=$($p3.ExitCode))" }
} catch {
  $exitCode = if ($exitCode -ne 0) { $exitCode } else { 1 }
  $errMsg = $_.Exception.Message
} finally {
  Pop-Location

  # Attach key SSOT artifacts by directory (small-ish, high-signal).
  $attachDirs = @()
  $ssotArtifacts = Join-Path -Path $repoRoot -ChildPath "SSOT\\artifacts"
  if (Test-Path -LiteralPath $ssotArtifacts) { $attachDirs += $ssotArtifacts }
  $ssotSnapshot = Join-Path -Path $repoRoot -ChildPath "SSOT\\snapshots"
  if (Test-Path -LiteralPath $ssotSnapshot) { $attachDirs += $ssotSnapshot }

  # Always capture EBv1 on PASS/FAIL (no HTTP API here; use api-base-url=none).
  $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
  $captureArgs = @(
    "--scenario=$scenario",
    "--api-base-url=none",
    "--out-root=$EvidenceOutRoot",
    "--trace=false",
    "--attach-label=ssot",
    "--attach-file=$runStdout",
    "--attach-file=$runStderr"
  )
  foreach ($d in $attachDirs) {
    $captureArgs += "--attach-dir=$d"
  }

  if ($exitCode -eq 0) {
    $captureArgs += "--external-status=passed"
  } else {
    $captureArgs += "--external-status=failed"
    if ($errMsg) { $captureArgs += "--external-error=$errMsg" }
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
    Write-Host "Evidence capture produced no bundle path (exit=$capExit). See $runStdout / $runStderr" -ForegroundColor Red
    exit 1
  }
  # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
  if ($exitCode -eq 0 -and $capExit -ne 0) {
    Write-Host "Evidence capture reported failed status (exit=$capExit). bundle=$bundleDir" -ForegroundColor Red
    exit 1
  }

  if ($ValidateEvidenceBundle.IsPresent) {
    Push-Location $repoRoot
    try {
      & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs") $bundleDir
      if ($LASTEXITCODE -ne 0) { throw "validate-evidencebundle-v1 failed (exit=$LASTEXITCODE)" }
      & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs") $bundleDir | Out-Null
    } finally {
      Pop-Location
    }
  }

  Write-Host ("EvidenceBundle: " + $bundleDir)
}

exit $exitCode
