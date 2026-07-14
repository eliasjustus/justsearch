# Click at window-relative coords in a target window, then capture.
# Usage: click.ps1 -ProcName JustSearch -X 738 -Y 537 -Out after.png
param([string]$ProcName="JustSearch",[int]$X=0,[int]$Y=0,[string]$Out="after.png",[int]$DelayMs=2000)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class M {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  public const uint LEFTDOWN = 0x0002, LEFTUP = 0x0004;
  public struct R { public int Left, Top, Right, Bottom; }
}
"@
$p = Get-Process -Name $ProcName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Output "NO WINDOW"; exit 1 }
$h = $p.MainWindowHandle
[void][M]::ShowWindow($h, 9); [void][M]::SetForegroundWindow($h); Start-Sleep -Milliseconds 600
$r = New-Object M+R; [void][M]::GetWindowRect($h, [ref]$r)
$sx = $r.Left + $X; $sy = $r.Top + $Y
Write-Output "window at $($r.Left),$($r.Top) -> clicking screen ($sx,$sy) [window-rel $X,$Y]"
[void][M]::SetCursorPos($sx, $sy); Start-Sleep -Milliseconds 250
[M]::mouse_event([M]::LEFTDOWN, 0,0,0,[IntPtr]::Zero); Start-Sleep -Milliseconds 80
[M]::mouse_event([M]::LEFTUP, 0,0,0,[IntPtr]::Zero)
Start-Sleep -Milliseconds $DelayMs
$r2 = New-Object M+R; [void][M]::GetWindowRect($h, [ref]$r2)
$w = $r2.Right-$r2.Left; $ht = $r2.Bottom-$r2.Top
$bmp = New-Object System.Drawing.Bitmap($w,$ht); $g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen((New-Object System.Drawing.Point($r2.Left,$r2.Top)), [System.Drawing.Point]::Empty, (New-Object System.Drawing.Size($w,$ht)))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
Write-Output "saved: $Out ($((Get-Item $Out).Length) bytes)"
