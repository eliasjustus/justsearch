# Crop a region from a PNG and scale it up for legibility.
# Usage: crop.ps1 -In a.png -Out b.png -X 0 -Y 0 -W 800 -H 40 -Scale 3
param([string]$In,[string]$Out,[int]$X=0,[int]$Y=0,[int]$W=100,[int]$H=100,[int]$Scale=3)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $In))
Write-Output "source: $($src.Width)x$($src.Height)"
if ($X + $W -gt $src.Width) { $W = $src.Width - $X }
if ($Y + $H -gt $src.Height) { $H = $src.Height - $Y }
$rect = New-Object System.Drawing.Rectangle($X,$Y,$W,$H)
$crop = New-Object System.Drawing.Bitmap($W,$H)
$g1 = [System.Drawing.Graphics]::FromImage($crop)
$g1.DrawImage($src, (New-Object System.Drawing.Rectangle(0,0,$W,$H)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
$g1.Dispose()
$big = New-Object System.Drawing.Bitmap(($W*$Scale),($H*$Scale))
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.DrawImage($crop, 0, 0, ($W*$Scale), ($H*$Scale))
$g2.Dispose()
$big.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$src.Dispose(); $crop.Dispose(); $big.Dispose()
Write-Output "saved: $Out ($($W*$Scale)x$($H*$Scale))"
