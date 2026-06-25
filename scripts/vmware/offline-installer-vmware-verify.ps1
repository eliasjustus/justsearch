#Requires -Version 5.1
[CmdletBinding()]
param(
  # Where to write the report/log files. Defaults to the script folder if writable, otherwise %TEMP%\JustSearch-Offline-Installer.
  [string]$OutDir,

  # Timeout waiting for JUSTSEARCH_API_PORT=... to appear in headless-backend.log.
  [int]$PortTimeoutSec = 60,

  # Timeout waiting for the backend to reach "ready" once the port is known.
  # This includes Worker readiness (indexAvailable=true) and /api/health worker.status == "UP".
  [int]$ReadyTimeoutSec = 60,

  # Timeout per HTTP request to /api/status and /api/health.
  [int]$HttpTimeoutSec = 5,

  # If set, treat determinism-budget violations (severity=error) as fatal (default is warn-only).
  [switch]$EnforceDeterminismBudget,

  # If set, capture EvidenceBundle v1 under tmp/agent-evidence (PASS and FAIL).
  [switch]$CaptureEvidenceBundle,

  # Evidence scenario slug (folder under tmp/agent-evidence/<scenario>/...).
  [string]$EvidenceScenario = "offline-installer-vmware-verify",

  # Optional extra API snapshots to include (csv): debug,policy,inference,gpu,ui_ready,effective_config
  [string]$EvidenceInclude = "",

  # Root directory for EvidenceBundles. Default: tmp/agent-evidence (repo root).
  [string]$EvidenceBundleOutRoot = "tmp\\agent-evidence"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Ensure HttpClient type is available in Windows PowerShell 5.1
try {
  Add-Type -AssemblyName System.Net.Http
} catch {
  # best-effort; script will fail later with a clearer error if HTTP types are unavailable
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path -Path $here -ChildPath "..\\..")).Path

$detModule = Join-Path -Path $repoRoot -ChildPath "scripts\\test-support\\DeterminismBudget.psm1"
Import-Module -LiteralPath $detModule -Force -DisableNameChecking
$det = New-DeterminismBudget -StdoutSentinel "JUSTSEARCH_API_PORT=..." -LogScrapeAllowed 1

function Test-WritableDir {
  param([Parameter(Mandatory = $true)][string]$Dir)
  try {
    if (-not (Test-Path -LiteralPath $Dir)) { return $false }
    $probe = Join-Path -Path $Dir -ChildPath ("._write_test_" + [Guid]::NewGuid().ToString("N") + ".tmp")
    "test" | Set-Content -LiteralPath $probe -Encoding UTF8
    Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
    return $true
  } catch {
    return $false
  }
}

function Resolve-OutDir {
  param([string]$Preferred)

  if ($Preferred) {
    if (-not (Test-Path -LiteralPath $Preferred)) {
      New-Item -ItemType Directory -Force -Path $Preferred | Out-Null
    }
    if (Test-WritableDir -Dir $Preferred) {
      return (Resolve-Path -LiteralPath $Preferred).Path
    }
  }

  if (Test-WritableDir -Dir $here) {
    return $here
  }

  $fallback = Join-Path -Path $env:TEMP -ChildPath "JustSearch-Offline-Installer"
  New-Item -ItemType Directory -Force -Path $fallback | Out-Null
  return (Resolve-Path -LiteralPath $fallback).Path
}

function Write-Log {
  param(
    [Parameter(Mandatory = $true)][string]$Message,
    [Parameter(Mandatory = $true)][string]$LogPath
  )
  $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message"
  try {
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
  } catch {
    # best-effort
  }
  Write-Host $Message
}

