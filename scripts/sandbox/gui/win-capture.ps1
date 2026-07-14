# Find, focus, and capture a specific top-level window by process name.
# Proves per-window GUI capture without any computer-use tool.
# Usage: win-capture.ps1 -ProcName JustSearch -Out shot.png [-Keys "^k"] [-DelayMs 1200]
param(
  [string]$ProcName = "JustSearch",
  [string]$Out = "win.png",
  [string]$Keys = "",
  [int]$DelayMs = 1200
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr h);
  public struct R { public int Left, Top, Right, Bottom; }
}
"@

$p = Get-Process -Name $ProcName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Output "NO WINDOW for process '$ProcName'"; exit 1 }
$h = $p.MainWindowHandle
Write-Output "process: $($p.ProcessName) pid=$($p.Id) hwnd=$h title='$($p.MainWindowTitle)'"

[void][W]::ShowWindow($h, 9)   # SW_RESTORE
[void][W]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 700

$fg = [W]::GetForegroundWindow()
Write-Output "foreground hwnd after focus: $fg  (match=$($fg -eq $h))"

if ($Keys -ne "") {
  Write-Output "sending keys: $Keys"
  [System.Windows.Forms.SendKeys]::SendWait($Keys)
  Start-Sleep -Milliseconds $DelayMs
}

$r = New-Object W+R
[void][W]::GetWindowRect($h, [ref]$r)
$w = $r.Right - $r.Left
$ht = $r.Bottom - $r.Top
Write-Output "window rect: $($r.Left),$($r.Top) ${w}x${ht}"
if ($w -le 0 -or $ht -le 0) { Write-Output "BAD RECT"; exit 1 }

$bmp = New-Object System.Drawing.Bitmap($w, $ht)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen((New-Object System.Drawing.Point($r.Left, $r.Top)), [System.Drawing.Point]::Empty, (New-Object System.Drawing.Size($w, $ht)))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved: $Out ($((Get-Item $Out).Length) bytes)"
