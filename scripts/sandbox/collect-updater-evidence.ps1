# SPDX-License-Identifier: Apache-2.0
<#
Captures the updater's durable recovery oracles without mutating them.
Run before an intentional interruption and again after restart/reconciliation.
#>
param(
    [string]$DataDir = "$env:APPDATA\io.justsearch.shell",
    [string]$EvidenceDir = (Join-Path $PSScriptRoot "evidence\updater")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$upgradeRoot = Join-Path $DataDir "upgrade"
New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null

$files = @(
    "intent.v1.json",
    "sequence.v1.json",
    "installer-launch-witness.v1.json",
    # §9 item 5 requires the shutdown receipt to be retained alongside the intent and witness, and
    # it is the artifact that proves Head stopped cleanly rather than being killed. It was absent
    # from this list, so every previous capture would have been missing it.
    "head-shutdown-receipt.v1.json",
    # Written by the unattended lane; absent on an operator-driven consent round.
    "qualification-result.v1.json"
)
$summary = [ordered]@{
    capturedAt = (Get-Date -Format o)
    dataDir = $DataDir
    upgradeRootExists = (Test-Path -LiteralPath $upgradeRoot)
    files = [ordered]@{}
    installedVersion = $null
    # Top-level so a lane reads one field instead of re-deriving the verdict from phases.
    autorunVerdict = $null
    autorunDetail = $null
    justSearchProcesses = @()
}

foreach ($name in $files) {
    $source = Join-Path $upgradeRoot $name
    $entry = [ordered]@{ exists = $false; validJson = $false; phase = $null }
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        $entry.exists = $true
        Copy-Item -LiteralPath $source -Destination (Join-Path $EvidenceDir $name) -Force
        try {
            $parsed = Get-Content -LiteralPath $source -Raw | ConvertFrom-Json
            $entry.validJson = $true
            if ($parsed.PSObject.Properties.Name -contains "phase") {
                $entry.phase = [string]$parsed.phase
            }
            if ($name -eq "qualification-result.v1.json") {
                if ($parsed.PSObject.Properties.Name -contains "verdict") {
                    $summary.autorunVerdict = [string]$parsed.verdict
                }
                if ($parsed.PSObject.Properties.Name -contains "detail") {
                    $summary.autorunDetail = [string]$parsed.detail
                }
            }
        }
        catch {
            $entry["error"] = $_.Exception.Message
        }
    }
    $summary.files[$name] = $entry
}

$installedExe = Join-Path $env:LOCALAPPDATA "JustSearch\JustSearch.exe"
if (Test-Path -LiteralPath $installedExe -PathType Leaf) {
    $summary.installedVersion = (Get-Item -LiteralPath $installedExe).VersionInfo.ProductVersion
}
$summary.justSearchProcesses = @(
    Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "*\JustSearch\*" -or $_.Path -like "*io.justsearch.shell*" } |
        ForEach-Object {
            [ordered]@{ id = $_.Id; name = $_.ProcessName; path = $_.Path }
        }
)

$summary | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $EvidenceDir "updater-state.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 8
