param(
  [string]$Major = "24"
)

$ErrorActionPreference = 'Stop'

function Get-NodeZipUrl([string]$major) {
  try {
    $latestPage = Invoke-WebRequest -UseBasicParsing -Uri "https://nodejs.org/dist/latest-v$major.x/"
    $m = [regex]::Match($latestPage.Content, "node-(v$major\.[0-9]+\.[0-9+])-win-x64\.zip")
    if ($m.Success) {
      $ver = $m.Groups[1].Value
      return "https://nodejs.org/dist/$ver/node-$ver-win-x64.zip"
    }
  } catch {}
  # Fallback to a conservative baseline
  return "https://nodejs.org/dist/v$major.0.0/node-v$major.0.0-win-x64.zip"
}

$toolsDir = Join-Path $PSScriptRoot "..\.tools\node"
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

# If already present, skip download
$existing = Get-ChildItem -Path $toolsDir -Filter "node-v$Major.*-win-x64" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $existing) {
  $url = Get-NodeZipUrl -major $Major
  $zip = Join-Path $toolsDir "node-$Major.zip"
  Write-Host "Downloading Node from $url ..."
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
  Write-Host "Extracting to $toolsDir ..."
  Expand-Archive -LiteralPath $zip -DestinationPath $toolsDir -Force
  Remove-Item $zip -Force
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

