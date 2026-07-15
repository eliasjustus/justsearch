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
#
# -Phrase is sent via SendKeys (Send-AppKeys), so it must NOT contain SendKeys
# metacharacters ({} + ^ % ~ ()) — see README.md gotcha #3. If the phrase you
# need to type is JSON (or otherwise contains those characters), replace the
# Send-AppKeys call below with Send-AppText, which pastes via the clipboard
# instead of typing.
#
# Now a thin wrapper over JustSearchGui.psm1: both clicks below go through
# Invoke-AppClick, so they fail closed (refuse to click, exit 1) if the
# window ever loses foreground focus between steps — the same fix click.ps1
# received, inherited here for free by sharing the primitive.
param(
  [string]$Phrase = "core.ingest-files",
  [int]$ApproveX = 738,
  [int]$ApproveY = 537,
  [int]$FieldX = 528,
  [int]$FieldY = 462,
  [string]$Tag = "gui"
)
$ev = "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\evidence"
Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

$conn = Connect-App -ProcName JustSearch -FocusDelayMs 900
if (-not $conn) {
  Write-Output "NO WINDOW"
  exit 1
}

Write-Output "[1] dialog as presented:"
[void](Save-AppShot -Handle $conn.Handle -Out "$ev\$Tag-a-dialog.png")

Write-Output "[2a] clicking confirm field at window-rel ($FieldX,$FieldY) to focus it"
if (-not (Invoke-AppClick -Connection $conn -X $FieldX -Y $FieldY -SettleDelayMs 250 -ClickHoldMs 80)) {
  exit 1
}
Start-Sleep -Milliseconds 500

Write-Output "[2b] typing phrase: $Phrase"
Send-AppKeys -Keys $Phrase -DelayMs 1200
[void](Save-AppShot -Handle $conn.Handle -Out "$ev\$Tag-b-typed.png")

Write-Output "[3] clicking Approve at window-rel ($ApproveX,$ApproveY)"
if (-not (Invoke-AppClick -Connection $conn -X $ApproveX -Y $ApproveY -SettleDelayMs 250 -ClickHoldMs 90)) {
  exit 1
}
Start-Sleep -Milliseconds 3000
[void](Save-AppShot -Handle $conn.Handle -Out "$ev\$Tag-c-after.png")
Write-Output "done."
