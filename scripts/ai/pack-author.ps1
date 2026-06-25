<#
.SYNOPSIS
  Builds a v2 (models-only) JustSearch AI Pack zip:
  - pack-manifest.v1.json (root)
  - payload/models/<chatFilename>
  - payload/models/<embeddingFilename>

.DESCRIPTION
  This script mirrors the current app-side validation/import behavior (v2 models-only):
  - Manifest digest allowlist key is SHA-256 of the exact manifest bytes stored in the pack.
  - All payload files must be declared in the manifest (no extras).
  - paths must start with "payload/" and use "/" separators.

  The script prints:
  - manifest SHA-256 digest
  - a ready-to-paste policy.v1.json snippet containing allowlists.packManifestSha256 (and modelSha256)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/ai/pack-author.ps1 `
    -ChatModelPath .\\models\\Qwen3-4B-Instruct-2507-Q4_K_M.gguf `
    -EmbeddingModelPath .\\models\\nomic-embed-text-v1.5.Q4_K_M.gguf `
    -PackId justsearch.ai-pack.v2.models.default `
    -PackVersion 2.0.0 `
    -RequiresAppMin 1.0.0 `
    -OutputZip .\\dist\\ai-pack.zip `
    -Force
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory = $false)]
  [string] $ChatModelPath,

  [Parameter(Mandatory = $false)]
  [string] $EmbeddingModelPath,

  [Parameter(Mandatory = $true)]
  [string] $PackId,

  [Parameter(Mandatory = $true)]
  [string] $PackVersion,

  [Parameter(Mandatory = $false)]
  [string] $RequiresAppMin = "1.0.0",

  [Parameter(Mandatory = $false)]
  [string] $RequiresAppMax,

  [Parameter(Mandatory = $true)]
  [string] $OutputZip,

  [Parameter(Mandatory = $false)]
  [ValidateSet("Optimal", "Fastest", "NoCompression")]
  [string] $CompressionLevel = "NoCompression",

  [Parameter(Mandatory = $false)]
  [switch] $PrettyManifest,

  [Parameter(Mandatory = $false)]
  [switch] $SkipVerify,

  [Parameter(Mandatory = $false)]
  [switch] $Force,

  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [Parameter(Mandatory = $false)]
  [string] $EvidenceOutRoot = "tmp/agent-evidence",

  # Disable EvidenceBundle capture for this script.
  [Parameter(Mandatory = $false)]
  [switch] $NoEvidence,

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [Parameter(Mandatory = $false)]
  [switch] $ValidateEvidenceBundle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) # scripts/ai -> scripts -> repo root

# Auto-discover models from AI Home when not explicitly specified.
$defaultModelDir = Join-Path $env:APPDATA "io.justsearch.shell\models"
if ((-not $ChatModelPath) -or (-not $EmbeddingModelPath)) {
  if (-not (Test-Path $defaultModelDir)) {
    throw "No model paths specified and default model dir not found: $defaultModelDir"
  }
  $ggufs = Get-ChildItem -Path $defaultModelDir -Filter "*.gguf" | Sort-Object Length -Descending
  if ($ggufs.Count -lt 2) {
    throw "Need at least 2 .gguf files in $defaultModelDir (found $($ggufs.Count))"
  }
  $embedFile = $ggufs | Where-Object { $_.Name -match 'embed' } | Select-Object -First 1
  $chatFile = $ggufs | Where-Object { $_.Name -notmatch 'embed' } | Select-Object -First 1
  if ($null -eq $embedFile -or $null -eq $chatFile) {
    throw "Could not auto-detect chat/embedding models in $defaultModelDir"
  }
  if (-not $ChatModelPath) { $ChatModelPath = $chatFile.FullName }
  if (-not $EmbeddingModelPath) { $EmbeddingModelPath = $embedFile.FullName }
  Write-Host "Auto-discovered models from $defaultModelDir`:"
  Write-Host "  Chat:      $ChatModelPath"
  Write-Host "  Embedding: $EmbeddingModelPath"
}

