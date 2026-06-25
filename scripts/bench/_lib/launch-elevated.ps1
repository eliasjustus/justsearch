# _lib/launch-elevated.ps1 - dot-sourceable helpers for bench scripts.
# Provides two functions addressing bug classes hit repeatedly in 390:
#   - Invoke-NativeExe      : clean stdout/stderr split (avoids PS 5.1 NativeCommandError)
#   - Test-ElevationOrExit  : fail fast with clear message if not elevated
#
# Dot-source from a bench script:
#   $root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
#   . (Join-Path $root "scripts\bench\_lib\launch-elevated.ps1")
#
# Rationale (tempdoc 390 Tier 3): every new elevated / cross-shell launcher re-introduces
# Split-Path depth bugs, $_-through-bash expansion, taskkill-through-MSYS, and native-command
# error propagation. These helpers are the extracted common cases.

function Test-ElevationOrExit {
  param([switch]$Warn)
  $isAdmin = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent()
  ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
  if (-not $isAdmin) {
    if ($Warn) {
      Write-Warning "Not elevated; continuing anyway. Some operations may fail."
    } else {
      Write-Error "This script requires elevation. Re-run from an elevated shell."
      exit 1
    }
  }
  return $isAdmin
}

function Invoke-NativeExe {
  # Wraps Start-Process with clean stdout/stderr redirection. Avoids the
  #   `& cmd 2> $err > $out`
  # pattern that trips PS 5.1 NativeCommandError when $ErrorActionPreference = Stop.
  #
  # Returns a PSCustomObject with ExitCode, WallSeconds, StdoutPath, StderrPath.
  param(
    [Parameter(Mandatory)] [string]$FilePath,
    [Parameter(Mandatory)] [string[]]$Arguments,
    [Parameter(Mandatory)] [string]$StdoutPath,
    [Parameter(Mandatory)] [string]$StderrPath,
    [string]$WorkingDirectory = (Get-Location).Path
  )
  $outDir = Split-Path -Parent $StdoutPath
  if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
  $start = Get-Date
  $proc = Start-Process -FilePath $FilePath `
    -ArgumentList $Arguments `
    -WorkingDirectory $WorkingDirectory `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $StdoutPath `
    -RedirectStandardError $StderrPath
  $wall = ((Get-Date) - $start).TotalSeconds
  [PSCustomObject]@{
    ExitCode    = $proc.ExitCode
    WallSeconds = [math]::Round($wall, 1)
    StdoutPath  = $StdoutPath
    StderrPath  = $StderrPath
  }
}
