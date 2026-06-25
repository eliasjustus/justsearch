#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ModelPath = "D:\tests\models\qwen3-4b-instruct-gguf\Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    [string]$Backend = "llama",
    [string]$LlamaLib = "D:\code\JustSearch\modules\app-launcher\build\install\app-launcher\bin\native\windows-x86_64\cpu\llama.dll",
    [switch]$RequireTranslator,
    [switch]$DisableDiagnosticsOverride,
    [switch]$AllowParityMismatch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlew = Join-Path -Path $repoRoot -ChildPath "gradlew.bat"
$launcher = Join-Path -Path $repoRoot -ChildPath "modules/app-launcher/build/install/app-launcher/bin/app-launcher.bat"

if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "Unable to find gradlew at $gradlew"
}

# Defaults for safer local runs
if (-not $env:JUSTSEARCH_LLM_MODEL_PATH) { $env:JUSTSEARCH_LLM_MODEL_PATH = $ModelPath }
if (-not $env:JUSTSEARCH_LLM_BACKEND)    { $env:JUSTSEARCH_LLM_BACKEND    = $Backend }
if (-not $env:LLAMA_LIB_PATH)            { $env:LLAMA_LIB_PATH            = $LlamaLib }
if ($RequireTranslator.IsPresent)        { $env:JUSTSEARCH_UI_AUTOMATION_REQUIRE_TRANSLATOR = "true" }
if ($DisableDiagnosticsOverride.IsPresent) { $env:JUSTSEARCH_UI_AUTOMATION_FORCE_DIAGNOSTICS = "false" }
if ($AllowParityMismatch.IsPresent -and -not $env:JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH) {
    $env:JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH = "true"
}

Write-Host "Ensuring launcher distribution is installed..."
& $gradlew --no-daemon :modules:app-launcher:installDist
if ($LASTEXITCODE -ne 0) {
    throw "Gradle installDist failed."
}

if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Launcher binary not found at $launcher"
}

Write-Host "Launching UI with local LLM (backend=$($env:JUSTSEARCH_LLM_BACKEND))..."
Start-Process -FilePath $launcher -ArgumentList @("ui") -WorkingDirectory (Split-Path -Parent $launcher)
