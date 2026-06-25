# run-P1.ps1 - orchestrate the P1 EXPORTCSV SINGLELINE probe.
# Launches PassMark with the P1 ptscript, polls tmp/P1/passmark-p1-iter.csv size + row count
# every 3 seconds, records transitions to P1-watch.log. Exits when PassMark exits.
#
# Invoke (elevated) from F:\JustSearch:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bench\run-P1.ps1

$ErrorActionPreference = "Stop"
$root      = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$outDir    = Join-Path $root "tmp\P1"
$pt        = "C:\Program Files\PerformanceTest\PerformanceTest64.exe"
$ptscript  = Join-Path $root "scripts\bench\passmark-p1-reprobe.ptscript"
$iterCsv   = Join-Path $outDir "passmark-p1-iter.csv"
$summary   = Join-Path $outDir "passmark-p1-summary.csv"
$watchLog  = Join-Path $outDir "P1-watch.log"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
# Clear prior run so we're starting from zero.
Remove-Item -Force -ErrorAction SilentlyContinue $iterCsv, $summary

function Log($m) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m
  Write-Host $line
  $line | Out-File -FilePath $watchLog -Append -Encoding ASCII
}

Log "P1 probe start. Script: $ptscript"
Log "Pre-run: iter.csv exists? $([bool](Test-Path $iterCsv))"

$start = Get-Date
$proc = Start-Process -FilePath $pt `
  -ArgumentList "/s","`"$ptscript`"" `
  -WorkingDirectory $root `
  -PassThru

Log "PassMark PID=$($proc.Id) started."

$lastSize = -1
$lastRows = -1
while (-not $proc.HasExited) {
  Start-Sleep -Milliseconds 2000
  if (Test-Path $iterCsv) {
    $size = (Get-Item $iterCsv).Length
    $rows = (Get-Content $iterCsv | Measure-Object -Line).Lines
    if ($size -ne $lastSize -or $rows -ne $lastRows) {
      Log "iter.csv -> size=$size bytes, lines=$rows"
      $lastSize = $size
      $lastRows = $rows
    }
  } else {
    if ($lastSize -ne 0) {
      # Log "iter.csv not yet created"
      $lastSize = 0
    }
  }
}

$wall = ((Get-Date) - $start).TotalSeconds
Log "PassMark exited. Wall: $([math]::Round($wall,1))s. ExitCode: $($proc.ExitCode)"
Log ""
Log "=== Final state ==="
if (Test-Path $iterCsv) {
  $rows = (Get-Content $iterCsv | Measure-Object -Line).Lines
  Log "iter.csv exists: $rows lines, $((Get-Item $iterCsv).Length) bytes"
  Log "--- iter.csv contents ---"
  Get-Content $iterCsv | ForEach-Object { Log "  $_" }
} else {
  Log "iter.csv DOES NOT EXIST - SINGLELINE inside LOOP wrote nothing"
}
if (Test-Path $summary) {
  $rows = (Get-Content $summary | Measure-Object -Line).Lines
  Log "summary.csv exists: $rows lines, $((Get-Item $summary).Length) bytes"
} else {
  Log "summary.csv DOES NOT EXIST"
}
Log "P1 probe complete."
