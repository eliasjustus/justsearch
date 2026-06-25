# dev-all.ps1
# Starts both frontend and backend for full-stack development
# Usage: .\scripts\dev\dev-all.ps1 [-Port 33221] [-DataDir <path>] [-SkipBuild]

param(
    [int]$Port = 33221,  # Matches Vite proxy default and documented default port
    [string]$DataDir,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Write-Host "=======================================" -ForegroundColor Cyan
Write-Host " JustSearch Full-Stack Dev Environment" -ForegroundColor Cyan
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host ""

# This script is intentionally a thin wrapper around the canonical cross-platform dev orchestrator:
#   npm --prefix modules/ui-web run dev:all
# That Node script:
# - picks a free backend port near JUSTSEARCH_API_PORT (default 33221)
# - starts Gradle :modules:ui:runHeadless (with -PskipWebBuild=true)
# - waits for /api/status (deterministic readiness)
# - starts Vite on http://localhost:5173
# - sets VITE_JUSTSEARCH_API_PORT so the UI connects without ?api_port=

# Check if npm is available
$npmPath = Get-Command npm -ErrorAction SilentlyContinue
if (-not $npmPath) {
    Write-Host "ERROR: npm not found. Please install Node.js first." -ForegroundColor Red
    exit 1
}

$uiWebPath = Join-Path $scriptRoot "modules\ui-web"
if (-not (Test-Path -LiteralPath $uiWebPath)) {
    throw "modules/ui-web not found at: $uiWebPath"
}

if ($SkipBuild) {
    Write-Host "Note: -SkipBuild is ignored for dev:all (Gradle will run incrementally as needed)." -ForegroundColor DarkYellow
}

$env:JUSTSEARCH_API_PORT = $Port
if ($DataDir) {
    $resolvedDataDir =
        if ([System.IO.Path]::IsPathRooted($DataDir)) { $DataDir }
        else { Join-Path $scriptRoot $DataDir }
    New-Item -ItemType Directory -Force -Path $resolvedDataDir | Out-Null
    $env:JUSTSEARCH_DATA_DIR = (Resolve-Path -LiteralPath $resolvedDataDir).Path
}

Write-Host "Frontend: http://localhost:5173" -ForegroundColor Green
Write-Host "Backend:  (auto-picked free port near $Port; see logs for the exact port)" -ForegroundColor Green
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

try {
    Push-Location $scriptRoot
    npm --prefix $uiWebPath run dev:all
} finally {
    Pop-Location
}

