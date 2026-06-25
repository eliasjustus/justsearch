# Analyse telemetry from tmp/B1/. Manual CSV parse (Import-Csv chokes on LHM duplicate columns).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$B1 = Join-Path $root "tmp\B1"

Write-Output "=== PassMark summary ==="
$pmLines = Get-Content (Join-Path $B1 "passmark-b1.csv")
$pmHeader = ($pmLines[0] -split ',(?=(?:[^"]*"[^"]*")*[^"]*$)')
$pmRow = ($pmLines[1] -split ',(?=(?:[^"]*"[^"]*")*[^"]*$)')
$targets = 'CPU Mark (Composite average)','Memory Mark (Composite average)','Disk Mark (Composite average)','2D Graphics Mark (Composite average)','3D Graphics Mark (Composite average)','PassMark Rating (Composite average)'
foreach ($t in $targets) {
  $i = [array]::IndexOf($pmHeader, $t)
  if ($i -ge 0) { "{0,-45} = {1}" -f $t, $pmRow[$i] }
}

Write-Output ""
Write-Output "=== LHM stats ==="
$lhmPath = Join-Path $B1 "lhm.csv"
$lhmHeader = (Get-Content $lhmPath -TotalCount 1) -split ','
Write-Output ("LHM rows (approx): {0}" -f ((Get-Content $lhmPath | Measure-Object -Line).Lines - 1))

function SummarizeCol($label, $colName) {
  $idx = [array]::IndexOf($lhmHeader, $colName)
  if ($idx -lt 0) { Write-Output "$label : column not found: $colName"; return }
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
  "{0,-28} min={1,7:F1} p50={2,7:F1} p95={3,7:F1} max={4,7:F1} avg={5,7:F1} n={6}" -f $label, $stats.Minimum, $p50, $p95, $stats.Maximum, $stats.Average, $n
}

SummarizeCol "CPU Package Temp (C)"     '12th Gen Intel Core i7-12700K|-|CPU Package|Temperature'
SummarizeCol "Core Max Temp (C)"        '12th Gen Intel Core i7-12700K|-|Core Max|Temperature'
SummarizeCol "CPU Package Power (W)"    '12th Gen Intel Core i7-12700K|-|CPU Package|Power'
SummarizeCol "CPU Cores Power (W)"      '12th Gen Intel Core i7-12700K|-|CPU Cores|Power'
SummarizeCol "CPU Total Load (%)"       '12th Gen Intel Core i7-12700K|-|CPU Total|Load'
SummarizeCol "P-Core #1 Clock (MHz)"    '12th Gen Intel Core i7-12700K|-|P-Core #1|Clock'
SummarizeCol "P-Core #1 Temp (C)"       '12th Gen Intel Core i7-12700K|-|P-Core #1|Temperature'

Write-Output ""
SummarizeCol "GPU Core Temp (C)"        'NVIDIA GeForce RTX 4070|-|GPU Core|Temperature'
SummarizeCol "GPU Core Load (%)"        'NVIDIA GeForce RTX 4070|-|GPU Core|Load'
SummarizeCol "GPU Package Power (W)"    'NVIDIA GeForce RTX 4070|-|GPU Package|Power'
SummarizeCol "GPU Memory Load (%)"      'NVIDIA GeForce RTX 4070|-|GPU Memory|Load'
SummarizeCol "GPU Memory Used (MB)"     'NVIDIA GeForce RTX 4070|-|GPU Memory Used|SmallData'

Write-Output ""
SummarizeCol "NVMe Comp Temp (C)"       'Samsung SSD 990 PRO 2TB|-|Composite Temperature|Temperature'
SummarizeCol "NVMe Read Rate (B/s)"     'Samsung SSD 990 PRO 2TB|-|Read Rate|Throughput'
SummarizeCol "NVMe Write Rate (B/s)"    'Samsung SSD 990 PRO 2TB|-|Write Rate|Throughput'

Write-Output ""
SummarizeCol "Total RAM Load (%)"       'Total Memory|-|Memory|Load'
SummarizeCol "RAM Used (GB)"            'Total Memory|-|Memory Used|Data'

Write-Output ""
Write-Output "=== Motherboard fans (control % + RPM) ==="
for ($i = 1; $i -le 7; $i++) {
  SummarizeCol ("Fan #{0} Control (%)" -f $i) ("ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #{0}|Control" -f $i)
  SummarizeCol ("Fan #{0} RPM" -f $i)         ("ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #{0}|Fan" -f $i)
}

Write-Output ""
Write-Output "=== GPU fan ==="
SummarizeCol "GPU Fan RPM"              'NVIDIA GeForce RTX 4070|-|GPU Fan|Fan'
SummarizeCol "GPU Fan Control (%)"      'NVIDIA GeForce RTX 4070|-|GPU Fan|Control'