function Find-JustSearchExe {
  $candidates = @()
  if ($env:LOCALAPPDATA) {
    $candidates += (Join-Path -Path $env:LOCALAPPDATA -ChildPath "Programs\\JustSearch\\JustSearch.exe")
    $candidates += (Join-Path -Path $env:LOCALAPPDATA -ChildPath "Programs\\JustSearch.exe")
  }
  if ($env:ProgramFiles) {
    $candidates += (Join-Path -Path $env:ProgramFiles -ChildPath "JustSearch\\JustSearch.exe")
  }

  foreach ($p in $candidates) {
    if ($p -and (Test-Path -LiteralPath $p)) { return $p }
  }

  $programsDir = $null
  if ($env:LOCALAPPDATA) {
    $programsDir = Join-Path -Path $env:LOCALAPPDATA -ChildPath "Programs"
  }
  if ($programsDir -and (Test-Path -LiteralPath $programsDir)) {
    try {
      $found = Get-ChildItem -LiteralPath $programsDir -Filter "JustSearch.exe" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
      if ($found) { return $found.FullName }
    } catch { }
  }

  return $null
}

function Find-HeadlessBackendLog {
  $ids = @("JustSearch", "io.justsearch.shell")
  $bases = @()
  if ($env:APPDATA -and (Test-Path -LiteralPath $env:APPDATA)) { $bases += $env:APPDATA }
  if ($env:LOCALAPPDATA -and (Test-Path -LiteralPath $env:LOCALAPPDATA)) { $bases += $env:LOCALAPPDATA }

  foreach ($base in $bases) {
    foreach ($name in $ids) {
      $cand = Join-Path -Path (Join-Path -Path $base -ChildPath $name) -ChildPath "logs\\headless-backend.log"
      if (Test-Path -LiteralPath $cand) { return $cand }
    }
  }

  foreach ($base in $bases) {
    try {
      $topDirs = Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'JustSearch|justsearch|io\.justsearch\.shell' }
      foreach ($d in $topDirs) {
        $cand = Join-Path -Path $d.FullName -ChildPath "logs\\headless-backend.log"
        if (Test-Path -LiteralPath $cand) { return $cand }
      }
    } catch { }
  }

  foreach ($base in $bases) {
    try {
      $found = Get-ChildItem -LiteralPath $base -Filter "headless-backend.log" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
      if ($found) { return $found.FullName }
    } catch { }
  }

  return $null
}

function Wait-ForApiPortFromLog {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][string]$LogPath,
    [int]$TimeoutSec = 60
  )
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
  while ([DateTime]::UtcNow -lt $deadline) {
    Add-StdoutSentinelParse -Det $Det
    if (Test-Path -LiteralPath $LogPath) {
      $tail = @()
      try { $tail = Get-Content -LiteralPath $LogPath -Tail 200 -ErrorAction SilentlyContinue } catch { }
      foreach ($line in $tail) {
        if ($line -match '^JUSTSEARCH_API_PORT=(\d+)$') {
          return [int]$Matches[1]
        }
      }
    }
    Add-BackoffSleep -Det $Det -Reason "wait_for_port_sentinel" -Ms 250
  }
  return 0
}

function Get-HttpBody {
  param(
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][string]$Uri
  )
  $resp = $Client.GetAsync($Uri).Result
  $body = $resp.Content.ReadAsStringAsync().Result
  return [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $body; Headers = $resp.Headers }
}

function Send-Options {
  param(
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][string]$Uri,
    [Parameter(Mandatory = $true)][string]$Origin
  )
  $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Options, $Uri)
  $null = $req.Headers.TryAddWithoutValidation("Origin", $Origin)
  $null = $req.Headers.TryAddWithoutValidation("Access-Control-Request-Method", "GET")
  $resp = $Client.SendAsync($req).Result
  $body = $resp.Content.ReadAsStringAsync().Result
  return [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $body; Headers = $resp.Headers }
}

function Get-HeaderValuesOrEmpty {
  param(
    [Parameter(Mandatory = $true)]$Headers,
    [Parameter(Mandatory = $true)][string]$Name
  )
  try {
    return @($Headers.GetValues($Name))
  } catch {
    return @()
  }
}

$outDirResolved = Resolve-OutDir -Preferred $OutDir
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runLog = Join-Path -Path $outDirResolved -ChildPath ("offline-installer-vmware-verify-" + $stamp + ".log")
$report = Join-Path -Path $outDirResolved -ChildPath ("offline-installer-vmware-report-" + $stamp + ".txt")

