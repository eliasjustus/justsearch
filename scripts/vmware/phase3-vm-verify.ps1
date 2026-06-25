# DEPRECATED: prefer purpose-named entrypoint:
#   scripts/vmware/offline-installer-vmware-verify.ps1

#Requires -Version 5.1
[CmdletBinding()]
param(
  [string]$OutDir,
  [int]$PortTimeoutSec = 60,
  [int]$HttpTimeoutSec = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Warning "DEPRECATED: use scripts/vmware/offline-installer-vmware-verify.ps1 (this script remains as a compatibility wrapper)."

$target = Join-Path -Path $PSScriptRoot -ChildPath "offline-installer-vmware-verify.ps1"
& $target @PSBoundParameters


