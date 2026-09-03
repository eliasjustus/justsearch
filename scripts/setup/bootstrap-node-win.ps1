param(
  [string]$Major = "24",
  [string]$DistributionBaseUrl = "https://nodejs.org/dist",
  [switch]$ResolveOnly
)

$ErrorActionPreference = 'Stop'

function Get-DistributionBaseUri([string]$baseUrl) {
  try {
    $uri = [Uri]::new($baseUrl, [UriKind]::Absolute)
  } catch {
    throw "DistributionBaseUrl must be an absolute HTTP(S) URL. Received '$baseUrl'."
  }

  if ($uri.Scheme -notin @('http', 'https') -or $uri.Query -or $uri.Fragment) {
    throw "DistributionBaseUrl must be an absolute HTTP(S) URL without a query or fragment. Received '$baseUrl'."
  }

  return [Uri]::new($uri.AbsoluteUri.TrimEnd('/') + '/')
}

function Get-NodeZipUrl([string]$major, [string]$distributionBaseUrl) {
  if ($major -notmatch '^[0-9]+$') {
    throw "Major must contain only digits. Received '$major'."
  }

  $baseUri = Get-DistributionBaseUri -baseUrl $distributionBaseUrl
  $indexUri = [Uri]::new($baseUri, "latest-v$major.x/")

  try {
    $latestPage = Invoke-WebRequest -UseBasicParsing -Uri $indexUri
  } catch {
    throw "Unable to fetch the Node.js distribution index '$indexUri'. Check the URL and network or proxy settings, then retry. $($_.Exception.Message)"
  }

  $escapedMajor = [regex]::Escape($major)
  $match = [regex]::Match(
    [string]$latestPage.Content,
    "node-(v$escapedMajor\.[0-9]+\.[0-9]+)-win-x64\.zip",
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
  )
  if (-not $match.Success) {
    throw "The Node.js distribution index '$indexUri' did not contain a Windows x64 archive matching 'node-v$major.<minor>.<patch>-win-x64.zip'. The index may be malformed or Node.js $major may be unavailable."
  }

  $version = $match.Groups[1].Value
  $archiveName = "node-$version-win-x64.zip"
  return [Uri]::new($baseUri, "$version/$archiveName").AbsoluteUri
}

if ($ResolveOnly) {
  Get-NodeZipUrl -major $Major -distributionBaseUrl $DistributionBaseUrl
  return
}

$toolsDir = Join-Path $PSScriptRoot "..\.tools\node"
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

# If already present, skip download
$existing = Get-ChildItem -Path $toolsDir -Filter "node-v$Major.*-win-x64" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $existing) {
  $url = Get-NodeZipUrl -major $Major -distributionBaseUrl $DistributionBaseUrl
  $zip = Join-Path $toolsDir "node-$Major.zip"
  Write-Host "Downloading Node from $url ..."
  try {
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
  } catch {
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    throw "Failed to download the Node.js archive from '$url' to '$zip'. Check network or proxy access and available disk space, then retry. $($_.Exception.Message)"
  }

  try {
    Write-Host "Extracting to $toolsDir ..."
    Expand-Archive -LiteralPath $zip -DestinationPath $toolsDir -Force
  } catch {
    throw "Failed to extract the Node.js archive '$zip' into '$toolsDir'. Remove any partial 'node-v$Major.*-win-x64' directory and retry. $($_.Exception.Message)"
  } finally {
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
  }
  $existing = Get-ChildItem -Path $toolsDir -Filter "node-v$Major.*-win-x64" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
}

if (-not $existing) { throw "Failed to locate extracted Node directory under $toolsDir" }

$nodeDir = $existing.FullName
Write-Host "Node installed at: $nodeDir"
Write-Host "Temporarily updating PATH for this session..."
$env:Path = "$nodeDir;$env:Path"
& node -v
& npm -v

Write-Host "To persist PATH, add the following to your profile or CI step:"
Write-Host "$env:Path"

