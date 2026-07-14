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

function Write-Utf8NoBom {
    # Windows PowerShell 5.1's `Out-File -Encoding utf8` still writes a UTF-8
    # BOM (ef bb bf), which broke the host-side checkers' plain `open(path,
    # encoding="utf-8")` reads (JSONDecodeError: Expecting value: line 1
    # column 1 -- a false "no fingerprint" BLOCKING verdict against a round
    # that was actually fine). The checkers are now BOM-tolerant
    # (utf-8-sig), but this writer-side fix keeps new evidence BOM-free too,
    # so any other consumer of these files doesn't hit the same trap.
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true, ValueFromPipeline = $true)][AllowEmptyString()][string]$Content
    )
    process {
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
    }
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
# Step 0: Elevation self-check (D3, tempdoc 728-followup)
# ---------------------------------------------------------------------------
# sandbox-CLAUDE.md's UAC announce-and-attribute protocol relies on the
# operator being the only sensor for a UAC prompt during the JustSearch
# install -- but if THIS session's own terminal is already running elevated,
# no UAC prompt can appear at all regardless of what the installer requests
# (Windows does not re-prompt an already-elevated process tree). A round
# running elevated silently produces nothing for that protocol item, which
# reads indistinguishably from "no prompt appeared, so it passed." Detect
# and record this so the round reports "structurally unobservable" instead.
# Same detection pattern as scripts/bench/_lib/launch-elevated.ps1.
$isElevated = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($isElevated) {
    $elevationNote = "ELEVATED: this session's terminal is running with Administrator " +
        "privileges. The UAC announce-and-attribute protocol (sandbox-CLAUDE.md) CANNOT " +
        "observe a UAC prompt during the JustSearch install this round -- an already-" +
        "elevated process tree is never re-prompted by UAC, regardless of whether the " +
        "installer requests elevation. Record the UAC item as STRUCTURALLY UNOBSERVABLE " +
        "this round (with this reason), not as a pass or as silence. The structural fact " +
        "that substitutes for it -- the installer is per-user " +
        "(bundle.windows.nsis.installMode: currentUser, ADR-0024) and requests no " +
        "elevation -- is asserted mechanically, independent of sandbox elevation state, " +
        "by scripts/ci/check-installer-execution-level.mjs (proves only that the " +
        "installer doesn't request elevation; says nothing about SmartScreen/publisher " +
        "trust)."
}
else {
    $elevationNote = "NOT ELEVATED: this session's terminal is running as a standard " +
        "user. A UAC prompt during the JustSearch install, if one appears, IS observable " +
        "this round per the announce-and-attribute protocol."
}
Write-Log $elevationNote
$elevationNote | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "elevation-check.txt")

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
    ($summaryLines -join "`r`n") | Write-Utf8NoBom -Path $summaryPath
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
        $body | Write-Utf8NoBom -Path $outFile
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
        ($errorRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $outFile

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
$mcpToolNames = @()
$mcpNote = ""
$mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"

$npxCommand = Get-Command "npx" -ErrorAction SilentlyContinue

if ($npxCommand -eq $null) {
    # Self-repair before declaring Node absent: a clean-environment race (Node
    # installed mid-session by a PATH-mutating MSI, but this PowerShell process
    # started before that MSI ran) leaves node.exe genuinely ON DISK while this
    # session's PATH is stale. A prior smoke round hit exactly this and lost
    # all /mcp coverage to a false "Node.js may not be installed" diagnostic
    # (tempdoc 727-followup). Probe well-known install dirs plus the MACHINE
    # (not just session) PATH, and repair the session PATH if found, before
    # concluding Node is missing.
    $repaired = $false
    $wellKnownNodeDirs = @("C:\Program Files\nodejs", "C:\Program Files (x86)\nodejs")
    foreach ($dir in $wellKnownNodeDirs) {
        if ((Test-Path -LiteralPath (Join-Path $dir "node.exe")) -and ($env:Path -notlike "*$dir*")) {
            $env:Path = "$env:Path;$dir"
            Write-Log "Found node.exe at $dir (not on session PATH) -- prepending to session PATH."
            $repaired = $true
        }
    }
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($machinePath) {
        foreach ($segment in $machinePath -split ";") {
            if ($segment -and ($segment -like "*nodejs*") -and (Test-Path -LiteralPath (Join-Path $segment "node.exe") -ErrorAction SilentlyContinue) -and ($env:Path -notlike "*$segment*")) {
                $env:Path = "$env:Path;$segment"
                Write-Log "Found node.exe via machine PATH at $segment (not on session PATH) -- prepending to session PATH."
                $repaired = $true
            }
        }
    }

    if ($repaired) {
        $npxCommand = Get-Command "npx" -ErrorAction SilentlyContinue
    }
}

if ($npxCommand -eq $null) {
    $nodeOnDisk = (Test-Path -LiteralPath "C:\Program Files\nodejs\node.exe") -or (Test-Path -LiteralPath "C:\Program Files (x86)\nodejs\node.exe")
    if ($nodeOnDisk) {
        $note = "MCP Inspector CLI was NOT exercised: npx exists on disk but was not on this " +
                "session's PATH (session likely started before Node install) -- PATH repair was " +
                "attempted but did not resolve npx. This is a harness/environment-timing gap, not " +
                "evidence that Node.js is missing."
    }
    else {
        $note = "MCP Inspector CLI was NOT exercised: 'npx' was not found on PATH in this sandbox, " +
                "and node.exe was not found in well-known install dirs either. Node.js/npm may " +
                "genuinely not be installed. This is a recorded gap, not a fatal error."
    }
    Write-Log $note
    $note | Write-Utf8NoBom -Path $mcpOutFile
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

        # 'npx' is a shell shim (npx.cmd), not a real PE. Start-Process -FilePath goes
        # straight to Win32 CreateProcess, which cannot execute an extensionless shim --
        # that always fails with "%1 is not a valid Win32 application" even when Node is
        # installed correctly (see 08-collect-evidence-npx-bug.md). Resolve the real
        # dispatchable form: prefer npx.cmd directly; fall back to cmd.exe /c npx.
        $npxCmdCommand = Get-Command "npx.cmd" -ErrorAction SilentlyContinue
        if ($npxCmdCommand -ne $null) {
            $process = Start-Process -FilePath $npxCmdCommand.Source -ArgumentList $inspectorArgs `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        }
        else {
            Write-Log "npx.cmd not found on PATH; falling back to 'cmd.exe /c npx'."
            $cmdArgs = @("/c", "npx") + $inspectorArgs
            $process = Start-Process -FilePath "cmd.exe" -ArgumentList $cmdArgs `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        }

        $mcpExitCode = $process.ExitCode
        $stdoutContent = Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue
        $stderrContent = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stdoutFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue

        # Success criterion (734 follow-up, live-verified): the Inspector CLI
        # returns complete, valid tools/list JSON on stdout and THEN crashes in
        # Node teardown on Windows (libuv assertion
        # "!(handle->flags & UV_HANDLE_CLOSING)", exit 0xC0000409) -- a nonzero
        # exit with a fully successful call. Judge success from stdout content,
        # not the exit code; still log the exit code and flag the known-benign
        # crash separately. A genuine failure (no/invalid stdout, no tools[])
        # still reads as a failure.
        $isJson = $false
        $parsedJson = $null
        if ($stdoutContent -ne $null) {
            $trimmed = $stdoutContent.Trim()
            if ($trimmed.StartsWith("{") -or $trimmed.StartsWith("[")) {
                try {
                    $parsedJson = $trimmed | ConvertFrom-Json -ErrorAction Stop
                    $isJson = $true
                }
                catch {
                    $isJson = $false
                }
            }
        }

        $mcpSuccess = $false
        if ($isJson -and $parsedJson -ne $null -and ($parsedJson.PSObject.Properties.Name -contains "tools")) {
            $toolsArray = @($parsedJson.tools)
            if ($toolsArray.Count -gt 0) {
                $mcpSuccess = $true
                $mcpToolNames = $toolsArray | ForEach-Object { $_.name }
            }
        }

        if ($mcpSuccess -and $mcpExitCode -ne 0) {
            $mcpNote = "Note: process exited nonzero (exit code $mcpExitCode) AFTER returning valid tools/list JSON on stdout -- this is the known-benign Node teardown crash on Windows (libuv assertion '!(handle->flags & UV_HANDLE_CLOSING)', exit 0xC0000409), not a real failure. Judged SUCCESS from stdout content, per the 734 follow-up fix."
            Write-Log $mcpNote
        }

        $toolNamesText = if ($mcpToolNames.Count -gt 0) { ($mcpToolNames -join ", ") } else { "(none)" }
        $combined = "exit code: $mcpExitCode`r`n--- stdout ---`r`n$stdoutContent`r`n--- stderr ---`r`n$stderrContent"
        if ($mcpSuccess) {
            $combined = "SUCCESS -- tools discovered: $toolNamesText`r`n`r`n$combined"
        }
        if ($mcpNote -ne "") {
            $combined = "$mcpNote`r`n`r`n$combined"
        }

        if ($isJson) {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"
            $combined | Write-Utf8NoBom -Path $mcpOutFile
        }
        else {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.txt"
            $combined | Write-Utf8NoBom -Path $mcpOutFile
        }

        $mcpRan = $mcpSuccess
        Write-Log "MCP Inspector CLI finished with exit code $mcpExitCode; success=$mcpSuccess (judged from stdout); output saved to $(Split-Path -Leaf $mcpOutFile)"
    }
    catch {
        $exceptionMessage = $_.Exception.Message
        $noteLines = @()
        $noteLines += "MCP Inspector CLI invocation failed: $exceptionMessage"
        if ($exceptionMessage -like "*is not a valid Win32 application*") {
            $noteLines += "Hint: '%1 is not a valid Win32 application' is the known npx-shim launch bug -- the launch pattern regressed, see 08-collect-evidence-npx-bug.md. This is a harness bug, not an environment gap."
        }
        else {
            $noteLines += "Hint: if the error above is '%1 is not a valid Win32 application', the launch pattern regressed -- see 08-collect-evidence-npx-bug.md. Otherwise this looks like a genuine environment gap (Node/npm/network), not that known harness bug."
        }
        $note = $noteLines -join "`r`n"
        Write-Log $note
        $note | Write-Utf8NoBom -Path $mcpOutFile
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
# Step 3.6: Golden-query search-parity capture (capture-only, no judgment)
# ---------------------------------------------------------------------------
# Parity-with-dev search-quality harness (owner design): if a golden query set
# is staged next to this script, POST each query to /api/knowledge/search
# (hybrid, limit 10) and save the raw response under evidence/golden/<queryId>.json.
# This is capture-only -- the tolerance judgment (does the installed candidate's
# retrieval still match the dev-generated golden baseline for this build/corpus?)
# happens host-side at finalize via check_golden_parity.py. A missing queries
# file is a recorded gap, not a fatal error, matching the rest of this harness's
# capture-only philosophy.

$goldenQueriesPath = Join-Path -Path $PSScriptRoot -ChildPath "golden-queries.json"
$goldenDir = Join-Path -Path $EvidenceDir -ChildPath "golden"
$goldenCapturedCount = 0
$goldenFailedCount = 0
$goldenSkipped = $false

if (-not (Test-Path -LiteralPath $goldenQueriesPath)) {
    $goldenSkipped = $true
    $note = "Golden-query capture SKIPPED: golden-queries.json not found at $goldenQueriesPath -- " +
            "no per-candidate search-parity baseline was staged for this round. Recorded as a gap, not a fatal error."
    Write-Log $note
    $note | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
}
else {
    if (-not (Test-Path -LiteralPath $goldenDir)) {
        New-Item -ItemType Directory -Path $goldenDir -Force | Out-Null
    }

    try {
        $goldenRaw = Get-Content -LiteralPath $goldenQueriesPath -Raw -ErrorAction Stop
        $goldenDoc = $goldenRaw | ConvertFrom-Json -ErrorAction Stop
        $goldenQueries = @($goldenDoc.queries)
        Write-Log "Loaded $($goldenQueries.Count) golden quer$(if ($goldenQueries.Count -eq 1) {'y'} else {'ies'}) from $goldenQueriesPath"

        foreach ($gq in $goldenQueries) {
            $qid = $gq.id
            $qtext = $gq.query
            $outFile = Join-Path -Path $goldenDir -ChildPath "$qid.json"
            $searchUrl = "$base/api/knowledge/search"
            $requestBody = @{ query = $qtext; limit = 10; mode = "hybrid" } | ConvertTo-Json

            try {
                $response = Invoke-WebRequest -Uri $searchUrl -Method Post -Body $requestBody `
                    -ContentType "application/json" -UseBasicParsing -ErrorAction Stop
                $response.Content | Write-Utf8NoBom -Path $outFile
                $goldenCapturedCount++
                Write-Log "  golden $qid -> captured to golden/$qid.json"
            }
            catch {
                $goldenFailedCount++
                $exceptionMessage = $_.Exception.Message
                $errorRecord = New-Object PSObject -Property @{
                    queryId   = $qid
                    query     = $qtext
                    url       = $searchUrl
                    exception = $exceptionMessage
                }
                ($errorRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $outFile
                Write-Log "  golden $qid -> ERROR ($exceptionMessage) recorded to golden/$qid.json"
            }
        }
    }
    catch {
        $note = "Golden-query capture FAILED to parse $goldenQueriesPath : $($_.Exception.Message)"
        Write-Log $note
        $note | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
    }
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
if ($isElevated) {
    $summaryLines += "Elevation self-check: ELEVATED -- UAC prompt during install is structurally unobservable this round (see elevation-check.txt)"
}
else {
    $summaryLines += "Elevation self-check: not elevated -- UAC prompt during install IS observable this round (see elevation-check.txt)"
}
$summaryLines += ""
$summaryLines += "API sanity ladder:"
foreach ($result in $ladderResults) {
    $statusText = if ($result.StatusCode -eq $null) { "ERROR" } else { $result.StatusCode }
    $okText = if ($result.Ok) { "2xx" } else { "error/non-2xx" }
    $summaryLines += ("  {0,-30} -> {1} ({2}) [{3}]" -f $result.Path, $statusText, $okText, $result.File)
}
$summaryLines += ""
if ($mcpRan) {
    $toolNamesSummary = if ($mcpToolNames.Count -gt 0) { ($mcpToolNames -join ", ") } else { "(none)" }
    $summaryLines += "MCP Inspector CLI: SUCCESS (exit code $mcpExitCode; judged from stdout, not exit code; tools: $toolNamesSummary) -> $(Split-Path -Leaf $mcpOutFile)"
    if ($mcpNote -ne "") {
        $summaryLines += "  $mcpNote"
    }
}
else {
    $exitText = if ($mcpExitCode -eq $null) { "not run" } else { "exit code $mcpExitCode" }
    $summaryLines += "MCP Inspector CLI: FAILED/NOT RUN ($exitText; gap recorded) -> $(Split-Path -Leaf $mcpOutFile)"
}
$summaryLines += ""
if ($tracesCopied) {
    $summaryLines += "Request traces: COPIED -> traces.ndjson (host-side coverage check input)"
}
else {
    $summaryLines += "Request traces: NOT COPIED (traces.ndjson absent -- tracing may not have been enabled)"
}
$summaryLines += ""
if ($goldenSkipped) {
    $summaryLines += "Golden-query search-parity capture: SKIPPED (golden-queries.json not staged -- recorded gap)"
}
else {
    $summaryLines += "Golden-query search-parity capture: $goldenCapturedCount captured, $goldenFailedCount failed -> golden/ (host-side check_golden_parity.py input)"
}

($summaryLines -join "`r`n") | Write-Utf8NoBom -Path $summaryPath

$okCount = ($ladderResults | Where-Object { $_.Ok }).Count
$totalCount = $ladderResults.Count
Write-Host "[collect-evidence] Done. Port=$port Ladder=$okCount/$totalCount 2xx MCP-ran=$mcpRan Golden=$goldenCapturedCount/$($goldenCapturedCount + $goldenFailedCount) EvidenceDir=$EvidenceDir"

exit 0
