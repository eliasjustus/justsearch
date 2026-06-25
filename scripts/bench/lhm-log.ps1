# lhm-log.ps1 — continuous CSV sensor log via LibreHardwareMonitorLib.dll direct API.
# Zero clicks, no UAC required (limited CPU sensor coverage without admin).
# Usage (from F:\JustSearch):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bench\lhm-log.ps1 `
#     -Output tmp\B1\lhm.csv -IntervalSec 1 -DurationSec 2400
#
# Stop early: Ctrl-C or kill the pwsh process — CSV is flushed per row.
param(
  [string]$Output = "tmp\B1\lhm.csv",
  [double]$IntervalSec = 1.0,
  [int]$DurationSec = 0,   # 0 = infinite
  [string]$LhmDll = ""
)

# DLL resolution order:
# 1. explicit -LhmDll parameter
# 2. $env:LHM_DLL (recommended if WinGet cache path changes or a copy lives elsewhere)
# 3. WinGet user-cache default (current install path 2026-04-19)
if (-not $LhmDll) {
  if ($env:LHM_DLL -and (Test-Path $env:LHM_DLL)) {
    $LhmDll = $env:LHM_DLL
  } else {
    $LhmDll = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\LibreHardwareMonitor.LibreHardwareMonitor_Microsoft.Winget.Source_8wekyb3d8bbwe\LibreHardwareMonitorLib.dll"
  }
}

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LhmDll)) { throw "LHM DLL not found: $LhmDll" }
$outDir = Split-Path $Output
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

Add-Type -Path $LhmDll

$c = New-Object LibreHardwareMonitor.Hardware.Computer
$c.IsCpuEnabled = $true
$c.IsGpuEnabled = $true
$c.IsMotherboardEnabled = $true
$c.IsStorageEnabled = $true
$c.IsMemoryEnabled = $true
$c.IsNetworkEnabled = $false
$c.IsBatteryEnabled = $false
$c.Open()

# First Update() pass to populate sensor list
foreach ($h in $c.Hardware) {
  $h.Update()
  foreach ($sh in $h.SubHardware) { $sh.Update() }
}

# Build stable sensor key list (used as CSV columns). Key = "<HW>|<SubHW|?>|<Sensor>|<Type>"
$sensorKeys = New-Object System.Collections.Generic.List[string]
$sensorMap  = @{}   # key → sensor object (for value fetch)
foreach ($h in $c.Hardware) {
  foreach ($s in $h.Sensors) {
    $k = "$($h.Name)|-|$($s.Name)|$($s.SensorType)"
    $sensorKeys.Add($k); $sensorMap[$k] = $s
  }
  foreach ($sh in $h.SubHardware) {
    foreach ($s in $sh.Sensors) {
      $k = "$($h.Name)|$($sh.Name)|$($s.Name)|$($s.SensorType)"
      $sensorKeys.Add($k); $sensorMap[$k] = $s
    }
  }
}

# Write CSV header
$header = "timestamp," + ($sensorKeys -join ",")
$header | Out-File -FilePath $Output -Encoding ASCII -Force

$startTick = [System.Diagnostics.Stopwatch]::StartNew()
$intervalMs = [int]($IntervalSec * 1000)

try {
  while ($true) {
    # Refresh all hardware
    foreach ($h in $c.Hardware) {
      $h.Update()
      foreach ($sh in $h.SubHardware) { $sh.Update() }
    }
    $ts = (Get-Date).ToString("o")
    $vals = foreach ($k in $sensorKeys) {
      $v = $sensorMap[$k].Value
      if ($null -eq $v) { "" } else { ([double]$v).ToString("0.######", [System.Globalization.CultureInfo]::InvariantCulture) }
    }
    ($ts + "," + ($vals -join ",")) | Out-File -FilePath $Output -Append -Encoding ASCII

    if ($DurationSec -gt 0 -and $startTick.Elapsed.TotalSeconds -ge $DurationSec) { break }
    Start-Sleep -Milliseconds $intervalMs
  }
} finally {
  $c.Close()
}
