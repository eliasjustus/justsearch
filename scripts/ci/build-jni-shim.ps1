Param(
  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [string]$EvidenceOutRoot = "tmp/agent-evidence",

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [switch]$ValidateEvidenceBundle
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path -Path $PSScriptRoot -ChildPath "..\\..")).Path
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir

$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$gradleStdout = Join-Path -Path $summaryDir -ChildPath ("build-jni-shim-" + $timestamp + ".stdout.log")
$gradleStderr = Join-Path -Path $summaryDir -ChildPath ("build-jni-shim-" + $timestamp + ".stderr.log")

$scenario = "build-jni-shim"
$exitCode = 0
$errMsg = $null
$bundleDir = $null

try {
  $p = Start-Process -FilePath (Join-Path -Path $repoRoot -ChildPath "gradlew.bat") `
    -ArgumentList @("--no-daemon", ":modules:ai-bridge:buildJniShim") `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $gradleStdout `
    -RedirectStandardError $gradleStderr
  $exitCode = $p.ExitCode
  if ($exitCode -ne 0) { throw "gradle buildJniShim failed (exit=$exitCode)" }
} catch {
  $exitCode = if ($exitCode -ne 0) { $exitCode } else { 1 }
  $errMsg = $_.Exception.Message
} finally {
  $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
  $captureArgs = @(
    "--scenario=$scenario",
    "--api-base-url=none",
    "--out-root=$EvidenceOutRoot",
    "--trace=false",
    "--attach-label=jni_shim",
    "--attach-file=$gradleStdout",
    "--attach-file=$gradleStderr"
  )

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
    Write-Host "Evidence capture produced no bundle path (exit=$capExit). See $gradleStdout / $gradleStderr" -ForegroundColor Red
    exit 1
  }
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

