<#
.SYNOPSIS
    Drives the shipped MCPB stdio bridge to exercise the TYPED_CONFIRM
    mutating-tool procedure for `cohort:mcp` (tempdoc 728-followup, D2).

.DESCRIPTION
    The MCP Inspector CLI's `--tool-arg` string-coerces every value and
    cannot express `justsearch_ingest`'s `paths: string[]` argument -- the
    procedure `governance/sandbox-coverage.v1.json` used to mandate is
    unfollowable as written (verified in a real round: round3-tools/mcp-call.js
    was a hand-rolled workaround for exactly this).

    Instead of promoting a second, divergent HTTP client, this script spawns
    the REAL shipped MCPB stdio bridge (`index.js`, a verbatim copy of
    packaging/mcpb/server/index.js, staged next to this script) as a child
    process and speaks newline-delimited JSON-RPC to its stdin/stdout --
    exactly how a real MCP host (e.g. Claude Desktop) drives it. That means
    this round validates the actual artifact JustSearch ships in the MCPB
    package, not a parallel bespoke client.

    Sequence driven: initialize -> notifications/initialized ->
    tools/call justsearch_ingest(paths: [<TargetPath>]).

    UNVERIFIED AGAINST A LIVE /mcp AS OF AUTHORING. This was written by
    reading index.js and mirroring the message sequence already proven to
    work by the round-3 hand-rolled client, but has not itself been run
    against a running JustSearch instance -- the next Sandbox round MUST
    run it live and report back (see the validateHow note in
    governance/sandbox-coverage.v1.json).

.PARAMETER TargetPath
    Absolute path to ingest via justsearch_ingest (e.g. the mapped SciFact
    corpus directory).

.PARAMETER NodeExe
    Path to node.exe / the node command. Default: "node" (resolved via PATH).

.PARAMETER TimeoutSeconds
    Seconds to wait for each JSON-RPC response before failing. Default: 30.

.EXAMPLE
    .\mcp-typed-confirm.ps1 -TargetPath "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\scifact"
#>
param(
    [Parameter(Mandatory = $true)][string]$TargetPath,
    [string]$NodeExe = "node",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

$bridgePath = Join-Path -Path $PSScriptRoot -ChildPath "index.js"
if (-not (Test-Path -LiteralPath $bridgePath)) {
    Write-Error "Bridge not found at $bridgePath -- was mcp-client/ staged correctly by sandbox-launch.py?"
    exit 2
}

function Write-Log {
    param([string]$Message)
    Write-Host "[mcp-typed-confirm] $Message"
}

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $NodeExe
$psi.Arguments = "`"$bridgePath`""
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

Write-Log "Launching bridge: $NodeExe `"$bridgePath`""
$proc = [System.Diagnostics.Process]::Start($psi)

function Send-Message {
    param([Parameter(Mandatory = $true)][hashtable]$Message)
    $json = $Message | ConvertTo-Json -Depth 10 -Compress
    Write-Log ">>> $json"
    $proc.StandardInput.WriteLine($json)
    $proc.StandardInput.Flush()
}

function Read-ResponseForId {
    param(
        [Parameter(Mandatory = $true)]$Id,
        [int]$Seconds = $TimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited -and $proc.StandardOutput.EndOfStream) {
            throw "bridge exited (code $($proc.ExitCode)) before responding to id=$Id"
        }
        if ($proc.StandardOutput.Peek() -ge 0) {
            $line = $proc.StandardOutput.ReadLine()
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            Write-Log "<<< $line"
            try {
                $msg = $line | ConvertFrom-Json -ErrorAction Stop
            }
            catch {
                continue
            }
            if ($null -ne $msg.id -and $msg.id -eq $Id) { return $msg }
        }
        else {
            Start-Sleep -Milliseconds 100
        }
    }
    throw "Timed out after ${Seconds}s waiting for response id=$Id"
}

try {
    Send-Message -Message @{
        jsonrpc = "2.0"
        id      = 1
        method  = "initialize"
        params  = @{
            protocolVersion = "2025-06-18"
            capabilities    = @{}
            clientInfo      = @{ name = "sandbox-typed-confirm-client"; version = "1.0.0" }
        }
    }
    $initResp = Read-ResponseForId -Id 1
    if (-not $initResp.result) {
        throw "initialize did not return a result: $($initResp | ConvertTo-Json -Depth 10 -Compress)"
    }
    Write-Log "initialize OK"

    Send-Message -Message @{ jsonrpc = "2.0"; method = "notifications/initialized" }

    Send-Message -Message @{
        jsonrpc = "2.0"
        id      = 2
        method  = "tools/call"
        params  = @{
            name      = "justsearch_ingest"
            arguments = @{ paths = @($TargetPath) }
        }
    }
    $callResp = Read-ResponseForId -Id 2

    Write-Host ""
    Write-Host "=== tools/call justsearch_ingest result ==="
    $callJson = $callResp | ConvertTo-Json -Depth 10
    Write-Host $callJson

    exit 0
}
catch {
    Write-Error "mcp-typed-confirm.ps1 failed: $($_.Exception.Message)"
    exit 1
}
finally {
    try {
        if (-not $proc.HasExited) {
            $stderrText = $null
            try { $stderrText = $proc.StandardError.ReadToEnd() } catch {}
            $proc.StandardInput.Close()
            Start-Sleep -Milliseconds 200
            if (-not $proc.HasExited) { $proc.Kill() }
            if ($stderrText) { Write-Log "bridge stderr:`n$stderrText" }
        }
    }
    catch {
        # best-effort cleanup only
    }
}
