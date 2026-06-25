#Requires -Version 5.1
# Last touched: retrigger CI after llama-server b8157 upgrade.
[CmdletBinding()]
param(
  # Path to a pre-built NSIS installer (*-setup.exe). If omitted, the newest bundle output is used.
  [string]$SetupExePath,

  # Build the NSIS installer before verifying it (local-friendly).
  [switch]$BuildAndVerify,

  # If set, skips build even when -BuildAndVerify is provided.
  [switch]$SkipBuild,

  # Where to write evidence logs. Relative paths are resolved from repo root.
  [string]$EvidenceDir = "tmp/installer-verify",

  # Override install directory (must NOT contain spaces for robust NSIS /D handling).
  [string]$InstallDir,

  # Keep the install directory after verification (debugging).
  [switch]$KeepInstallDir,

  # Timeout waiting for JUSTSEARCH_API_PORT from the spawned headless backend.
  [int]$PortTimeoutSec = 60,

  # Timeout waiting for the backend to reach "ready" after the port is known.
  # This includes Worker readiness (indexAvailable=true) and /api/health worker.status == "UP".
  [int]$ReadyTimeoutSec = 60,

  # Timeout per HTTP request to /api/status and /api/health.
  [int]$HttpTimeoutSec = 5,

  # If set, capture an EvidenceBundle v1 (API snapshots + diagnostics export) and validate it.
  [switch]$CaptureEvidenceBundle,

  # If set, treat determinism-budget validation failures as fatal (default is warn-only).
  [switch]$EnforceDeterminismBudget,

  # EvidenceBundle v1 scenario slug (used in output path).
  [string]$EvidenceScenario = "installer-nsis-backend",

  # Optional CSV for extra snapshots (e.g. debug,policy,inference,gpu,ui_ready,effective_config).
  [string]$EvidenceInclude = "",

  # Where to write EvidenceBundle v1 output (defaults to <EvidenceDir>/agent-evidence).
  [string]$EvidenceBundleOutRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Ensure HttpClient type is available in Windows PowerShell 5.1
try {
  Add-Type -AssemblyName System.Net.Http
} catch {
  # best-effort; script will fail later with a clearer error if HTTP types are unavailable
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/ci -> scripts -> repo root

# Fallback: if invoked from nested scripts/* dir, detect top-level repo (contains gradlew.bat)
$gradlew = Join-Path -Path $repoRoot -ChildPath "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
  $maybeRoot = Split-Path -Parent $repoRoot
  $fallbackGradlew = Join-Path -Path $maybeRoot -ChildPath "gradlew.bat"
  if (Test-Path -LiteralPath $fallbackGradlew) {
    $repoRoot = $maybeRoot
    $gradlew = $fallbackGradlew
  }
}
if (-not (Test-Path -LiteralPath $gradlew)) {
  throw "Unable to find repo root (gradlew.bat not found). scriptDir=$scriptDir"
}

$detModule = Join-Path -Path $repoRoot -ChildPath "scripts\\test-support\\DeterminismBudget.psm1"
Import-Module -Name $detModule -Force -DisableNameChecking
$det = New-DeterminismBudget -StdoutSentinel "JUSTSEARCH_API_PORT=..." -LogScrapeAllowed 1

function Resolve-PathRelative {
  param([Parameter(Mandatory = $true)][string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
  if ([System.IO.Path]::IsPathRooted($Path)) {
    if (Test-Path -LiteralPath $Path) {
      return (Resolve-Path -LiteralPath $Path).Path
    }
    return [System.IO.Path]::GetFullPath($Path)
  }
  $candidate = Join-Path -Path $repoRoot -ChildPath $Path
  if (Test-Path -LiteralPath $candidate) {
    return (Resolve-Path -LiteralPath $candidate).Path
  }
  return $candidate
}

function Invoke-External {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $false)][string[]]$Arguments
  )
  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed (exit=$LASTEXITCODE): $FilePath $($Arguments -join ' ')"
  }
}

