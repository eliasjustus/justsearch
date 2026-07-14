# EXAMPLE — worked pattern for a focus -> type -> click GUI ceremony, not a
# generic tool. This is the script that proved a full GUI-driven TYPED_CONFIRM
# approval end-to-end in the tempdoc-727-followup smoke round.
#
# The $ApproveX/$ApproveY/$FieldX/$FieldY defaults below are PIXEL COORDINATES
# captured from ONE dialog at ONE window size/theme/DPI in that round. They
# are NOT durable — re-derive them every round via observe -> locate -> act ->
# observe: snap.ps1 the dialog, read the target's coordinates off the PNG,
# THEN run this script (or a copy of it) with fresh -FieldX/-FieldY/-ApproveX/
# -ApproveY values. Copy this file per-surface rather than editing the
# defaults in place, so the worked example stays legible.
#
# One-shot: focus -> screenshot -> type confirm phrase -> screenshot -> click Approve -> screenshot.
# All in a single invocation so the dialog never resets between steps (see
# README.md "two mechanical gotchas" — splitting this across two script runs
# re-focuses the window and clears the field).
param(
  [string]$Phrase = "core.ingest-files",
  [int]$ApproveX = 738,
  [int]$ApproveY = 537,
  [int]$FieldX = 528,
  [int]$FieldY = 462,
  [string]$Tag = "gui"
)
$ev = "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\evidence"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class G {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  public const uint LEFTDOWN = 0x0002, LEFTUP = 0x0004;
  public struct R { public int Left, Top, Right, Bottom; }
}
"@
function Snap($h, $file) {
  $r = New-Object G+R; [void][G]::GetWindowRect($h, [ref]$r)
  $w = $r.Right-$r.Left; $ht = $r.Bottom-$r.Top
  $b = New-Object System.Drawing.Bitmap($w,$ht); $g = [System.Drawing.Graphics]::FromImage($b)
  $g.CopyFromScreen((New-Object System.Drawing.Point($r.Left,$r.Top)), [System.Drawing.Point]::Empty, (New-Object System.Drawing.Size($w,$ht)))
  $b.Save($file, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $b.Dispose()
  Write-Output "  snap -> $(Split-Path -Leaf $file)"
  return $r
}
$p = Get-Process -Name JustSearch -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Output "NO WINDOW"; exit 1 }
$h = $p.MainWindowHandle
[void][G]::ShowWindow($h, 9); [void][G]::SetForegroundWindow($h); Start-Sleep -Milliseconds 900

Write-Output "[1] dialog as presented:"
$r = Snap $h "$ev\$Tag-a-dialog.png"

Write-Output "[2a] clicking confirm field at window-rel ($FieldX,$FieldY) to focus it"
$fx = $r.Left + $FieldX; $fy = $r.Top + $FieldY
[void][G]::SetCursorPos($fx, $fy); Start-Sleep -Milliseconds 250
[G]::mouse_event([G]::LEFTDOWN,0,0,0,[IntPtr]::Zero); Start-Sleep -Milliseconds 80
[G]::mouse_event([G]::LEFTUP,0,0,0,[IntPtr]::Zero)
Start-Sleep -Milliseconds 500

Write-Output "[2b] typing phrase: $Phrase"
[System.Windows.Forms.SendKeys]::SendWait($Phrase)
Start-Sleep -Milliseconds 1200
$r = Snap $h "$ev\$Tag-b-typed.png"

Write-Output "[3] clicking Approve at window-rel ($ApproveX,$ApproveY)"
$sx = $r.Left + $ApproveX; $sy = $r.Top + $ApproveY
[void][G]::SetCursorPos($sx, $sy); Start-Sleep -Milliseconds 250
[G]::mouse_event([G]::LEFTDOWN,0,0,0,[IntPtr]::Zero); Start-Sleep -Milliseconds 90
[G]::mouse_event([G]::LEFTUP,0,0,0,[IntPtr]::Zero)
Start-Sleep -Milliseconds 3000
[void](Snap $h "$ev\$Tag-c-after.png")
Write-Output "done."
