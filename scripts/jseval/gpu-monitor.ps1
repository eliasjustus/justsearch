# GPU monitoring script — polls nvidia-smi at 1s intervals and writes TSV
# Usage: powershell -File gpu-monitor.ps1 -OutputPath tmp/gpu-monitor.tsv -Duration 300
param(
    [string]$OutputPath = "tmp/gpu-monitor.tsv",
    [int]$Duration = 300,
    [int]$IntervalMs = 1000
)

$header = "timestamp`tgpu_clock_mhz`tmax_clock_mhz`ttemp_c`tpower_w`tgpu_util_pct`tmem_util_pct`tvram_used_mb"
$header | Out-File -FilePath $OutputPath -Encoding utf8

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
while ($stopwatch.Elapsed.TotalSeconds -lt $Duration) {
    $line = & nvidia-smi --query-gpu=timestamp,clocks.current.graphics,clocks.max.graphics,temperature.gpu,power.draw,utilization.gpu,utilization.memory,memory.used --format=csv,noheader,nounits 2>$null
    if ($line) {
        $parts = $line -split ',\s*'
        "$($parts[0])`t$($parts[1])`t$($parts[2])`t$($parts[3])`t$($parts[4])`t$($parts[5])`t$($parts[6])`t$($parts[7])" | Out-File -FilePath $OutputPath -Append -Encoding utf8
    }
    Start-Sleep -Milliseconds $IntervalMs
}
Write-Host "GPU monitor: wrote $OutputPath ($([math]::Round($stopwatch.Elapsed.TotalSeconds))s)"