function New-TempDirNoSpaces {
  param([Parameter(Mandatory = $true)][string]$Prefix)
  $base = Join-Path -Path $env:TEMP -ChildPath ($Prefix + "-" + [Guid]::NewGuid().ToString("N"))
  # $env:TEMP should have no spaces; still ensure our final path contains no spaces.
  if ($base -match "\s") {
    $base = Join-Path -Path "C:\\Temp" -ChildPath ($Prefix + "-" + [Guid]::NewGuid().ToString("N"))
  }
  New-Item -ItemType Directory -Force -Path $base | Out-Null
  return $base
}

function Find-NewestFile {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Filter
  )
  $items = Get-ChildItem -LiteralPath $Path -Filter $Filter -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
  return $items | Select-Object -First 1
}

function Assert {
  param([Parameter(Mandatory = $true)][bool]$Condition, [Parameter(Mandatory = $true)][string]$Message)
  if (-not $Condition) { throw $Message }
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
  return [pscustomobject]@{
    StatusCode = [int]$resp.StatusCode
    Body = $body
    Headers = $resp.Headers
  }
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

function Wait-ForBackendReady {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][int]$TimeoutSec,
    [Parameter(Mandatory = $true)][string]$EvidenceFile
  )

  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
  $lastStatus = $null
  $lastHealth = $null
  $lastStatusJson = $null
  $lastHealthJson = $null
  $lastError = $null

  while ([DateTime]::UtcNow -lt $deadline) {
    # /api/status (retry on transient errors)
    try {
      $lastStatus = Get-HttpBody -Client $Client -Uri ("http://127.0.0.1:$Port/api/status")
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    if ($lastStatus.StatusCode -ne 200) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    try {
      $lastStatusJson = $lastStatus.Body | ConvertFrom-Json
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }

    # Fatal conditions (fail fast)
    $ksErr = [string]$lastStatusJson.knowledgeServerStartError
    if (-not [string]::IsNullOrWhiteSpace($ksErr)) {
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: /api/status reported knowledgeServerStartError='" + $ksErr + "'")
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Full /api/status body: " + $lastStatus.Body)
      throw "Backend reported knowledgeServerStartError='$ksErr'. Evidence=$EvidenceFile"
    }

    if ($lastStatusJson.indexAvailable -ne $true) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }

    $state = [string]$lastStatusJson.indexState
    if ($state -eq "ERROR") {
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: /api/status reported indexState=ERROR. Full body: " + $lastStatus.Body)
      throw "Backend reported indexState=ERROR. Evidence=$EvidenceFile"
    }

    # /api/health (retry on transient errors)
    try {
      $lastHealth = Get-HttpBody -Client $Client -Uri ("http://127.0.0.1:$Port/api/health")
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    if ($lastHealth.StatusCode -ne 200) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    try {
      $lastHealthJson = $lastHealth.Body | ConvertFrom-Json
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    $workerStatus = ""
    try { 
      # Check components.worker.state (new API format) first, fallback to worker.status (legacy)
      if ($lastHealthJson.components -and $lastHealthJson.components.worker) {
        $workerStatus = [string]$lastHealthJson.components.worker.state
      } else {
        $workerStatus = [string]$lastHealthJson.worker.status
      }
    } catch { $workerStatus = "" }

    # Accept both "UP" (legacy) and "READY" (new format) as valid worker states
    if ($workerStatus -eq "UP" -or $workerStatus -eq "READY") {
      return [pscustomobject]@{
        StatusRaw = $lastStatus
        StatusJson = $lastStatusJson
        HealthRaw = $lastHealth
        HealthJson = $lastHealthJson
      }
    }

    Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
  }

  Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Timed out waiting for backend readiness within ${TimeoutSec}s. LastError=" + $lastError)
  if ($lastStatus) { Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Last /api/status (" + $lastStatus.StatusCode + "): " + $lastStatus.Body) }
  if ($lastHealth) { Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Last /api/health (" + $lastHealth.StatusCode + "): " + $lastHealth.Body) }
  throw "Timed out waiting for backend readiness within ${TimeoutSec}s. Evidence=$EvidenceFile"
}

$resolvedEvidenceDir = Resolve-PathRelative -Path $EvidenceDir
if (-not $resolvedEvidenceDir) {
  $resolvedEvidenceDir = Join-Path -Path $repoRoot -ChildPath $EvidenceDir
}
New-Item -ItemType Directory -Force -Path $resolvedEvidenceDir | Out-Null

$resolvedEvidenceBundleOutRoot = $null
if ($EvidenceBundleOutRoot) {
  $resolvedEvidenceBundleOutRoot = Resolve-PathRelative -Path $EvidenceBundleOutRoot
} else {
  $resolvedEvidenceBundleOutRoot = Join-Path -Path $resolvedEvidenceDir -ChildPath "agent-evidence"
}

if (-not $SkipBuild.IsPresent -and $BuildAndVerify.IsPresent) {
  Push-Location $repoRoot
  try {
    # Preflight: ensure rustup shims take precedence over any system-installed Rust (e.g., chocolatey GNU toolchain).
    # This avoids Tauri builds accidentally linking with MinGW when MSVC is required.
    $rustupBin = Join-Path -Path $env:USERPROFILE -ChildPath ".cargo\\bin"
    if (Test-Path -LiteralPath $rustupBin) {
      $env:Path = "$rustupBin;$env:Path"
    }

    # Preflight: require MSVC Rust host triple (GNU fails at link time for Tauri on Windows).
    $hostLine = (& rustc -Vv 2>$null | Select-String -Pattern '^host:\s+' | Select-Object -First 1).Line
    if ($hostLine -match '^host:\s*(\S+)\s*$') {
      # NOTE: PowerShell variables are case-insensitive; `$Host` is a built-in read-only variable.
      # Do not use `$host` here.
      $rustHost = $Matches[1]
      if ($rustHost -like '*windows-gnu') {
        throw "Rust host triple is '$rustHost' (GNU). Tauri on Windows requires MSVC. Fix: rustup default stable-x86_64-pc-windows-msvc (and ensure %USERPROFILE%\\.cargo\\bin is first in PATH)."
      }
    }

    # Build NSIS installer (tauri.conf.json now enforces ui-web build + sidecar bundle via hooks).
    # NOTE: dev/CI smoke builds must not require Windows SDK SignTool. Release pipelines sign separately.
    Invoke-External -FilePath "npm" -Arguments @("--prefix", ".\\modules\\shell", "run", "tauri", "--", "build", "--bundles", "nsis", "--no-sign")
  } finally {
    Pop-Location
  }
}

if ($SetupExePath) {
  $SetupExePath = Resolve-PathRelative -Path $SetupExePath
}

if (-not $SetupExePath) {
  $nsisOutDir = Join-Path -Path $repoRoot -ChildPath "modules\\shell\\src-tauri\\target\\release\\bundle\\nsis"
  $newest = Find-NewestFile -Path $nsisOutDir -Filter "*-setup.exe"
  Assert ($null -ne $newest) "No NSIS installer found at $nsisOutDir (expected *-setup.exe). Run with -BuildAndVerify or provide -SetupExePath."
  $SetupExePath = $newest.FullName
}

Assert (Test-Path -LiteralPath $SetupExePath) "Setup exe not found: $SetupExePath"

if (-not $InstallDir) {
  $InstallDir = New-TempDirNoSpaces -Prefix "JustSearch-nsis-install"
} else {
  if ($InstallDir -match "\s") {
    throw "InstallDir must not contain spaces for robust NSIS /D handling: '$InstallDir'"
  }
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

$dataDir = New-TempDirNoSpaces -Prefix "JustSearch-installer-data"
$evidenceFile = Join-Path -Path $resolvedEvidenceDir -ChildPath ("verify-installer-nsis-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
"" | Set-Content -LiteralPath $evidenceFile -Encoding UTF8

$headlessProc = $null
$installedUninstaller = $null
# Pre-initialize so the finally block can read these even if the try
# throws early (Set-StrictMode -Version Latest above would otherwise
# mask the real error with "variable not set" on finally's $port/$client
# access).
$port = 0
$client = $null

$mainError = $null
try {
  Write-Host "Installer: $SetupExePath"
  Write-Host "InstallDir: $InstallDir"
  Write-Host "DataDir:    $dataDir"
  Write-Host "Evidence:   $evidenceFile"

  # ---------------------------------------------------------------------------
  # 1) Silent NSIS install (per-user, no admin). /D must be last argument.
  # ---------------------------------------------------------------------------
  $installArgs = @("/S", "/D=$InstallDir")
  $installProc = Start-Process -FilePath $SetupExePath -ArgumentList $installArgs -Wait -PassThru
  Assert ($installProc.ExitCode -eq 0) "NSIS installer failed (exit=$($installProc.ExitCode)). Evidence=$evidenceFile"

  # ---------------------------------------------------------------------------
  # 2) Locate resources/headless by searching for ui-headless.jar
  # ---------------------------------------------------------------------------
  $uiJar = Get-ChildItem -LiteralPath $InstallDir -Filter "ui-headless.jar" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
  Assert ($null -ne $uiJar) "Installed payload missing ui-headless.jar under $InstallDir"
  $headlessDir = Split-Path -Parent $uiJar.FullName

  # ---------------------------------------------------------------------------
  # 3) Validate expected sidecar files exist
  # ---------------------------------------------------------------------------
  $javaBin = Join-Path -Path $headlessDir -ChildPath "runtime\\bin\\java.exe"
  # Worker is shipped via installDist (tempdoc 226) -- flat `lib/worker/*.jar`, not a fat `lib/worker.jar`.
  $workerLibDir = Join-Path -Path $headlessDir -ChildPath "lib\\worker"
  $configPath = Join-Path -Path $headlessDir -ChildPath "config\\application.yaml"
  $ssotPath = Join-Path -Path $headlessDir -ChildPath "SSOT"
  $manifestPath = Join-Path -Path $ssotPath -ChildPath "manifest.v1.json"
  $pluginsManifest = Join-Path -Path $ssotPath -ChildPath "manifests\\plugins\\pipeline-stage-plugins.v1.json"

  # v1 Simple Mode: bundled llama-server payload must include required DLLs (exe-only fails in Windows Sandbox).
  $llamaDir = Join-Path -Path $headlessDir -ChildPath "native-bin\\llama-server"
  $llamaExe = Join-Path -Path $llamaDir -ChildPath "llama-server.exe"
  Assert (Test-Path -LiteralPath $llamaExe) "Missing bundled llama-server.exe: $llamaExe"
  # NOTE: We bundle the pinned upstream Windows CPU build from ggml-org/llama.cpp (b8157), which ships
  # multiple cpu backend DLLs (ggml-cpu-*.dll), libomp, and requires msvcp140_codecvt_ids.dll.
  # Note: libcurl-x64.dll was removed from upstream releases starting at b8157.
  $requiredFiles = @(
    "llama.dll",
    "ggml.dll",
    "ggml-base.dll",
    "mtmd.dll",
    "libomp140.x86_64.dll",
    "msvcp140_codecvt_ids.dll",
    "runtime-version.txt"
  )
  $missingFiles = @()
  foreach ($f in $requiredFiles) {
    $p = Join-Path -Path $llamaDir -ChildPath $f
    if (-not (Test-Path -LiteralPath $p)) { $missingFiles += $f }
  }
  $cpuBackends = Get-ChildItem -LiteralPath $llamaDir -Filter "ggml-cpu*.dll" -File -ErrorAction SilentlyContinue
  if ($missingFiles.Count -gt 0 -or -not $cpuBackends -or $cpuBackends.Count -lt 1) {
    $present = Get-ChildItem -LiteralPath $llamaDir -File -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -ExpandProperty Name
    $cpuMsg = if (-not $cpuBackends -or $cpuBackends.Count -lt 1) { " Missing ggml-cpu*.dll backends." } else { "" }
    throw "Bundled llama-server payload incomplete.$cpuMsg Missing: $($missingFiles -join ', '). Present: $($present -join ', '). Dir=$llamaDir"
  }

  Assert (Test-Path -LiteralPath $javaBin) "Missing bundled java runtime: $javaBin"
  Assert (Test-Path -LiteralPath $workerLibDir) "Missing worker classpath dir in bundle: $workerLibDir (expected installDist layout `lib/worker/*.jar` per tempdoc 226)"
  $workerJarCount = (Get-ChildItem -LiteralPath $workerLibDir -Filter "*.jar" -File -ErrorAction SilentlyContinue | Measure-Object).Count
  # installDist layout produced 176 JARs at 2026-04-24; 50 is a safe floor that would still catch a broken bundle while tolerating dependency churn.
  Assert ($workerJarCount -ge 50) "Worker classpath dir has only $workerJarCount JARs at $workerLibDir (expected >= 50 -- installDist layout per tempdoc 226)"
  Assert (Test-Path -LiteralPath $configPath) "Missing config/application.yaml in bundle: $configPath"
  Assert (Test-Path -LiteralPath $manifestPath) "Missing SSOT/manifest.v1.json in bundle: $manifestPath"
  Assert (Test-Path -LiteralPath $pluginsManifest) "Missing plugins manifest in bundle: $pluginsManifest"

  # Optional: sanity check config keeps AI disabled by default.
  try {
    $cfg = Get-Content -LiteralPath $configPath -Raw -ErrorAction Stop
    if ($cfg -notmatch '(?m)^[\s#]*llm:\s*\r?\n[\s#]*enabled:\s*false\s*$') {
      Add-Content -LiteralPath $evidenceFile -Value "WARN: config/application.yaml did not match expected 'llm.enabled: false' pattern (best-effort check)."
    }
  } catch {
    Add-Content -LiteralPath $evidenceFile -Value "WARN: unable to read config/application.yaml for sanity check: $($_.Exception.Message)"
  }

  # Best-effort locate uninstaller for cleanup.
  $installedUninstaller = Get-ChildItem -LiteralPath $InstallDir -Filter "*uninstall*.exe" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($installedUninstaller) {
    Add-Content -LiteralPath $evidenceFile -Value ("INFO: Found uninstaller: " + $installedUninstaller.FullName)
  } else {
    Add-Content -LiteralPath $evidenceFile -Value "WARN: Could not find uninstaller exe under install dir (cleanup will be best-effort)."
  }

  # ---------------------------------------------------------------------------
  # 4) Boot the bundled backend from installed payload and assert readiness
  # ---------------------------------------------------------------------------
  $libDir = Join-Path -Path $headlessDir -ChildPath "lib"
  $libGlob = Join-Path -Path $libDir -ChildPath "*"
  $cp = "$($uiJar.FullName);$libGlob"
  $javaArgs = @(
    "-Djustsearch.prod=true",
    "-Djustsearch.data.dir=$dataDir",
    "-Djustsearch.home=$dataDir",
    "-Djustsearch.ui.settings.mode=IN_MEMORY",
    "-Djustsearch.config=$configPath",
    "-Djustsearch.repo.root=$headlessDir",
    "-Djustsearch.ssot.path=$ssotPath",
    "-Djustsearch.plugins.manifest=$pluginsManifest",
    "-cp",
    $cp,
    "io.justsearch.ui.HeadlessApp"
  )
  $argString = $javaArgs -join " "

  $headlessStdout = Join-Path -Path $resolvedEvidenceDir -ChildPath ("headless-backend-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".stdout.log")
  $headlessStderr = Join-Path -Path $resolvedEvidenceDir -ChildPath ("headless-backend-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".stderr.log")
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: Starting headless backend: " + $javaBin + " " + $argString)
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: Headless stdout -> " + $headlessStdout)
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: Headless stderr -> " + $headlessStderr)

  $headlessProc = Start-Process `
    -FilePath $javaBin `
    -WorkingDirectory $headlessDir `
    -ArgumentList $javaArgs `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $headlessStdout `
    -RedirectStandardError $headlessStderr

  $deadline = [DateTime]::UtcNow.AddSeconds($PortTimeoutSec)
  $port = 0
  while ([DateTime]::UtcNow -lt $deadline) {
    Add-StdoutSentinelParse -Det $det
    # Scan only the tail to avoid rereading large logs.
    $tail = @()
    if (Test-Path -LiteralPath $headlessStdout) {
      $tail = Get-Content -LiteralPath $headlessStdout -Tail 200 -ErrorAction SilentlyContinue
    }
    foreach ($line in $tail) {
      if ($line -match '^JUSTSEARCH_API_PORT=(\d+)$') {
        $port = [int]$Matches[1]
      }
    }
    if ($port -gt 0) { break }
    if ($headlessProc -and $headlessProc.HasExited) { break }
    Add-BackoffSleep -Det $det -Reason "wait_for_port_sentinel" -Ms 200
  }

  if ($port -le 0) {
    Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Did not observe JUSTSEARCH_API_PORT within ${PortTimeoutSec}s.")
    try {
      if (Test-Path -LiteralPath $headlessStdout) {
        Add-Content -LiteralPath $evidenceFile -Value "---- headless stdout (tail 200) ----"
        Add-Content -LiteralPath $evidenceFile -Value (Get-Content -LiteralPath $headlessStdout -Tail 200 -ErrorAction SilentlyContinue)
      }
      if (Test-Path -LiteralPath $headlessStderr) {
        Add-Content -LiteralPath $evidenceFile -Value "---- headless stderr (tail 200) ----"
        Add-Content -LiteralPath $evidenceFile -Value (Get-Content -LiteralPath $headlessStderr -Tail 200 -ErrorAction SilentlyContinue)
      }
    } catch { }
    throw "Headless backend did not emit JUSTSEARCH_API_PORT within ${PortTimeoutSec}s. Evidence=$evidenceFile"
  }

  Write-Host "Backend port: $port"

  # ---------------------------------------------------------------------------
  # 5) Deterministic readiness assertions
  # ---------------------------------------------------------------------------
  $client = New-Object System.Net.Http.HttpClient
  $client.Timeout = [TimeSpan]::FromSeconds($HttpTimeoutSec)
  $ready = Wait-ForBackendReady -Det $det -Client $client -Port $port -TimeoutSec $ReadyTimeoutSec -EvidenceFile $evidenceFile
  $statusJson = $ready.StatusJson
  $healthJson = $ready.HealthJson

  # Loopback bind check (best effort).
  $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
  Assert ($listeners.Count -gt 0) "Expected a LISTEN socket on port $port (Get-NetTCPConnection returned none)."
  $nonLoopback = @($listeners | Where-Object { $_.LocalAddress -ne "127.0.0.1" -and $_.LocalAddress -ne "::1" })
  Assert ($nonLoopback.Count -eq 0) ("Expected loopback-only bind on port $port, got: " + ($listeners | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" } | Sort-Object | Out-String))

  # CORS posture checks (prod mode).
  # Tauri app origins differ by runtime version:
  # - Tauri v1:  tauri://localhost
  # - Tauri v2+: https://tauri.localhost
  foreach ($allowedOrigin in @("tauri://localhost", "https://tauri.localhost", "http://tauri.localhost")) {
    $optAllowed = Send-Options -Client $client -Uri ("http://127.0.0.1:$port/api/status") -Origin $allowedOrigin
    Assert ($optAllowed.StatusCode -ge 200 -and $optAllowed.StatusCode -lt 300) "Expected preflight from $allowedOrigin to succeed, got $($optAllowed.StatusCode). Body=$($optAllowed.Body)"
    $acaosAllowed = @(Get-HeaderValuesOrEmpty -Headers $optAllowed.Headers -Name "Access-Control-Allow-Origin")
    Assert ($acaosAllowed.Count -eq 1 -and $acaosAllowed[0] -eq $allowedOrigin) ("Expected Access-Control-Allow-Origin=$allowedOrigin, got: " + ($acaosAllowed -join ", "))
  }

  foreach ($blockedOrigin in @("http://localhost:5173", "http://127.0.0.1:5173")) {
    $optBrowser = Send-Options -Client $client -Uri ("http://127.0.0.1:$port/api/status") -Origin $blockedOrigin
    Assert ($optBrowser.StatusCode -eq 403) "Expected preflight from $blockedOrigin to be rejected with 403 in prod, got $($optBrowser.StatusCode). Body=$($optBrowser.Body)"
    $acaos = @(Get-HeaderValuesOrEmpty -Headers $optBrowser.Headers -Name "Access-Control-Allow-Origin")
    Assert ($acaos.Count -eq 0) ("Expected no Access-Control-Allow-Origin header for blocked origin $blockedOrigin, got: " + ($acaos -join ", "))
  }

  Write-Host "PASS: NSIS installer payload boots and backend readiness checks passed."

} catch {
  $mainError = $_
  throw
} finally {
  # ---------------------------------------------------------------------------
  # EvidenceBundle v1 capture (optional; run BEFORE uninstall/teardown so backend is alive)
  # ---------------------------------------------------------------------------
  $bundleCaptureError = $null
  $bundlePath = $null
  $captureEnabled = $CaptureEvidenceBundle.IsPresent -or (-not [string]::IsNullOrWhiteSpace($env:CI))
  if ($captureEnabled -and $port -gt 0) {
    try {
      New-Item -ItemType Directory -Force -Path $resolvedEvidenceBundleOutRoot | Out-Null

      $apiBaseUrl = "http://127.0.0.1:$port"
      $enforceDet = [bool]$EnforceDeterminismBudget.IsPresent -or ($env:JUSTSEARCH_DETERMINISM_ENFORCE -eq "1")

      # Snapshot harness-level determinism budget as an attachment (best-effort; run-metadata.json is produced by the capture script).
      $detFile = Join-Path -Path $resolvedEvidenceDir -ChildPath ("determinism-harness-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
      try { $null = (Enforce-DeterminismBudget -Det $det) } catch { }
      try { ($det | ConvertTo-Json -Depth 15) | Set-Content -LiteralPath $detFile -Encoding UTF8 } catch { $detFile = $null }

      $captureArgs = @(
        (Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"),
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

      foreach ($p in @($evidenceFile, $headlessStdout, $headlessStderr)) {
        if ($p -and (Test-Path -LiteralPath $p)) {
          $captureArgs += "--attach-file=$p"
        }
      }
      if ($detFile -and (Test-Path -LiteralPath $detFile)) {
        $captureArgs += "--attach-file=$detFile"
        $captureArgs += "--merge-determinism=$detFile"
      }

      if ($mainError) {
        $captureArgs += "--external-status=failed"
        $captureArgs += ("--external-error=Installer NSIS verification failed: " + $mainError.Exception.Message)
      } else {
        $captureArgs += "--external-status=passed"
      }
      Add-Content -LiteralPath $evidenceFile -Value ("INFO: Capturing EvidenceBundle v1 (scenario=$EvidenceScenario) ...")
      $bundlePathRaw = & node @captureArgs
      $capExit = $LASTEXITCODE
      $bundlePath = ([string]$bundlePathRaw).Trim()
      Add-Content -LiteralPath $evidenceFile -Value ("INFO: EvidenceBundle dir: " + $bundlePath)
      if ([string]::IsNullOrWhiteSpace($bundlePath)) {
        throw "EvidenceBundle capture produced no bundle path (exit=$capExit)."
      }
      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if (-not $mainError -and $capExit -ne 0) {
        throw "EvidenceBundle capture reported failed status (exit=$capExit). bundle=$bundlePath"
      }

      # Validate bundle invariants (hashing/layout/scope).
      $validator = Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs"
      & node $validator $bundlePath
      if ($LASTEXITCODE -ne 0) {
        throw "EvidenceBundle validator failed (exit=$LASTEXITCODE). bundle=$bundlePath"
      }

      # Validate determinism-budget invariants (policy enforcement; separate from structural EBv1 validation).
      $detValidator = Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs"
      & node $detValidator $bundlePath
      if ($LASTEXITCODE -ne 0) {
        if ($enforceDet) {
          throw "Determinism Budget validator failed (exit=$LASTEXITCODE). bundle=$bundlePath"
        } else {
          Add-Content -LiteralPath $evidenceFile -Value ("WARN: Determinism Budget validator failed (warn-only; re-run with -EnforceDeterminismBudget to gate). exit=$LASTEXITCODE bundle=$bundlePath")
        }
      }
      Add-Content -LiteralPath $evidenceFile -Value "INFO: EvidenceBundle v1 validation OK."
    } catch {
      $bundleCaptureError = $_.Exception.Message
      Add-Content -LiteralPath $evidenceFile -Value ("ERROR: EvidenceBundle capture/validation failed: " + $bundleCaptureError)
    }
  }

  if (-not $KeepInstallDir.IsPresent) {
    # Uninstall should be safe even when the backend is still running (DoD-4).
    # We intentionally run uninstall before killing the headless process so we can catch regressions
    # where the uninstaller leaks backend processes.
    if ($installedUninstaller) {
      try {
        $u = Start-Process -FilePath $installedUninstaller.FullName -ArgumentList @("/S") -Wait -PassThru
        if ($u.ExitCode -ne 0) {
          throw "Uninstaller exited with code $($u.ExitCode)"
        }
      } catch {
        Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Uninstall failed: " + $_.Exception.Message)
        throw
      }

      # Assert uninstall did not leave any processes running from the install directory.
      $leaked = $null
      try {
        $leaked = @(
          Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstallDir, [System.StringComparison]::OrdinalIgnoreCase) }
        )
      } catch {
        Add-Content -LiteralPath $evidenceFile -Value ("WARN: Unable to query leaked processes (best-effort): " + $_.Exception.Message)
        $leaked = $null
      }
      if ($leaked -ne $null -and $leaked.Count -gt 0) {
        Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Uninstaller left running processes from install dir: " + $InstallDir)
        foreach ($p in $leaked) {
          Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Leaked PID=" + $p.ProcessId + " EXE=" + $p.ExecutablePath)
        }
        # Cleanup so we don't strand processes on the build host.
        foreach ($p in $leaked) {
          try { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
        }
        throw "Uninstaller leaked running processes from install dir. Evidence=$evidenceFile"
      }

      # Verify uninstall cleaned up registry (best-effort, non-blocking).
      $uninstRegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\JustSearch"
      if (Test-Path $uninstRegKey) {
        Add-Content -LiteralPath $evidenceFile -Value "WARN: Uninstall left registry key: $uninstRegKey"
      } else {
        Add-Content -LiteralPath $evidenceFile -Value "INFO: Uninstall registry key removed OK."
      }

      # Verify shortcuts removed (best-effort, non-blocking).
      $startMenuLnk = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath "JustSearch\JustSearch.lnk"
      $desktopLnk = Join-Path -Path ([Environment]::GetFolderPath("Desktop")) -ChildPath "JustSearch.lnk"
      foreach ($lnk in @($startMenuLnk, $desktopLnk)) {
        if (Test-Path -LiteralPath $lnk) {
          Add-Content -LiteralPath $evidenceFile -Value ("WARN: Uninstall left shortcut: " + $lnk)
        }
      }

      # Verify Start Menu folder removed (best-effort, non-blocking).
      $startMenuFolder = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath "JustSearch"
      if (Test-Path -LiteralPath $startMenuFolder) {
        Add-Content -LiteralPath $evidenceFile -Value ("WARN: Uninstall left Start Menu folder: " + $startMenuFolder)
      }
    }
    try { Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}
  }

  # Teardown headless backend (kill process tree on Windows). This is a cleanup fallback.
  if ($headlessProc -and -not $headlessProc.HasExited) {
    try { & taskkill /PID $headlessProc.Id /T /F | Out-Null } catch {}
    try { $headlessProc.Kill() } catch {}
  }

  try { Remove-Item -LiteralPath $dataDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}

  # If the main run succeeded, make EvidenceBundle capture failures actionable without masking the real failure.
  if ($captureEnabled -and $bundleCaptureError -and -not $mainError) {
    throw "EvidenceBundle capture/validation failed: $bundleCaptureError. Evidence=$evidenceFile"
  }
}