$failures = New-Object System.Collections.Generic.List[string]

function Pass([string]$Message) { Write-Log -Message ("PASS: " + $Message) -LogPath $runLog }
function Fail([string]$Message) { $null = $failures.Add($Message); Write-Log -Message ("FAIL: " + $Message) -LogPath $runLog }
function Info([string]$Message) { Write-Log -Message ("INFO: " + $Message) -LogPath $runLog }
function Warn([string]$Message) { Write-Log -Message ("WARN: " + $Message) -LogPath $runLog }

Write-Log -Message "" -LogPath $runLog
Write-Log -Message "=== JustSearch Offline Installer Verification (VMware) - backend checks ===" -LogPath $runLog
Write-Log -Message ("OutDir:  " + $outDirResolved) -LogPath $runLog
Write-Log -Message ("RunLog:  " + $runLog) -LogPath $runLog
Write-Log -Message ("Report:  " + $report) -LogPath $runLog
Write-Log -Message "Note: this script cannot visually verify WebView2/UI rendering. Use offline-installer-vmware-steps.txt for manual UI steps." -LogPath $runLog
Write-Log -Message "" -LogPath $runLog

$mainError = $null
$headlessLog = $null
$port = 0

try {

# Offline hint (best-effort)
try {
  $upAdapters = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq "Up" })
  if ($upAdapters.Count -gt 0) {
    $names = ($upAdapters | ForEach-Object { $_.Name } | Sort-Object) -join ", "
    Warn ("Network adapters appear UP (" + $names + "). For offline validation, disconnect/disable the VM adapter.")
  } else {
    Info "No 'Up' network adapters detected (good for offline validation)."
  }
} catch {
  Warn "Unable to query network adapters (Get-NetAdapter)."
}

# Ensure app is running (best-effort)
$justSearchProc = Get-Process -Name "JustSearch" -ErrorAction SilentlyContinue | Select-Object -First 1
$justSearchExe = $null
if (-not $justSearchProc) {
  $justSearchExe = Find-JustSearchExe
  if ($justSearchExe) {
    Info ("JustSearch is not running; attempting to launch: " + $justSearchExe)
    try {
      Start-Process -FilePath $justSearchExe | Out-Null
      $deadline = [DateTime]::UtcNow.AddSeconds(10)
      while (-not $justSearchProc -and [DateTime]::UtcNow -lt $deadline) {
        $justSearchProc = Get-Process -Name "JustSearch" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($justSearchProc) { break }
        Add-BackoffSleep -Det $det -Reason "wait_for_ui_ready" -Ms 250
      }
    } catch {
      Fail ("Failed to launch JustSearch exe: " + $justSearchExe + " Error=" + $_.Exception.Message)
    }
  } else {
    Fail "JustSearch process not found and installed exe could not be located. Install + launch the app, then re-run this script."
  }
}

if ($justSearchProc) {
  Pass ("JustSearch process is running (PID " + $justSearchProc.Id + ").")
}

$headlessLog = Find-HeadlessBackendLog
if (-not $headlessLog) {
  Fail "Could not find headless backend log (headless-backend.log). Launch the app once and try again."
} else {
  Pass ("Found headless backend log: " + $headlessLog)
}

$port = 0
if ($headlessLog) {
  Info ("Waiting up to " + $PortTimeoutSec + "s for JUSTSEARCH_API_PORT=... in headless backend log...")
  $port = Wait-ForApiPortFromLog -Det $det -LogPath $headlessLog -TimeoutSec $PortTimeoutSec
  if ($port -le 0) {
    Fail ("Did not observe JUSTSEARCH_API_PORT=... in log within " + $PortTimeoutSec + "s. Open the log and check for errors.")
  } else {
    Pass ("Observed backend port: " + $port)
  }
}

