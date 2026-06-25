# _analyze-gradle-thermal.ps1 - thermal + clock + power summary during Gradle builds.
# Designed for tmp/gradle-diagnostic/lhm.csv. Same column schema as _analyze-B1.ps1.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$lhmPath = Join-Path $root "tmp\gradle-diagnostic\lhm.csv"

$header = (Get-Content $lhmPath -TotalCount 1) -split ','
$totalRows = (Get-Content $lhmPath | Measure-Object -Line).Lines - 1
Write-Output "LHM rows: $totalRows ($([math]::Round($totalRows / 60, 1)) min of 1 Hz data)"
Write-Output ""

function Summarize($label, $col) {
  $idx = [array]::IndexOf($header, $col)
  if ($idx -lt 0) { Write-Output "$label : column not found"; return }
  $vals = @()
  Get-Content $lhmPath | Select-Object -Skip 1 | ForEach-Object {
    $cells = $_ -split ','
    if ($idx -lt $cells.Length -and $cells[$idx] -ne '') {
      $v = 0.0
      if ([double]::TryParse($cells[$idx], [ref]$v)) {
        if ($v -ne 0) { $vals += $v }
      }
    }
  }
  if ($vals.Count -eq 0) { Write-Output "$label : no data"; return }
  $sorted = $vals | Sort-Object
  $n = $sorted.Count
  $stats = $vals | Measure-Object -Minimum -Maximum -Average
  $p50 = $sorted[[int]($n * 0.50)]
  $p95 = $sorted[[int]($n * 0.95)]
  $above90 = ($vals | Where-Object { $_ -ge 90 }).Count
  $above80 = ($vals | Where-Object { $_ -ge 80 }).Count
  "{0,-28} min={1,7:F1} p50={2,7:F1} p95={3,7:F1} max={4,7:F1} avg={5,7:F1}  >=80C:{6,4}  >=90C:{7,4}  n={8}" -f $label, $stats.Minimum, $p50, $p95, $stats.Maximum, $stats.Average, $above80, $above90, $n
}

Summarize "CPU Package Temp (C)"   '12th Gen Intel Core i7-12700K|-|CPU Package|Temperature'
Summarize "Core Max Temp (C)"      '12th Gen Intel Core i7-12700K|-|Core Max|Temperature'
Summarize "CPU Package Power (W)"  '12th Gen Intel Core i7-12700K|-|CPU Package|Power'
Summarize "CPU Cores Power (W)"    '12th Gen Intel Core i7-12700K|-|CPU Cores|Power'
Summarize "CPU Total Load (%)"     '12th Gen Intel Core i7-12700K|-|CPU Total|Load'

Write-Output ""
Write-Output "P-Core clocks (throttling detection) -"
for ($i = 1; $i -le 8; $i++) {
  Summarize ("P-Core #{0} Clock" -f $i) ("12th Gen Intel Core i7-12700K|-|P-Core #{0}|Clock" -f $i)
}