$evidenceEnabled = -not $NoEvidence.IsPresent
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$transcriptPath = Join-Path -Path $summaryDir -ChildPath ("ai-pack-author-" + $timestamp + ".transcript.log")
$metaPath = Join-Path -Path $summaryDir -ChildPath ("ai-pack-author-" + $timestamp + ".meta.json")
$transcriptStarted = $false

# Capture a few high-signal outputs for evidence metadata (initialized for StrictMode).
$manifestSha256Out = $null
$chatShaOut = $null
$embedShaOut = $null

$exitCode = 0
$mainError = $null

if ($evidenceEnabled) {
  try {
    Start-Transcript -Path $transcriptPath -Force | Out-Null
    $transcriptStarted = $true
  } catch {
    $transcriptStarted = $false
    Write-Warning "Start-Transcript failed (non-fatal): $($_.Exception.Message)"
  }
}

function Fail([string] $Message) {
  throw $Message
}

function IsBlank([string] $s) {
  return ($null -eq $s) -or ($s.Trim().Length -eq 0)
}

function JsonEscape([string] $s) {
  if ($null -eq $s) { return "" }
  $sb = New-Object System.Text.StringBuilder
  foreach ($ch in $s.ToCharArray()) {
    $code = [int] $ch
    switch ($ch) {
      '"'  { [void] $sb.Append('\\"'); continue }
      '\'  { [void] $sb.Append('\\\\'); continue }
      "`b" { [void] $sb.Append('\b'); continue }
      "`f" { [void] $sb.Append('\f'); continue }
      "`n" { [void] $sb.Append('\n'); continue }
      "`r" { [void] $sb.Append('\r'); continue }
      "`t" { [void] $sb.Append('\t'); continue }
    }
    if ($code -lt 0x20) {
      [void] $sb.Append(("\u{0:X4}" -f $code))
      continue
    }
    [void] $sb.Append($ch)
  }
  return $sb.ToString()
}

function J([string] $s) {
  return '"' + (JsonEscape $s) + '"'
}

function ToLowerHex([byte[]] $bytes) {
  if ($null -eq $bytes) { return "" }
  return ([System.BitConverter]::ToString($bytes).Replace("-", "")).ToLowerInvariant()
}

function Sha256HexFromBytes([byte[]] $bytes) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $hashBytes = $sha.ComputeHash($bytes)
    return ToLowerHex $hashBytes
  } finally {
    $sha.Dispose()
  }
}

function ComputeSha256AndCountFromStream([System.IO.Stream] $stream, [int] $bufferSizeBytes = 4194304) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $buf = New-Object byte[] $bufferSizeBytes
    [long] $count = 0
    while ($true) {
      $read = $stream.Read($buf, 0, $buf.Length)
      if ($read -le 0) { break }
      [void] $sha.TransformBlock($buf, 0, $read, $null, 0)
      $count += $read
    }
    [void] $sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
    return [pscustomobject]@{
      sha256 = (ToLowerHex $sha.Hash)
      sizeBytes = $count
    }
  } finally {
    $sha.Dispose()
  }
}

function CopyStreamWithSha256([System.IO.Stream] $src, [System.IO.Stream] $dst, [int] $bufferSizeBytes = 4194304) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $buf = New-Object byte[] $bufferSizeBytes
    [long] $count = 0
    while ($true) {
      $read = $src.Read($buf, 0, $buf.Length)
      if ($read -le 0) { break }
      $dst.Write($buf, 0, $read)
      [void] $sha.TransformBlock($buf, 0, $read, $null, 0)
      $count += $read
    }
    [void] $sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
    return [pscustomobject]@{
      sha256 = (ToLowerHex $sha.Hash)
      sizeBytes = $count
    }
  } finally {
    $sha.Dispose()
  }
}

