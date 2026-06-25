<#
.SYNOPSIS
  Build a v3 GPU Booster Pack (runtime pack) folder from pinned upstream llama.cpp CUDA artifacts.

.DESCRIPTION
  Produces a pack folder containing:
    - pack-manifest.v1.json (kind=runtime, with variantId)
    - payload/llama-server.exe
    - payload/*.dll (llama + CUDA runtime DLLs)
    - payload/LICENSE* (upstream notices if present)
    - payload/NOTICE-NVIDIA-CUDA.txt (generated)
    - payload/runtime-version.txt (generated)

  Then prints the manifest SHA-256 (raw file bytes) to allowlist in policy:
    allowlists.packManifestSha256 = [ "<digest>" ]

.PARAMETER LlamaZip
  Path to the upstream llama.cpp runtime zip (e.g., llama-b8157-bin-win-cuda-12.4-x64.zip).

.PARAMETER CudartZip
  Path to the upstream cudart redistributables zip (e.g., cudart-llama-bin-win-cuda-12.4-x64.zip).

.PARAMETER OutDir
  Output directory for the pack folder.

.PARAMETER PackId
  Pack id (default: justsearch.ai-pack.v3.runtime.cuda).

.PARAMETER PackVersion
  Pack version (default: 1.0.0).

.PARAMETER VariantId
  Runtime variant id (default: cuda-12.4).

.PARAMETER RequiresAppMin
  Minimum app version string (default: 1.0.0).

.PARAMETER ZipOut
  Optional: if provided, also create a .zip of the pack folder at this path.
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$LlamaZip,

  [Parameter(Mandatory = $true)]
  [string]$CudartZip,

  [Parameter(Mandatory = $true)]
  [string]$OutDir,

  [string]$PackId = "justsearch.ai-pack.v3.runtime.cuda",
  [string]$PackVersion = "1.0.0",
  [string]$VariantId = "cuda-12.4",
  [string]$RequiresAppMin = "1.0.0",

  [string]$ZipOut = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-Utf8NoBomBytes([string]$Text) {
  $enc = New-Object System.Text.UTF8Encoding($false)
  return $enc.GetBytes($Text)
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
  $bytes = New-Utf8NoBomBytes $Text
  [System.IO.File]::WriteAllBytes($Path, $bytes)
}

function Sha256HexFromBytes([byte[]]$Bytes) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $hash = $sha.ComputeHash($Bytes)
    return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Sha256HexFromFile([string]$Path) {
  $bytes = [System.IO.File]::ReadAllBytes($Path)
  return Sha256HexFromBytes $bytes
}

function Ensure-EmptyDir([string]$Path) {
  if (Test-Path -LiteralPath $Path) {
    Remove-Item -LiteralPath $Path -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Expand-Zip([string]$Zip, [string]$Dest) {
  Ensure-EmptyDir $Dest
  Expand-Archive -LiteralPath $Zip -DestinationPath $Dest -Force
}

function Get-TopLevelFiles([string]$Dir) {
  return Get-ChildItem -LiteralPath $Dir -File -Force
}

function Write-ZipWithForwardSlashPaths([string]$SourceDir, [string]$ZipPath) {
  if ([string]::IsNullOrWhiteSpace($SourceDir)) { throw "SourceDir is required." }
  if ([string]::IsNullOrWhiteSpace($ZipPath)) { throw "ZipPath is required." }
  if (-not (Test-Path -LiteralPath $SourceDir)) { throw "SourceDir not found: $SourceDir" }

  # Ensure the ZipFile helpers are available (Windows PowerShell 5.1 / .NET Framework).
  try { Add-Type -AssemblyName System.IO.Compression | Out-Null } catch { }
  try { Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null } catch { }

  $src = (Resolve-Path -LiteralPath $SourceDir).Path
  $src = [System.IO.Path]::GetFullPath($src)

  if (Test-Path -LiteralPath $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
  }

  $fs = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
  try {
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create, $false)
    try {
      $files = Get-ChildItem -LiteralPath $src -Recurse -File -Force
      foreach ($f in $files) {
        $full = [System.IO.Path]::GetFullPath($f.FullName)
        if (-not $full.StartsWith($src, [System.StringComparison]::OrdinalIgnoreCase)) {
          throw "Internal error: file outside source dir: $full"
        }
        $rel = $full.Substring($src.Length).TrimStart('\', '/')
        $rel = $rel -replace '\\', '/'
        if ([string]::IsNullOrWhiteSpace($rel)) {
          throw "Internal error: empty relative path for file: $full"
        }

        $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        try {
          $in = [System.IO.File]::OpenRead($full)
          try {
            $in.CopyTo($entryStream)
          } finally {
            $in.Dispose()
          }
        } finally {
          $entryStream.Dispose()
        }
      }
    } finally {
      $zip.Dispose()
    }
  } finally {
    $fs.Dispose()
  }

  # Defensive check: the app rejects backslash entries ("Zip contains backslash path entry").
  $zipRead = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
  try {
    foreach ($e in $zipRead.Entries) {
      if ($e.FullName.Contains('\')) {
        throw "Zip contains backslash path entry: $($e.FullName)"
      }
    }
  } finally {
    $zipRead.Dispose()
  }
}

if (!(Test-Path -LiteralPath $LlamaZip)) { throw "LlamaZip not found: $LlamaZip" }
if (!(Test-Path -LiteralPath $CudartZip)) { throw "CudartZip not found: $CudartZip" }

$outRoot = (Resolve-Path -LiteralPath $OutDir -ErrorAction SilentlyContinue)
if ($outRoot) { $outRoot = $outRoot.Path } else { $outRoot = $OutDir }

Ensure-EmptyDir $outRoot

$payloadDir = Join-Path $outRoot "payload"
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("justsearch-pack-" + [System.Guid]::NewGuid().ToString("n"))
$tmpLlama = Join-Path $tmp "llama"
$tmpCuda = Join-Path $tmp "cudart"

try {
  Expand-Zip $LlamaZip $tmpLlama
  Expand-Zip $CudartZip $tmpCuda

  $llamaFiles = Get-TopLevelFiles $tmpLlama
  $cudaFiles = Get-TopLevelFiles $tmpCuda

  # Allowlist of runtime payload patterns (matches v3 validator).
  $selected = @()

  # llama-server.exe (required)
  $exe = $llamaFiles | Where-Object { $_.Name -ieq "llama-server.exe" } | Select-Object -First 1
  if (-not $exe) { throw "llama-server.exe not found in LlamaZip root." }
  $selected += $exe

  # All DLLs from both zips
  $selected += ($llamaFiles | Where-Object { $_.Extension -ieq ".dll" })
  $selected += ($cudaFiles | Where-Object { $_.Extension -ieq ".dll" })

  # LICENSE* files from llama zip (if present)
  $selected += ($llamaFiles | Where-Object { $_.Name -match "^(?i)LICENSE" })

  # De-dup by filename (case-insensitive)
  $byName = @{}
  foreach ($f in $selected) {
    if (-not $f) { continue }
    $key = $f.Name.ToLowerInvariant()
    if (-not $byName.ContainsKey($key)) {
      $byName[$key] = $f
    }
  }

  # Generated notices
  $runtimeVersionPath = Join-Path $payloadDir "runtime-version.txt"
  $noticePath = Join-Path $payloadDir "NOTICE-NVIDIA-CUDA.txt"
  Write-Utf8NoBom $runtimeVersionPath ("variantId=" + $VariantId + "`n" + "createdAt=" + (Get-Date).ToUniversalTime().ToString("o") + "`n")
  Write-Utf8NoBom $noticePath @"
JustSearch GPU Booster Pack (runtime)

variantId: $VariantId
packId: $PackId
packVersion: $PackVersion

This pack may redistribute NVIDIA CUDA runtime DLLs (cudart/cublas/etc).
Ensure your distribution complies with NVIDIA's CUDA Toolkit EULA (Attachment A).

This file is generated by tools/build-gpu-booster-pack.ps1.
"@

  # Copy selected files into payload/
  foreach ($kv in $byName.GetEnumerator()) {
    $src = $kv.Value.FullName
    $dst = Join-Path $payloadDir $kv.Value.Name
    Copy-Item -LiteralPath $src -Destination $dst -Force
  }

  # Include generated files in the final manifest set
  $finalFiles = Get-ChildItem -LiteralPath $payloadDir -File -Force | Sort-Object Name

  # Build manifest
  $fileEntries = @()
  $assetEntries = @()

  foreach ($f in $finalFiles) {
    $rel = "payload/" + $f.Name
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $sha = Sha256HexFromBytes $bytes
    $id = ($f.BaseName + "_" + ($sha.Substring(0, 8))) -replace "[^a-zA-Z0-9_\\-\\.]", "_"
    $fileEntries += @{
      id = $id
      pathInPack = $rel
      sha256 = $sha
      sizeBytes = $bytes.Length
    }

    if ($f.Name -ieq "llama-server.exe") {
      $assetEntries += @{ role = "runtime.llamaServer"; fileId = $id }
    } else {
      $assetEntries += @{ role = "runtime.runtimeFile"; fileId = $id }
    }
  }

  $manifest = @{
    schemaVersion = 1
    packId = $PackId
    packVersion = $PackVersion
    kind = "runtime"
    variantId = $VariantId
    createdAt = (Get-Date).ToUniversalTime().ToString("o")
    requiresAppMin = $RequiresAppMin
    files = $fileEntries
    assets = $assetEntries
  }

  $manifestJson = ($manifest | ConvertTo-Json -Depth 20)
  $manifestPath = Join-Path $outRoot "pack-manifest.v1.json"
  Write-Utf8NoBom $manifestPath $manifestJson

  $manifestDigest = Sha256HexFromFile $manifestPath

  Write-Host ""
  Write-Host "Pack folder created at: $outRoot"
  Write-Host "Manifest SHA-256 (raw bytes): $manifestDigest"
  Write-Host ""
  Write-Host "Allowlist snippet (policy.v1.json):"
  Write-Host ""
  Write-Host ("`"allowlists`": { `"packManifestSha256`": [ `"" + $manifestDigest + "`" ] }")
  Write-Host ""

  if ($ZipOut -and $ZipOut.Trim()) {
    $zipPath = $ZipOut
    if (Test-Path -LiteralPath $zipPath) {
      Remove-Item -LiteralPath $zipPath -Force
    }
    Write-ZipWithForwardSlashPaths -SourceDir $outRoot -ZipPath $zipPath
    Write-Host "Pack zip created at: $zipPath"
  }
} finally {
  try {
    if (Test-Path -LiteralPath $tmp) {
      Remove-Item -LiteralPath $tmp -Recurse -Force
    }
  } catch {
    # best-effort
  }
}


