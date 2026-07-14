<#
.SYNOPSIS
    Captures a "capture-half" evidence snapshot of a running JustSearch backend
    inside a Windows Sandbox validation round (tempdoc 728).

.DESCRIPTION
    This script only CAPTURES raw data -- it does not interpret or judge it.
    Judgment (is the UI honest/scary, does the coverage look right) stays with
    the human/agent reviewing the evidence directory afterward.

    Steps:
      1. Discover the backend's API port (canonical: runtime manifest;
         fallback: listening-socket scan).
      2. Hit a fixed API sanity ladder and save each raw response body.
      3. Exercise the /mcp endpoint via the official MCP Inspector CLI (npx).
      4. Write a plain-text summary of what was captured.

    Windows PowerShell 5.1 compatible. Runs unattended -- no interactive
    prompts, no dialogs.

.PARAMETER EvidenceDir
    Directory to write evidence files into. Created if missing. Default: .\evidence

.PARAMETER DataDir
    JustSearch data directory containing runtime\manifest.json.
    Default: $env:APPDATA\io.justsearch.shell
#>

param(
    [string]$EvidenceDir = ".\evidence",
    [string]$DataDir = "$env:APPDATA\io.justsearch.shell"
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Host "[collect-evidence] $Message"
}

function Get-SanitizedFileName {
    param([string]$ApiPath)
    $sanitized = $ApiPath -replace '^/', '' -replace '/', '-'
    if ([string]::IsNullOrEmpty($sanitized)) {
        $sanitized = "root"
    }
    return $sanitized
}

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $EvidenceDir)) {
    New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null
}
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).ProviderPath

$timestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ss.fffK"
Write-Log "Starting evidence collection at $timestamp"
Write-Log "EvidenceDir: $EvidenceDir"
Write-Log "DataDir: $DataDir"

# ---------------------------------------------------------------------------
# Step 1: Port discovery (canonical: runtime manifest.json; tempdoc 501)
# ---------------------------------------------------------------------------

$port = $null
$portSource = $null
$portDiscoveryNote = ""

$manifestPath = Join-Path -Path $DataDir -ChildPath "runtime\manifest.json"

if (Test-Path -LiteralPath $manifestPath) {
    try {
        $manifestRaw = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop
        $manifest = $manifestRaw | ConvertFrom-Json -ErrorAction Stop
        if ($manifest.head -and $manifest.head.apiPort) {
            $port = [int]$manifest.head.apiPort
            $portSource = "manifest:$manifestPath"
            Write-Log "Resolved apiPort $port from runtime manifest."
        }
        else {
            $portDiscoveryNote = "manifest.json found at $manifestPath but .head.apiPort was missing/empty."
            Write-Log $portDiscoveryNote
        }
    }
    catch {
        $portDiscoveryNote = "Failed to read/parse manifest.json at $manifestPath : $($_.Exception.Message)"
        Write-Log $portDiscoveryNote
    }
}
else {
    $portDiscoveryNote = "manifest.json not found at $manifestPath."
    Write-Log $portDiscoveryNote
}

$fallbackCandidates = @()
if (-not $port) {
    Write-Log "Falling back to listening-socket scan (Get-NetTCPConnection)."
    try {
        $connections = Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { $_.LocalAddress -eq '127.0.0.1' }
        $fallbackCandidates = $connections | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object
        if ($fallbackCandidates -and $fallbackCandidates.Count -gt 0) {
            Write-Log "Candidate loopback listening ports found: $($fallbackCandidates -join ', ')"
            Write-Log "Not guessing which one is the backend -- recording candidates, not selecting automatically."
        }
        else {
            Write-Log "No loopback listening ports found."
        }
    }
    catch {
        Write-Log "Get-NetTCPConnection fallback failed: $($_.Exception.Message)"
    }
}

