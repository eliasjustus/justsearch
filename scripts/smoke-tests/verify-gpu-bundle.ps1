#Requires -Version 5.1
<#
.SYNOPSIS
    Verifies GPU variant is correctly staged in build output.
.DESCRIPTION
    Run after Gradle build to verify cuda12 variant is present with all required DLLs.
    This catches build-time issues before they reach distribution.
.PARAMETER BuildOutput
    Path to the llama-server directory (default: modules/ui/native-bin/llama-server)
.EXAMPLE
    .\verify-gpu-bundle.ps1
    .\verify-gpu-bundle.ps1 -BuildOutput "modules\ui\native-bin\llama-server"
#>

param(
    [string]$BuildOutput
)

$ErrorActionPreference = "Stop"

# Resolve default path relative to repo root (script is in scripts/smoke-tests/)
if (-not $BuildOutput) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
    $BuildOutput = Join-Path $repoRoot "modules/ui/native-bin/llama-server"
}

Write-Host "Verifying GPU variant in build output..."
Write-Host "  Path: $BuildOutput"

$cuda12Dir = Join-Path $BuildOutput "variants/cuda12"

# Check variant directory exists
if (-not (Test-Path $cuda12Dir)) {
    throw "FAILED: cuda12 variant directory not found at: $cuda12Dir"
}

# Check required files
$requiredFiles = @(
    "llama-server.exe",
    "ggml-cuda.dll",
    "cudart64_12.dll",
    "cublas64_12.dll",
    "cublasLt64_12.dll",
    "NOTICE-NVIDIA-CUDA.txt"
)

$missing = @()
foreach ($file in $requiredFiles) {
    $path = Join-Path $cuda12Dir $file
    if (-not (Test-Path $path)) {
        $missing += $file
    }
}

if ($missing.Count -gt 0) {
    throw "FAILED: Missing files in cuda12 variant: $($missing -join ', ')"
}

# Check ggml-cuda.dll size (must be statically-linked, >400MB)
$ggmlCuda = Join-Path $cuda12Dir "ggml-cuda.dll"
$size = (Get-Item $ggmlCuda).Length
if ($size -lt 400000000) {
    throw "FAILED: ggml-cuda.dll is $([math]::Round($size/1MB))MB - not statically-linked (expected >400MB)"
}

Write-Host "PASSED: cuda12 variant verified" -ForegroundColor Green
Write-Host "  Files: $($requiredFiles.Count) required files present"
Write-Host "  ggml-cuda.dll: $([math]::Round($size/1MB))MB (statically-linked)"
