# _gc-summary.ps1 - summarise total GC pause time across the gc.log rotation set.
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$files = @()
$files += Join-Path $root "tmp/gradle-diagnostic/gc.log.1"
$files += Join-Path $root "tmp/gradle-diagnostic/gc.log.0"
$files += Join-Path $root "tmp/gradle-diagnostic/gc.log"
foreach ($f in $files) {
  if (-not (Test-Path $f)) { continue }
  $head = Get-Content $f -TotalCount 1
  $tail = Get-Content $f -Tail 1
  $size = (Get-Item $f).Length
  Write-Output ("=== {0} ({1:N0} bytes) ===" -f $f, $size)
  Write-Output ("HEAD: " + $head)
  Write-Output ("TAIL: " + $tail)
  Write-Output ""
}

# Parse total pause time from all files
$pausePattern = [regex]'Pause \w+.*?\s([\d\.]+)ms$'
$totalPauseMs = 0.0
$pauseCount = 0
$pauses = @()
foreach ($f in $files) {
  if (-not (Test-Path $f)) { continue }
  Get-Content $f | ForEach-Object {
    $m = $pausePattern.Match($_)
    if ($m.Success) {
      $ms = [double]$m.Groups[1].Value
      $totalPauseMs += $ms
      $pauseCount += 1
      $pauses += $ms
    }
  }
}

Write-Output "=== GC pause summary ==="
"Total pauses: $pauseCount"
"Total pause wall: {0:F1} ms = {1:F2} s" -f $totalPauseMs, ($totalPauseMs / 1000)
if ($pauseCount -gt 0) {
  $sorted = $pauses | Sort-Object
  "Min / p50 / p95 / Max: {0:F1} / {1:F1} / {2:F1} / {3:F1} ms" -f `
    $sorted[0], `
    $sorted[[int]($pauseCount * 0.5)], `
    $sorted[[int]($pauseCount * 0.95)], `
    $sorted[$pauseCount - 1]
  "Mean: {0:F1} ms" -f ($totalPauseMs / $pauseCount)
}