if (-not $port) {
    $errorLines = @()
    $errorLines += "ERROR: could not determine the backend API port."
    $errorLines += "Manifest lookup: $portDiscoveryNote"
    if ($fallbackCandidates -and $fallbackCandidates.Count -gt 0) {
        $errorLines += "Fallback candidate loopback listening ports (unconfirmed): $($fallbackCandidates -join ', ')"
        $errorLines += "None of these were assumed to be the backend port -- inspect manually and re-run with a known port if needed."
    }
    else {
        $errorLines += "Fallback listening-socket scan found no loopback listeners either."
    }
    $errorText = $errorLines -join "`r`n"
    Write-Host $errorText
    $summaryPath = Join-Path -Path $EvidenceDir -ChildPath "collect-evidence-summary.txt"
    $summaryLines = @()
    $summaryLines += "JustSearch Sandbox evidence collection summary"
    $summaryLines += "Timestamp: $timestamp"
    $summaryLines += "Resolved port: NONE"
    $summaryLines += $errorText
    ($summaryLines -join "`r`n") | Out-File -LiteralPath $summaryPath -Encoding utf8
    exit 1
}

$base = "http://127.0.0.1:$port"
Write-Log "Using base URL: $base (source: $portSource)"

# ---------------------------------------------------------------------------
# Step 2: API sanity ladder
# ---------------------------------------------------------------------------

$ladderPaths = @(
    "/api/health",
    "/api/status",
    "/api/knowledge/status",
    "/api/ai/runtime/status",
    "/api/inference/status",
    "/api/ai/install/status"
)

$ladderResults = @()

foreach ($apiPath in $ladderPaths) {
    $fileName = "api-" + (Get-SanitizedFileName -ApiPath $apiPath) + ".json"
    $outFile = Join-Path -Path $EvidenceDir -ChildPath $fileName
    $url = "$base$apiPath"
    Write-Log "GET $url"

    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -ErrorAction Stop
        $statusCode = [int]$response.StatusCode
        $body = $response.Content
        $body | Out-File -LiteralPath $outFile -Encoding utf8
        $ok = ($statusCode -ge 200) -and ($statusCode -lt 300)
        $ladderResults += New-Object PSObject -Property @{
            Path       = $apiPath
            StatusCode = $statusCode
            Ok         = $ok
            File       = $fileName
        }
        Write-Log "  -> $statusCode saved to $fileName"
    }
    catch {
        $statusCode = $null
        $exceptionMessage = $_.Exception.Message
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
                $statusCode = $null
            }
        }

        $errorBody = ""
        try {
            if ($_.Exception.Response -ne $null) {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($stream -ne $null) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $errorBody = $reader.ReadToEnd()
                    $reader.Close()
                }
            }
        }
        catch {
            $errorBody = ""
        }

        $errorRecord = New-Object PSObject -Property @{
            path        = $apiPath
            url         = $url
            statusCode  = $statusCode
            exception   = $exceptionMessage
            responseBody = $errorBody
        }
        ($errorRecord | ConvertTo-Json -Depth 5) | Out-File -LiteralPath $outFile -Encoding utf8

        $ladderResults += New-Object PSObject -Property @{
            Path       = $apiPath
            StatusCode = $statusCode
            Ok         = $false
            File       = $fileName
        }
        Write-Log "  -> ERROR ($exceptionMessage) recorded to $fileName"
    }
}

# ---------------------------------------------------------------------------
# Step 3: MCP endpoint check via the official MCP Inspector CLI (npx, MIT)
# ---------------------------------------------------------------------------

$mcpUrl = "$base/mcp"
$mcpRan = $false
$mcpExitCode = $null
$mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"

$npxCommand = Get-Command "npx" -ErrorAction SilentlyContinue

