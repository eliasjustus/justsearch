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

    STATUS (verified live 2026-07, dev-stack round): the pieces this script
    depends on were individually live-verified against a running JustSearch
    instance driving this same shipped bridge over stdio -- initialize and
    tools/call both answer correctly, and the TYPED_CONFIRM gate fires as
    designed (tools/call justsearch_ingest with a real array `paths` returns
    isError:true plus the approval notice). The SCRIPT ITSELF was found hung
    by a real defect: the server replied to the notifications/initialized
    notification at all (JSON-RPC 2.0 SS4.1 forbids replying to a
    Notification, independent of whether the method is recognized), which
    desynced a spec-correct client's read loop. That server defect is fixed
    in McpProtocolHandler (see the commit touching
    modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java
    for the live evidence), and this script was separately hardened below to
    match responses by JSON-RPC id (not read-order/count) and to discard,
    not hang on, any unsolicited or id-null frame -- so it no longer relies
    on the server behaving perfectly to avoid hanging. That said, THIS
    HARDENING PASS DID NOT ITSELF RE-RUN THE SCRIPT END-TO-END against a
    live dev stack (no dev stack was started for this pass) -- the next
    Sandbox round that runs it live should still report back and update
    this status (see the validateHow note in
    governance/sandbox-coverage.v1.json). Do not read this docstring as a
    claim that the full sequence has been re-verified post-hardening.

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
    # Matches strictly by JSON-RPC `id` -- never by read-order/count. A frame with no "id"
    # member, an explicit "id":null, or an id belonging to some OTHER pending request is not
    # the answer to THIS request: it must be discarded (with a warning), not consumed as if it
    # were. This is what makes the script robust regardless of server behaviour -- e.g. a server
    # that (incorrectly) replies to a notification with an id:null error frame no longer hangs or
    # confuses this reader, it just gets logged and skipped.
    param(
        [Parameter(Mandatory = $true)]$Id,
        [int]$Seconds = $TimeoutSeconds
    )
    $seenFrames = New-Object System.Collections.Generic.List[string]
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited -and $proc.StandardOutput.EndOfStream) {
            throw "bridge exited (code $($proc.ExitCode)) before responding to id=$Id"
        }
        if ($proc.StandardOutput.Peek() -ge 0) {
            $line = $proc.StandardOutput.ReadLine()
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            Write-Log "<<< $line"
            $seenFrames.Add($line)
            try {
                $msg = $line | ConvertFrom-Json -ErrorAction Stop
            }
            catch {
                Write-Log "[WARN] non-JSON frame on bridge stdout, discarding: $line"
                continue
            }
            if ($null -ne $msg.id -and $msg.id -eq $Id) { return $msg }
            Write-Log "[WARN] discarding unsolicited/unmatched frame (id=$($msg.id), expected id=$Id)"
        }
        else {
            Start-Sleep -Milliseconds 100
        }
    }
    $frameDump = if ($seenFrames.Count -gt 0) {
        ($seenFrames | ForEach-Object { "    $_" }) -join "`n"
    }
    else {
        "    (no frames received)"
    }
    throw "Timed out after ${Seconds}s waiting for response id=$Id. Frames seen while waiting:`n$frameDump"
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
