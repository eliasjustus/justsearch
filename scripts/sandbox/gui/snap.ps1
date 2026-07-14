# Native Windows screen capture via .NET - no computer-use tool required.
# Usage: powershell -File snap.ps1 <outfile.png>
param([string]$Out = "snap.png")
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
Write-Output "screen bounds: $($bounds.Width)x$($bounds.Height)"
$bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose()
$bmp.Dispose()
Write-Output "saved: $Out ($((Get-Item $Out).Length) bytes)"
