# Crop a region from a PNG and scale it up for legibility.
# Usage: crop.ps1 -In a.png -Out b.png -X 0 -Y 0 -W 800 -H 40 -Scale 3
# Thin wrapper over JustSearchGui.psm1 -- $Out is echoed as an absolute,
# resolved path (Resolve-AppPath), not the bare filename.
param([string]$In, [string]$Out, [int]$X = 0, [int]$Y = 0, [int]$W = 100, [int]$H = 100, [int]$Scale = 3)
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

[void](Save-AppShotRegion -InPath $In -OutPath $Out -X $X -Y $Y -W $W -H $H -Scale $Scale)