if ($npxCommand -eq $null) {
    $note = "MCP Inspector CLI was NOT exercised: 'npx' was not found on PATH in this sandbox. " +
            "Node.js/npm may not be installed. This is a recorded gap, not a fatal error."
    Write-Log $note
    $note | Out-File -LiteralPath $mcpOutFile -Encoding utf8
}
else {
    Write-Log "Running MCP Inspector CLI against $mcpUrl"
    $inspectorArgs = @(
        "@modelcontextprotocol/inspector",
        "--cli",
        $mcpUrl,
        "--transport",
        "http",
        "--method",
        "tools/list"
    )

    try {
        $stdoutFile = [System.IO.Path]::GetTempFileName()
        $stderrFile = [System.IO.Path]::GetTempFileName()

        $process = Start-Process -FilePath "npx" -ArgumentList $inspectorArgs `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile

        $mcpExitCode = $process.ExitCode
        $stdoutContent = Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue
        $stderrContent = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stdoutFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue

        $combined = "exit code: $mcpExitCode`r`n--- stdout ---`r`n$stdoutContent`r`n--- stderr ---`r`n$stderrContent"

        $isJson = $false
        if ($stdoutContent -ne $null) {
            $trimmed = $stdoutContent.Trim()
            if ($trimmed.StartsWith("{") -or $trimmed.StartsWith("[")) {
                try {
                    $null = $trimmed | ConvertFrom-Json -ErrorAction Stop
                    $isJson = $true
                }
                catch {
                    $isJson = $false
                }
            }
        }

        if ($isJson) {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"
            $combined | Out-File -LiteralPath $mcpOutFile -Encoding utf8
        }
        else {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.txt"
            $combined | Out-File -LiteralPath $mcpOutFile -Encoding utf8
        }

        $mcpRan = $true
        Write-Log "MCP Inspector CLI finished with exit code $mcpExitCode; output saved to $(Split-Path -Leaf $mcpOutFile)"
    }
    catch {
        $note = "MCP Inspector CLI invocation failed: $($_.Exception.Message)"
        Write-Log $note
        $note | Out-File -LiteralPath $mcpOutFile -Encoding utf8
        $mcpRan = $false
    }
}

# ---------------------------------------------------------------------------
# Step 3.5: Persist the request-trace file past sandbox teardown (F4)
# ---------------------------------------------------------------------------
# traces.ndjson lives in the ephemeral sandbox data dir and is wiped on
# shutdown; copy it into the (mapped, host-persisted) evidence dir so the
# host-side finalize coverage check (check_coverage.py --traces) can read it
# after the VM closes. Requires JUSTSEARCH_HEAD_TRACING_LEVEL=detailed to have
# been set before the app launched (the .wsb LogonCommand does this).

$tracesSrc = Join-Path -Path $DataDir -ChildPath "telemetry\traces.ndjson"
$tracesDst = Join-Path -Path $EvidenceDir -ChildPath "traces.ndjson"
$tracesCopied = $false
if (Test-Path -LiteralPath $tracesSrc) {
    try {
        Copy-Item -LiteralPath $tracesSrc -Destination $tracesDst -Force -ErrorAction Stop
        $tracesCopied = $true
        Write-Log "Copied traces.ndjson into evidence dir (for host-side coverage check)."
    }
    catch {
        Write-Log "Failed to copy traces.ndjson: $($_.Exception.Message)"
    }
}
else {
    Write-Log "traces.ndjson not found at $tracesSrc -- was JUSTSEARCH_HEAD_TRACING_LEVEL=detailed set before launch?"
}

# ---------------------------------------------------------------------------
# Step 4: Summary
# ---------------------------------------------------------------------------

$summaryPath = Join-Path -Path $EvidenceDir -ChildPath "collect-evidence-summary.txt"
$summaryLines = @()
$summaryLines += "JustSearch Sandbox evidence collection summary"
$summaryLines += "Timestamp: $timestamp"
$summaryLines += "Resolved port: $port (source: $portSource)"
$summaryLines += "Base URL: $base"
$summaryLines += ""
$summaryLines += "API sanity ladder:"
foreach ($result in $ladderResults) {
    $statusText = if ($result.StatusCode -eq $null) { "ERROR" } else { $result.StatusCode }
    $okText = if ($result.Ok) { "2xx" } else { "error/non-2xx" }
    $summaryLines += ("  {0,-30} -> {1} ({2}) [{3}]" -f $result.Path, $statusText, $okText, $result.File)
}
$summaryLines += ""
if ($mcpRan) {
    $summaryLines += "MCP Inspector CLI: RAN (exit code $mcpExitCode) -> $(Split-Path -Leaf $mcpOutFile)"
}
else {
    $summaryLines += "MCP Inspector CLI: NOT RUN (gap recorded) -> $(Split-Path -Leaf $mcpOutFile)"
}
$summaryLines += ""
if ($tracesCopied) {
    $summaryLines += "Request traces: COPIED -> traces.ndjson (host-side coverage check input)"
}
else {
    $summaryLines += "Request traces: NOT COPIED (traces.ndjson absent -- tracing may not have been enabled)"
}

($summaryLines -join "`r`n") | Out-File -LiteralPath $summaryPath -Encoding utf8

$okCount = ($ladderResults | Where-Object { $_.Ok }).Count
$totalCount = $ladderResults.Count
Write-Host "[collect-evidence] Done. Port=$port Ladder=$okCount/$totalCount 2xx MCP-ran=$mcpRan EvidenceDir=$EvidenceDir"

exit 0