if ($port -gt 0) {
  $client = New-Object System.Net.Http.HttpClient
  $client.Timeout = [TimeSpan]::FromSeconds($HttpTimeoutSec)
  $base = "http://127.0.0.1:$port"

  try {
    Info ("Waiting up to " + $ReadyTimeoutSec + "s for backend readiness (indexAvailable + worker UP)...")
    $readyDeadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSec)
    $ready = $false
    $lastStatus = $null
    $lastHealth = $null
    $statusJson = $null
    $healthJson = $null

    while ([DateTime]::UtcNow -lt $readyDeadline) {
      $lastStatus = $null
      $lastHealth = $null
      $statusJson = $null
      $healthJson = $null
      try {
        $lastStatus = Get-HttpBody -Client $client -Uri ($base + "/api/status")
        if ($lastStatus.StatusCode -eq 200) {
          try { $statusJson = $lastStatus.Body | ConvertFrom-Json } catch { $statusJson = $null }
        }
      } catch { }

      if ($statusJson) {
        $ksErr = [string]$statusJson.knowledgeServerStartError
        if (-not [string]::IsNullOrWhiteSpace($ksErr)) {
          Fail ("Backend reported knowledgeServerStartError='" + $ksErr + "' (this will not self-heal).")
          break
        }
      }

      if ($statusJson -and $statusJson.indexAvailable -eq $true) {
        try {
          $lastHealth = Get-HttpBody -Client $client -Uri ($base + "/api/health")
          if ($lastHealth.StatusCode -eq 200) {
            try { $healthJson = $lastHealth.Body | ConvertFrom-Json } catch { $healthJson = $null }
          }
        } catch { }

        if ($healthJson -and $healthJson.worker -and $healthJson.worker.status -eq "UP") {
          $ready = $true
          break
        }
      }

      Add-BackoffSleep -Det $det -Reason "wait_for_backend_ready" -Ms 250
    }

    if (-not $ready) {
      $s = if ($lastStatus) { ("(" + $lastStatus.StatusCode + ") " + $lastStatus.Body) } else { "<none>" }
      $h = if ($lastHealth) { ("(" + $lastHealth.StatusCode + ") " + $lastHealth.Body) } else { "<none>" }
      Fail ("Timed out waiting for backend readiness. Last /api/status=" + $s + " Last /api/health=" + $h)
    } else {
      Pass "/api/status indexAvailable == true and knowledgeServerStartError is empty"
      Pass "/api/health worker.status == UP"
    }

    # Loopback bind check (best-effort)
    try {
      $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
      if ($listeners.Count -le 0) {
        Fail ("Expected a LISTEN socket on port " + $port + " (Get-NetTCPConnection returned none).")
      } else {
        $nonLoopback = @($listeners | Where-Object { $_.LocalAddress -ne "127.0.0.1" -and $_.LocalAddress -ne "::1" })
        if ($nonLoopback.Count -gt 0) {
          $got = ($listeners | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" } | Sort-Object) -join ", "
          Fail ("Expected loopback-only bind on port " + $port + ". Got: " + $got)
        } else {
          Pass ("Backend is loopback-only on port " + $port)
        }
      }
    } catch {
      Warn "Loopback bind check skipped (Get-NetTCPConnection unavailable)."
    }

    # CORS posture checks (prod mode; best-effort)
    try {
      foreach ($allowedOrigin in @("tauri://localhost", "https://tauri.localhost", "http://tauri.localhost")) {
        $optAllowed = Send-Options -Client $client -Uri ($base + "/api/status") -Origin $allowedOrigin
        if ($optAllowed.StatusCode -lt 200 -or $optAllowed.StatusCode -ge 300) {
          Fail ("Expected preflight from " + $allowedOrigin + " to succeed (2xx), got status " + $optAllowed.StatusCode)
        } else {
          $acaosAllowed = @(Get-HeaderValuesOrEmpty -Headers $optAllowed.Headers -Name "Access-Control-Allow-Origin")
          if ($acaosAllowed.Count -ne 1 -or $acaosAllowed[0] -ne $allowedOrigin) {
            Fail ("Expected Access-Control-Allow-Origin=" + $allowedOrigin + "; got: " + ($acaosAllowed -join ", ") + " (status " + $optAllowed.StatusCode + ")")
          } else {
            Pass ("CORS allows " + $allowedOrigin)
          }
        }
      }

      foreach ($blockedOrigin in @("http://localhost:5173", "http://127.0.0.1:5173")) {
        $optBrowser = Send-Options -Client $client -Uri ($base + "/api/status") -Origin $blockedOrigin
        if ($optBrowser.StatusCode -ne 403) {
          Fail ("Expected preflight from " + $blockedOrigin + " to be rejected with 403 in prod, got " + $optBrowser.StatusCode)
        } else {
          $acaos = @(Get-HeaderValuesOrEmpty -Headers $optBrowser.Headers -Name "Access-Control-Allow-Origin")
          if ($acaos.Count -ne 0) {
            Fail ("Expected no Access-Control-Allow-Origin header for blocked origin " + $blockedOrigin + "; got: " + ($acaos -join ", "))
          } else {
            Pass ("CORS blocks " + $blockedOrigin)
          }
        }
      }
    } catch {
      Warn ("CORS checks skipped: " + $_.Exception.Message)
    }

  } catch {
    Fail ("HTTP checks failed: " + $_.Exception.Message)
  }
}
} catch {
  $mainError = $_
  try { Fail ("Unhandled exception: " + $_.Exception.Message) } catch { }
} finally {
  $detHasError = $false
  try { $detHasError = (Enforce-DeterminismBudget -Det $det) } catch { $detHasError = $false }
  $detPath = Join-Path -Path $outDirResolved -ChildPath ("determinism-budget-" + $stamp + ".json")
  try { ($det | ConvertTo-Json -Depth 15) | Set-Content -LiteralPath $detPath -Encoding UTF8 } catch { $detPath = $null }

  if ($detHasError) {
    $enforceDet = [bool]$EnforceDeterminismBudget.IsPresent -or ($env:JUSTSEARCH_DETERMINISM_ENFORCE -eq "1")
    if ($enforceDet) {
      Fail ("Determinism budget violations present (severity=error). See determinism-budget JSON artifact.")
    } else {
      Warn "Determinism budget violations present (warn-only; re-run with -EnforceDeterminismBudget to gate)."
    }
  }

  $lines = @()
  $lines += "JustSearch offline installer verification report (VMware)"
  $lines += "Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
  $lines += ""
  $lines += "Artifacts:"
  $lines += "  OutDir:      $outDirResolved"
  $lines += "  RunLog:      $runLog"
  $lines += "  HeadlessLog: $headlessLog"
  $lines += "  BackendPort: $port"
  $detLine = if ($detPath) { $detPath } else { "<none>" }
  $lines += ("  Determinism: " + $detLine)
  $lines += ""
  $lines += "Result:"
  if ($failures.Count -eq 0) {
    $lines += "  PASS (backend started and is reachable on loopback)."
  } else {
    $lines += "  FAIL"
    $lines += ""
    $lines += "Failures:"
    foreach ($f in $failures) { $lines += "  - $f" }
  }
  $lines += ""
  $lines += "Manual checks still required:"
  $lines += "  - UI renders (WebView2) and connects automatically"
  $lines += "  - Search/indexing works on a small folder"
  $lines += "  - Open file / Reveal in Explorer"
  $lines += "  - Single-instance behavior"
  $lines += "  - Uninstall behavior + data preservation + Delete Data option"

  $lines | Set-Content -LiteralPath $report -Encoding UTF8
  Write-Log -Message ("Wrote report: " + $report) -LogPath $runLog

  # ---------------------------------------------------------------------------
  # EvidenceBundle v1 capture (optional; PASS and FAIL)
  # ---------------------------------------------------------------------------
  $bundleCaptureError = $null
  $bundleDir = $null
  $captureEnabled = $CaptureEvidenceBundle.IsPresent -or (-not [string]::IsNullOrWhiteSpace($env:CI))
  if ($captureEnabled -and $port -gt 0) {
    try {
      $enforceDet = [bool]$EnforceDeterminismBudget.IsPresent -or ($env:JUSTSEARCH_DETERMINISM_ENFORCE -eq "1")
      $apiBaseUrl = "http://127.0.0.1:$port"

      $rootRaw = if (-not [string]::IsNullOrWhiteSpace($EvidenceBundleOutRoot)) { $EvidenceBundleOutRoot } else { "tmp\\agent-evidence" }
      $resolvedEvidenceBundleOutRoot = if ([System.IO.Path]::IsPathRooted($rootRaw)) { $rootRaw } else { Join-Path -Path $repoRoot -ChildPath $rootRaw }
      New-Item -ItemType Directory -Force -Path $resolvedEvidenceBundleOutRoot | Out-Null
      $resolvedEvidenceBundleOutRoot = (Resolve-Path -LiteralPath $resolvedEvidenceBundleOutRoot).Path

      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureArgs = @(
        $captureScript,
        "--scenario=$EvidenceScenario",
        "--api-base-url=$apiBaseUrl",
        "--out-root=$resolvedEvidenceBundleOutRoot",
        "--attach-label=harness"
      )

      if (-not [string]::IsNullOrWhiteSpace($EvidenceInclude)) {
        $captureArgs += "--include=$EvidenceInclude"
      }
      if ($enforceDet) {
        $captureArgs += "--enforce-determinism=true"
      }

      foreach ($p in @($runLog, $report, $headlessLog, $detPath)) {
        if ($p -and (Test-Path -LiteralPath $p)) {
          $captureArgs += "--attach-file=$p"
        }
      }
      if ($detPath -and (Test-Path -LiteralPath $detPath)) {
        $captureArgs += "--merge-determinism=$detPath"
      }

      $harnessFailed = ($failures.Count -gt 0) -or ($mainError -ne $null)
      if ($harnessFailed) { $captureArgs += "--external-status=failed" } else { $captureArgs += "--external-status=passed" }
      if ($mainError -ne $null) {
        $captureArgs += ("--external-error=Unhandled exception: " + $mainError.Exception.Message)
      }
      $i = 0
      foreach ($f in $failures) {
        if ($i -ge 20) { break }
        $captureArgs += ("--external-error=" + $f)
        $i += 1
      }

      Info ("Capturing EvidenceBundle v1 (scenario=$EvidenceScenario) ...")
      $bundleDirRaw = & node @captureArgs
      $capExit = $LASTEXITCODE
      $bundleDir = ([string]$bundleDirRaw).Trim()
      Info ("EvidenceBundle dir: " + $bundleDir)
      if ([string]::IsNullOrWhiteSpace($bundleDir)) {
        throw "EvidenceBundle capture produced no bundle path (exit=$capExit)."
      }
      if (-not $harnessFailed -and $capExit -ne 0) {
        throw "EvidenceBundle capture reported failed status (exit=$capExit). bundle=$bundleDir"
      }

      # Validate EBv1 invariants (hard gate).
      & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs") $bundleDir
      if ($LASTEXITCODE -ne 0) {
        throw "EvidenceBundle validator failed (exit=$LASTEXITCODE). bundle=$bundleDir"
      }

      # Validate determinism budget (warn-only by default).
      & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs") $bundleDir
      if ($LASTEXITCODE -ne 0) {
        if ($enforceDet) {
          throw "Determinism Budget validator failed (exit=$LASTEXITCODE). bundle=$bundleDir"
        } else {
          Warn "Determinism Budget validator failed (warn-only; re-run with -EnforceDeterminismBudget to gate)."
        }
      }

      Pass "EvidenceBundle v1 validation OK."
    } catch {
      $bundleCaptureError = $_.Exception.Message
      Fail ("EvidenceBundle capture/validation failed: " + $bundleCaptureError)
    }
  }

  if ($failures.Count -gt 0) { exit 1 }
  exit 0
}


