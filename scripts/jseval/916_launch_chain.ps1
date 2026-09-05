# Tempdoc 916 Part 1 — historical detached launcher for a SEQUENCE of campaign phases.
# The shipping tree has no temporary chunk-sweep binding; the driver preflight refuses
# execution until that binding is deliberately restored in a throwaway experiment branch.
#
# One backend runs at a time, so phases with different `--arms` per corpus cannot be
# parallel and must not leave an idle gap between them either. This runs them back to
# back inside one detached process, under one run-watcher state dir.
param(
  [Parameter(Mandatory = $true)][string]$Name,
  [Parameter(Mandatory = $true)][string]$Steps,   # phase|corpora|arms|reps|deadline ; ...
  [int]$GpuIdlePct = 25,
  [string]$Out = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\jseval\tmp\916-part1\sweep-20260903"
)

$ErrorActionPreference = "Stop"
$jseval = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\jseval"
$watcher = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\dev\run-watcher.mjs"
$state = Join-Path $Out ("watch-" + $Name)
New-Item -ItemType Directory -Force -Path $state | Out-Null

# The inner script the watcher supervises: one python invocation per step, in order.
$inner = Join-Path $state "chain.ps1"
$lines = @("`$ErrorActionPreference = 'Stop'")
foreach ($step in $Steps.Split(";")) {
  $p = $step.Split("|")
  $a = "'916_chunk_sweep.py','run','--out','$Out','--corpora','$($p[1])','--reps','$($p[3])'," +
       "'--deadline-ms','$($p[4])','--phase','$($p[0])','--gpu-idle-pct','$GpuIdlePct'"
  if ($p[2] -ne "") { $a += ",'--arms','$($p[2])'" }
  $lines += "& python @($a)"
  $lines += "if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }"
}
$lines += "'CHAIN COMPLETE'"
Set-Content -Path $inner -Value $lines -Encoding utf8

$argList = @("$watcher", "run", "--dir", "$state", "--marker-on-exit", "PHASE.done", "--",
             "powershell", "-NoProfile", "-File", "$inner")
$proc = Start-Process -FilePath "node" -ArgumentList $argList -WorkingDirectory $jseval `
  -RedirectStandardOutput (Join-Path $state "watcher.out") `
  -RedirectStandardError (Join-Path $state "watcher.err") `
  -WindowStyle Hidden -PassThru
"chain=$Name pid=$($proc.Id) state=$state"
