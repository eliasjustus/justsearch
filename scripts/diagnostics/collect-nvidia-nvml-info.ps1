Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "JustSearch NVML diagnostics (Windows)" -ForegroundColor Cyan
Write-Host ("Time: {0:o}" -f (Get-Date))
Write-Host ("PSVersion: {0}" -f $PSVersionTable.PSVersion)
Write-Host ("OS: {0}" -f [System.Environment]::OSVersion.VersionString)
Write-Host ""

$candidates = @(
  (Join-Path $env:SystemRoot "System32\nvml.dll"),
  (Join-Path ${env:ProgramFiles} "NVIDIA Corporation\NVSMI\nvml.dll")
)

Write-Host "NVML candidates:"
foreach ($p in $candidates) {
  if (Test-Path -LiteralPath $p) {
    $item = Get-Item -LiteralPath $p
    $vi = $item.VersionInfo
    Write-Host ("  FOUND: {0}" -f $p) -ForegroundColor Green
    Write-Host ("    FileVersion:    {0}" -f $vi.FileVersion)
    Write-Host ("    ProductVersion: {0}" -f $vi.ProductVersion)
    Write-Host ("    CompanyName:    {0}" -f $vi.CompanyName)
    Write-Host ("    FileSizeBytes:  {0}" -f $item.Length)
  } else {
    Write-Host ("  MISSING: {0}" -f $p) -ForegroundColor DarkYellow
  }
}

Write-Host ""
Write-Host "nvidia-smi (optional):"
try {
  $cmd = Get-Command nvidia-smi -ErrorAction Stop
  Write-Host ("  Path: {0}" -f $cmd.Source)
  & nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader
} catch {
  Write-Host "  nvidia-smi not available on PATH (this is expected in some environments, e.g., Windows Sandbox)."
}


