# check-bench-state.ps1 - one-shot snapshot of bench-relevant state.
# Run at the start of a new agent session to orient fast without re-probing.
#
# Invoke from F:\JustSearch:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bench\check-bench-state.ps1

$ErrorActionPreference = "Continue"

function Section($name) { Write-Output ""; Write-Output "=== $name ===" }

Section "Elevation"
[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Section "Power plan (active)"
powercfg /getactivescheme

Section "Windows Update / Defender services"
Get-Service wuauserv,UsoSvc,WaaSMedicSvc,SysMain,WSearch,WinDefend -ErrorAction SilentlyContinue | Format-Table -AutoSize Name,Status,StartType

Section "Scheduled tasks (WaaSMedic)"
$t = Get-ScheduledTask -TaskName "PerformRemediation" -TaskPath "\Microsoft\Windows\WaaSMedic\" -ErrorAction SilentlyContinue
if ($t) { "{0,-20} {1}" -f $t.TaskName, $t.State } else { "(not found)" }

Section "Bench-related processes"
Get-Process PerformanceTest64,HWiNFO64,LibreHardwareMonitor,llama-server,nvidia-smi,java,gradle -ErrorAction SilentlyContinue | Select-Object Name,Id,CPU,StartTime | Format-Table -AutoSize

Section "Defender exclusions"
$mp = Get-MpPreference
"ExclusionPath     : {0}" -f (($mp.ExclusionPath      | ForEach-Object { $_ }) -join '; ')
"ExclusionExt      : {0}" -f (($mp.ExclusionExtension | ForEach-Object { $_ }) -join '; ')
"ExclusionProcess  : {0}" -f (($mp.ExclusionProcess   | ForEach-Object { $_ }) -join '; ')
"RealTimeProtection: {0}" -f (-not $mp.DisableRealtimeMonitoring)

Section "Dev Drive status (F:)"
fsutil devdrv query F: 2>$null | Select-String -Pattern 'trusted|WdFilter'

Section "Dev stack (port 33221)"
$c = Get-NetTCPConnection -LocalPort 33221 -State Listen -ErrorAction SilentlyContinue
if ($c) { "LISTENING on :33221 (PID=$($c[0].OwningProcess))" } else { "(not listening)" }

Section "Disk free (F:, C:)"
Get-PSDrive F,C -ErrorAction SilentlyContinue | Select-Object Name,@{N='FreeGB';E={[math]::Round($_.Free/1GB,1)}},@{N='UsedGB';E={[math]::Round($_.Used/1GB,1)}} | Format-Table -AutoSize

Section "Git worktree + branch"
$pwd = Get-Location
"CWD: $pwd"
$branch = (& git branch --show-current 2>$null)
"Branch: $branch"
$status = (& git status --short 2>$null | Measure-Object -Line).Lines
"Uncommitted lines: $status"

Section "Recent tmp/ dirs"
Get-ChildItem F:\JustSearch\tmp -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 8 Name, LastWriteTime | Format-Table -AutoSize

Section "390 results folders"
Get-ChildItem F:\JustSearch\docs\tempdocs\390-results -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object Name, LastWriteTime | Format-Table -AutoSize
