# Crop a region from a PNG and scale it up for legibility.
# Usage: crop.ps1 -In a.png -Out b.png -X 0 -Y 0 -W 800 -H 40 -Scale 3
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
#
# Round 11 (tempdoc 805 item 6): this script's dimension parameters are
# -W/-H, not -Width/-Height -- and `powershell -File` silently DROPS
# unrecognized named parameters instead of erroring, so passing -Width/-Height
# left $W/$H at their 100x100 defaults with NO error and exit 0. A round's
# first crop from that misspelling was a meaningless 100x100 patch nearly
# read as evidence. Fail loud instead: scan the leftover $args (where
# PowerShell -File invocation puts any token it could not bind to a declared
# parameter) for anything shaped like an unrecognized -Flag and refuse to run.
param([string]$In, [string]$Out, [int]$X = 0, [int]$Y = 0, [int]$W = 100, [int]$H = 100, [int]$Scale = 3)

$unrecognized = @($args | Where-Object { $_ -match '^-[A-Za-z]' })
if ($unrecognized.Count -gt 0) {
  Write-Output "UNKNOWN PARAMETER(S): $($unrecognized -join ', ') -- crop.ps1 takes -In -Out -X -Y -W -H -Scale (NOT -Width/-Height). Refusing to run with silently-defaulted dimensions."
  exit 1
}

Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

[void](Save-AppShotRegion -InPath $In -OutPath $Out -X $X -Y $Y -W $W -H $H -Scale $Scale)