function ValidatePathInPack([string] $pathInPack) {
  if (IsBlank $pathInPack) { Fail "pathInPack is required." }
  if ($pathInPack.Contains("\")) { Fail "Invalid pathInPack: must use '/' separators only: $pathInPack" }
  if ($pathInPack.StartsWith("/")) { Fail "Invalid pathInPack: must be relative: $pathInPack" }
  if ($pathInPack -match '^[A-Za-z]:') { Fail "Invalid pathInPack: must be relative: $pathInPack" }
  if (-not $pathInPack.StartsWith("payload/")) { Fail "Invalid pathInPack: must start with 'payload/': $pathInPack" }
  foreach ($seg in $pathInPack.Split("/")) {
    if ($seg -eq "..") { Fail "Invalid pathInPack: must not contain '..' segments: $pathInPack" }
    if (IsBlank $seg) { Fail "Invalid pathInPack: must not contain empty segments: $pathInPack" }
  }
}

function BuildManifestJson(
  [string] $packId,
  [string] $packVersion,
  [string] $createdAt,
  [string] $requiresAppMin,
  [string] $requiresAppMax,
  [pscustomobject[]] $files,
  [pscustomobject[]] $assets,
  [switch] $pretty
) {
  if ($files.Count -le 0) { Fail "Manifest must contain files." }
  if ($assets.Count -le 0) { Fail "Manifest must contain assets." }

  $nl = ""
  $sp0 = ""
  $sp1 = ""
  $sp2 = ""
  $sp3 = ""
  $colonSpace = ""
  if ($pretty.IsPresent) {
    $nl = "`n"
    $sp1 = "  "
    $sp2 = "    "
    $sp3 = "      "
    $colonSpace = " "
  }

  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append("{").Append($nl)

  [void]$sb.Append($sp1).Append('"schemaVersion":').Append($colonSpace).Append("1,").Append($nl)
  [void]$sb.Append($sp1).Append('"packId":').Append($colonSpace).Append((J $packId)).Append(",").Append($nl)
  [void]$sb.Append($sp1).Append('"packVersion":').Append($colonSpace).Append((J $packVersion)).Append(",").Append($nl)
  [void]$sb.Append($sp1).Append('"kind":').Append($colonSpace).Append((J "models")).Append(",").Append($nl)
  [void]$sb.Append($sp1).Append('"createdAt":').Append($colonSpace).Append((J $createdAt)).Append(",").Append($nl)
  [void]$sb.Append($sp1).Append('"requiresAppMin":').Append($colonSpace).Append((J $requiresAppMin))
  if (-not (IsBlank $requiresAppMax)) {
    [void]$sb.Append(",").Append($nl)
    [void]$sb.Append($sp1).Append('"requiresAppMax":').Append($colonSpace).Append((J $requiresAppMax))
  }
  [void]$sb.Append(",").Append($nl)

  # files
  [void]$sb.Append($sp1).Append('"files":').Append($colonSpace).Append("[").Append($nl)
  for ($i = 0; $i -lt $files.Count; $i++) {
    $f = $files[$i]
    [void]$sb.Append($sp2).Append("{").Append($nl)
    [void]$sb.Append($sp3).Append('"id":').Append($colonSpace).Append((J $f.id)).Append(",").Append($nl)
    [void]$sb.Append($sp3).Append('"pathInPack":').Append($colonSpace).Append((J $f.pathInPack)).Append(",").Append($nl)
    [void]$sb.Append($sp3).Append('"sha256":').Append($colonSpace).Append((J $f.sha256)).Append(",").Append($nl)
    [void]$sb.Append($sp3).Append('"sizeBytes":').Append($colonSpace).Append([string]$f.sizeBytes).Append($nl)
    [void]$sb.Append($sp2).Append("}")
    if ($i -lt ($files.Count - 1)) { [void]$sb.Append(",") }
    [void]$sb.Append($nl)
  }
  [void]$sb.Append($sp1).Append("],").Append($nl)

  # assets
  [void]$sb.Append($sp1).Append('"assets":').Append($colonSpace).Append("[").Append($nl)
  for ($i = 0; $i -lt $assets.Count; $i++) {
    $a = $assets[$i]
    if ($pretty.IsPresent) {
      [void]$sb.Append($sp2).Append("{ ").Append('"role": ').Append((J $a.role)).Append(", ").Append('"fileId": ').Append((J $a.fileId)).Append(" }")
    } else {
      [void]$sb.Append($sp2).Append("{").Append('"role":').Append((J $a.role)).Append(",").Append('"fileId":').Append((J $a.fileId)).Append("}")
    }
    if ($i -lt ($assets.Count - 1)) { [void]$sb.Append(",") }
    [void]$sb.Append($nl)
  }
  [void]$sb.Append($sp1).Append("]").Append($nl)

  [void]$sb.Append($sp0).Append("}").Append($nl)
  return $sb.ToString()
}

function NormalizeFullPath([string] $path) {
  if (IsBlank $path) { return "" }
  return [System.IO.Path]::GetFullPath($path)
}

function EnsureRegularFile([string] $path, [string] $label) {
  if (IsBlank $path) { Fail "$label is required." }
  if (-not (Test-Path -LiteralPath $path)) { Fail "$label does not exist: $path" }
  $item = Get-Item -LiteralPath $path -ErrorAction Stop
  if ($item.PSIsContainer) { Fail "$label must be a file, not a directory: $path" }
  return $item
}

function EnsureDir([string] $dir) {
  if (IsBlank $dir) { return }
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

function NormalizeSha256([string] $value, [string] $label) {
  if (IsBlank $value) { Fail "$label SHA-256 is blank." }
  $s = $value.Trim().ToLowerInvariant()
  if ($s.Length -ne 64) { Fail "$label SHA-256 must be 64 hex characters." }
  if ($s -notmatch '^[0-9a-f]{64}$') { Fail "$label SHA-256 contains non-hex characters." }
  return $s
}

function PrintPolicySnippet([string] $manifestSha256, [string[]] $modelSha256) {
  $now = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss'Z'")
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine("{")
  [void]$sb.AppendLine('  "schemaVersion": 1,')
  [void]$sb.AppendLine(('  "updatedAt": ' + (J $now) + ','))
  [void]$sb.AppendLine('  "allowlists": {')
  [void]$sb.AppendLine('    "modelSha256": [')
  for ($i = 0; $i -lt $modelSha256.Length; $i++) {
    $line = '      ' + (J $modelSha256[$i])
    if ($i -lt ($modelSha256.Length - 1)) { $line += "," }
    [void]$sb.AppendLine($line)
  }
  [void]$sb.AppendLine('    ],')
  [void]$sb.AppendLine('    "packManifestSha256": [')
  [void]$sb.AppendLine(('      ' + (J $manifestSha256)))
  [void]$sb.AppendLine('    ]')
  [void]$sb.AppendLine('  }')
  [void]$sb.AppendLine("}")
  return $sb.ToString()
}

# ----------- Begin script logic -----------

try {
if (IsBlank $PackId) { Fail "PackId is required." }
if (IsBlank $PackVersion) { Fail "PackVersion is required." }
if (IsBlank $RequiresAppMin) { Fail "RequiresAppMin is required." }
if (IsBlank $OutputZip) { Fail "OutputZip is required." }

$chatItem = EnsureRegularFile -path $ChatModelPath -label "ChatModelPath"
$embedItem = EnsureRegularFile -path $EmbeddingModelPath -label "EmbeddingModelPath"

$chatFull = NormalizeFullPath $chatItem.FullName
$embedFull = NormalizeFullPath $embedItem.FullName
if ([string]::Equals($chatFull, $embedFull, [System.StringComparison]::OrdinalIgnoreCase)) {
  Fail "ChatModelPath and EmbeddingModelPath must be different files."
}

$chatFilename = [System.IO.Path]::GetFileName($chatFull)
$embedFilename = [System.IO.Path]::GetFileName($embedFull)
if (IsBlank $chatFilename -or IsBlank $embedFilename) {
  Fail "Could not derive filenames from model paths."
}
if ([string]::Equals($chatFilename, $embedFilename, [System.StringComparison]::OrdinalIgnoreCase)) {
  Fail "Chat and embedding filenames collide (case-insensitive). Rename one of the files."
}

$chatPathInPack = "payload/models/$chatFilename"
$embedPathInPack = "payload/models/$embedFilename"
ValidatePathInPack $chatPathInPack
ValidatePathInPack $embedPathInPack

$outFull = NormalizeFullPath $OutputZip
$outDir = [System.IO.Path]::GetDirectoryName($outFull)
if (-not (IsBlank $outDir)) {
  EnsureDir $outDir
}
if ((Test-Path -LiteralPath $outFull) -and (-not $Force.IsPresent)) {
  Fail "OutputZip already exists (use -Force to overwrite): $outFull"
}
if ((Test-Path -LiteralPath $outFull) -and $Force.IsPresent) {
  Remove-Item -LiteralPath $outFull -Force
}

Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$compressionEnum = [System.IO.Compression.CompressionLevel]::$CompressionLevel

$createdAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss'Z'")

Write-Host ("Building pack zip: " + $outFull)
Write-Host ("- packId: " + $PackId)
Write-Host ("- packVersion: " + $PackVersion)
Write-Host ("- chat: " + $chatFull)
Write-Host ("- embed: " + $embedFull)

$fileMeta = @{}

$fs = $null
$zip = $null
try {
  $fs = [System.IO.FileStream]::new(
    $outFull,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::None
  )
  $zip = [System.IO.Compression.ZipArchive]::new($fs, [System.IO.Compression.ZipArchiveMode]::Create, $true)

  function AddPayload([string] $id, [string] $srcPath, [string] $pathInPack) {
    $entryName = $pathInPack
    if ($entryName.Contains("\")) { Fail "Internal error: entryName contains backslash: $entryName" }
    $entry = $zip.CreateEntry($entryName, $compressionEnum)

    $inStream = $null
    $outStream = $null
    try {
      $inStream = [System.IO.FileStream]::new(
        $srcPath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read,
        1048576,
        [System.IO.FileOptions]::SequentialScan
      )
      $outStream = $entry.Open()
      $h = CopyStreamWithSha256 -src $inStream -dst $outStream -bufferSizeBytes 4194304
    } finally {
      if ($outStream) { $outStream.Dispose() }
      if ($inStream) { $inStream.Dispose() }
    }

    $sha = NormalizeSha256 $h.sha256 ("File '$pathInPack'")
    if ($h.sizeBytes -le 0) { Fail "File '$pathInPack' produced empty output; refusing to create pack." }

    return [pscustomobject]@{
      id = $id
      pathInPack = $pathInPack
      sha256 = $sha
      sizeBytes = [long]$h.sizeBytes
    }
  }

  $chatEntry = AddPayload -id "chat" -srcPath $chatFull -pathInPack $chatPathInPack
  $embedEntry = AddPayload -id "embed" -srcPath $embedFull -pathInPack $embedPathInPack

  # Construct manifest (deterministic JSON).
  $files = @($chatEntry, $embedEntry)
  $assets = @(
    [pscustomobject]@{ role = "model.chat"; fileId = "chat" },
    [pscustomobject]@{ role = "model.embedding"; fileId = "embed" }
  )
  $manifestJson = BuildManifestJson `
    -packId $PackId.Trim() `
    -packVersion $PackVersion.Trim() `
    -createdAt $createdAt `
    -requiresAppMin $RequiresAppMin.Trim() `
    -requiresAppMax $RequiresAppMax `
    -files $files `
    -assets $assets `
    -pretty:$PrettyManifest

  $manifestBytes = [System.Text.Encoding]::UTF8.GetBytes($manifestJson)
  $manifestSha256 = Sha256HexFromBytes $manifestBytes
  $manifestSha256 = NormalizeSha256 $manifestSha256 "Manifest"

  # Write manifest as exact bytes we hashed (this is the allowlist key).
  $manifestEntry = $zip.CreateEntry("pack-manifest.v1.json", [System.IO.Compression.CompressionLevel]::Optimal)
  $ms = $null
  try {
    $ms = $manifestEntry.Open()
    $ms.Write($manifestBytes, 0, $manifestBytes.Length)
  } finally {
    if ($ms) { $ms.Dispose() }
  }

  $fileMeta.manifestSha256 = $manifestSha256
  $fileMeta.files = $files
} finally {
  if ($zip) { $zip.Dispose() }
  if ($fs) { $fs.Dispose() }
}

if (-not $SkipVerify.IsPresent) {
  Write-Host "Verifying created zip against manifest (fail-closed)..."

  $expected = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
  [void]$expected.Add("pack-manifest.v1.json")
  foreach ($f in $fileMeta.files) { [void]$expected.Add($f.pathInPack) }

  $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
  $zip2 = $null
  try {
    $zip2 = [System.IO.Compression.ZipFile]::OpenRead($outFull)
    foreach ($e in $zip2.Entries) {
      $name = $e.FullName
      if ($null -eq $name) { Fail "Zip contains entry with null name." }
      if ($name.Contains("\")) { Fail "Zip contains backslash entry name (rejected by importer): $name" }
      if (-not $seen.Add($name)) { Fail "Zip contains duplicate entry name (case-insensitive): $name" }
      if (-not $expected.Contains($name)) { Fail "Zip contains unexpected file (fail closed): $name" }
    }
    if ($seen.Count -ne $expected.Count) {
      $missing = @()
      foreach ($n in $expected) { if (-not $seen.Contains($n)) { $missing += $n } }
      Fail ("Zip is missing expected entries: " + ($missing -join ", "))
    }

    # Verify manifest digest from stored bytes.
    $me = $zip2.GetEntry("pack-manifest.v1.json")
    if ($null -eq $me) { Fail "Zip missing pack-manifest.v1.json" }
    $mStream = $null
    try {
      $mStream = $me.Open()
      $mBytes = New-Object byte[] $me.Length
      [int]$offset = 0
      while ($offset -lt $mBytes.Length) {
        $r = $mStream.Read($mBytes, $offset, $mBytes.Length - $offset)
        if ($r -le 0) { break }
        $offset += $r
      }
      if ($offset -ne $mBytes.Length) { Fail "Failed to read entire manifest entry for verification." }
      $gotManifestSha = Sha256HexFromBytes $mBytes
      $gotManifestSha = NormalizeSha256 $gotManifestSha "Manifest"
      if ($gotManifestSha -ne $fileMeta.manifestSha256) {
        Fail ("Manifest digest mismatch. expected=" + $fileMeta.manifestSha256 + " got=" + $gotManifestSha)
      }
    } finally {
      if ($mStream) { $mStream.Dispose() }
    }

    # Verify each payload entry hash+size by reading entry bytes (mirrors importer).
    foreach ($f in $fileMeta.files) {
      $pe = $zip2.GetEntry($f.pathInPack)
      if ($null -eq $pe) { Fail "Zip missing declared payload file: $($f.pathInPack)" }
      if ([long]$pe.Length -ne [long]$f.sizeBytes) {
        Fail ("Size mismatch for " + $f.pathInPack + " expected=" + $f.sizeBytes + " got=" + $pe.Length)
      }
      $pStream = $null
      try {
        $pStream = $pe.Open()
        $hv = ComputeSha256AndCountFromStream -stream $pStream -bufferSizeBytes 4194304
        $gotSha = NormalizeSha256 $hv.sha256 ("File '$($f.pathInPack)'")
        if ([long]$hv.sizeBytes -ne [long]$f.sizeBytes) {
          Fail ("Read size mismatch for " + $f.pathInPack + " expected=" + $f.sizeBytes + " got=" + $hv.sizeBytes)
        }
        if ($gotSha -ne $f.sha256) {
          Fail ("SHA-256 mismatch for " + $f.pathInPack + " expected=" + $f.sha256 + " got=" + $gotSha)
        }
      } finally {
        if ($pStream) { $pStream.Dispose() }
      }
    }
  } finally {
    if ($zip2) { $zip2.Dispose() }
  }

  Write-Host "Verification: OK"
}

$manifestSha256Out = $fileMeta.manifestSha256
$chatShaOut = ($fileMeta.files | Where-Object { $_.id -eq "chat" } | Select-Object -First 1).sha256
$embedShaOut = ($fileMeta.files | Where-Object { $_.id -eq "embed" } | Select-Object -First 1).sha256

Write-Host ""
Write-Host ("manifestSha256: " + $manifestSha256Out)
Write-Host ""
Write-Host "policy.v1.json snippet (machine: %PROGRAMDATA%\\JustSearch\\policy.v1.json, user: <AI_HOME>\\policy.v1.json):"
Write-Host (PrintPolicySnippet -manifestSha256 $manifestSha256Out -modelSha256 @($chatShaOut, $embedShaOut))
} catch {
  $exitCode = 1
  $mainError = $_
} finally {
  if ($evidenceEnabled) {
    if ($transcriptStarted) {
      try { Stop-Transcript | Out-Null } catch { }
    }

    # Write metadata (avoid copying huge pack payloads into EvidenceBundle).
    try {
      $resolvedOut = $null
      try { $resolvedOut = [System.IO.Path]::GetFullPath($OutputZip) } catch { }

      $meta = [ordered]@{
        schema = "ai-pack-author.v1"
        packId = $PackId
        packVersion = $PackVersion
        requiresAppMin = $RequiresAppMin
        requiresAppMax = $RequiresAppMax
        chatModelPath = $ChatModelPath
        embeddingModelPath = $EmbeddingModelPath
        outputZip = $resolvedOut
        compressionLevel = $CompressionLevel
        prettyManifest = [bool]$PrettyManifest.IsPresent
        skipVerify = [bool]$SkipVerify.IsPresent
        createdAt = (Get-Date).ToString("o")
        outputs = [ordered]@{
          manifestSha256 = $manifestSha256Out
          chatModelSha256 = $chatShaOut
          embeddingModelSha256 = $embedShaOut
        }
      }
      if ($resolvedOut -and (Test-Path -LiteralPath $resolvedOut)) {
        $meta.outputZipInfo = [ordered]@{
          bytes = (Get-Item -LiteralPath $resolvedOut).Length
        }
      }
      $meta | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $metaPath -Encoding UTF8
    } catch {
      Write-Warning "Failed to write ai-pack metadata (non-fatal): $($_.Exception.Message)"
    }

    try {
      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureArgs = @(
        "--scenario=ai-pack-author",
        "--api-base-url=none",
        "--out-root=$EvidenceOutRoot",
        "--trace=false",
        "--attach-label=ai_pack",
        "--attach-file=$metaPath"
      )
      if ($transcriptPath -and (Test-Path -LiteralPath $transcriptPath)) {
        $captureArgs += "--attach-file=$transcriptPath"
      }

      if ($mainError) {
        $captureArgs += "--external-status=failed"
        $captureArgs += ("--external-error=" + $mainError.Exception.Message)
      } else {
        $captureArgs += "--external-status=passed"
      }

      $bundleDirRaw = $null
      $capExit = 0
      Push-Location $repoRoot
      try {
        $bundleDirRaw = & node $captureScript @captureArgs
        $capExit = $LASTEXITCODE
      } finally {
        Pop-Location
      }
      $bundleDir = ([string]$bundleDirRaw).Trim()

      if ([string]::IsNullOrWhiteSpace($bundleDir)) {
        $msg = "Evidence capture produced no bundle path (exit=$capExit)."
        if ($mainError) { Write-Warning $msg } else { throw $msg }
      }

      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if (-not $mainError -and $capExit -ne 0) {
        throw "Evidence capture reported failed status (exit=$capExit). bundle=$bundleDir"
      }

      if ($ValidateEvidenceBundle.IsPresent -and -not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Push-Location $repoRoot
        try {
          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs") $bundleDir
          if ($LASTEXITCODE -ne 0) { throw "validate-evidencebundle-v1 failed (exit=$LASTEXITCODE)" }
          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs") $bundleDir | Out-Null
        } finally {
          Pop-Location
        }
      }

      if (-not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Write-Host ("EvidenceBundle: " + $bundleDir)
      }
    } catch {
      if ($mainError) {
        Write-Warning ("Evidence capture/validation failed (non-fatal): " + $_.Exception.Message)
      } else {
        throw
      }
    }
  }
}

if ($mainError) { throw $mainError }
exit $exitCode

