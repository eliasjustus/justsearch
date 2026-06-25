Get-Process nvidia-smi,smartctl,LibreHardwareMonitor -ErrorAction SilentlyContinue | Select-Object Name, Id, CPU | Format-Table -AutoSize
Write-Output '---powershell procs younger than 40 min---'
$cutoff = (Get-Date).AddMinutes(-40)
Get-Process powershell -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt $cutoff } | Select-Object Id, StartTime, CPU | Format-Table -AutoSize
Write-Output '---jobs---'
Get-Job | Format-Table -AutoSize Id, State, HasMoreData
