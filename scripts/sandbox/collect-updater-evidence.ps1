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
    "installer-launch-witness.v1.json"
)
$summary = [ordered]@{
    capturedAt = (Get-Date -Format o)
    dataDir = $DataDir
    upgradeRootExists = (Test-Path -LiteralPath $upgradeRoot)
    files = [ordered]@{}
    installedVersion = $null
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
