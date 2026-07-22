#Requires -Version 5.1
<#
.SYNOPSIS
  Host-side launcher for the automated silent install/uninstall verification harness
  (tempdoc 760 Phase 2 item 1). Generates a Windows Sandbox .wsb that runs
  sandbox-guest-silent-test.ps1 as its LogonCommand against the stable installer alias that
  scripts/ci/package-installer-win.ps1 already stages into tmp/offline-installer-sandbox/share.

.DESCRIPTION
  This script does NOT itself drive the Sandbox session -- Windows Sandbox requires an
  interactive GUI desktop session to run at all, so this is a launcher + evidence-location
  printer, not an automation-to-completion tool. What it does:

    1. Verifies the Sandbox share (default tmp/offline-installer-sandbox/share) and the stable
       installer alias (default JustSearch-LATEST-setup.exe) exist. If either is missing, it
       fails with an actionable pointer at scripts/ci/package-installer-win.ps1, which stages
       both automatically as part of a normal installer build
       (scripts/ci/package-installer-win.ps1:316-373, "sandbox_stage" phase) -- this script does
       NOT invoke Gradle/Tauri builds itself.
    2. Copies scripts/release/sandbox-guest-silent-test.ps1 into the share so it rides along
       inside the mapped folder.
    3. Generates a .wsb (default tmp/offline-installer-sandbox/silent-install-test.wsb) whose
       <MappedFolders> maps the share read-write and whose <LogonCommand> runs the guest script
       non-interactively.
    4. Unless -GenerateOnly, starts the .wsb via Start-Process (the OS associates .wsb with
       WindowsSandbox.exe). This still requires an interactive desktop session; run with
       -GenerateOnly from a non-GUI context and double-click the generated .wsb manually, or hand
       the .wsb to a GUI session.

  Evidence: sandbox-guest-silent-test.ps1 writes a timestamped
  silent-test-result-<stamp>.json into the SAME mapped share directory (it runs from there), so
  once the sandbox session completes, the result is already sitting on the host filesystem at
  <ShareDir>\silent-test-result-*.json -- no manual copy-out needed.

.PARAMETER InstallerPath
  Optional: use a specific installer exe instead of the staged stable alias. Copied into the
  share under -InstallerFileName so the guest script finds a deterministic filename.

.PARAMETER ShareDir
  Sandbox share directory (relative to repo root unless rooted). Default matches the existing
  package-installer-win.ps1 staging convention: tmp/offline-installer-sandbox/share.

.PARAMETER InstallerFileName
  Stable alias filename package-installer-win.ps1 maintains inside the share.

.PARAMETER WsbPath
  Output path for the generated .wsb (relative to repo root unless rooted).

.PARAMETER SandboxFolderName
  Folder name under the Sandbox guest's Desktop the share is mapped to.

.PARAMETER MemoryMB
  Sandbox RAM allocation. This harness never boots the app or loads models -- 4096 MB is ample
  (contrast the 16384 MB used by scripts/sandbox/sandbox-launch.py's full manual harness, which
  does boot the backend).

.PARAMETER InstallTimeoutSec
  Forwarded to the guest script: hard timeout for the installer process.

.PARAMETER UninstallTimeoutSec
  Forwarded to the guest script: hard timeout for the uninstaller process.

.PARAMETER GenerateOnly
  Generate the .wsb (and stage the guest script) but do not attempt to start Windows Sandbox.
  Use this from any non-interactive/agent context -- Windows Sandbox requires a GUI session.

.EXAMPLE
  # Stage a fresh installer, then generate + start the harness:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\package-installer-win.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\sandbox-silent-install-test.ps1

.EXAMPLE
  # Prepare the harness without starting Sandbox (e.g. from an agent session):
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\sandbox-silent-install-test.ps1 -GenerateOnly
#>
[CmdletBinding()]
param(
  [string]$InstallerPath,

  [string]$ShareDir = "tmp/offline-installer-sandbox/share",

  [string]$InstallerFileName = "JustSearch-LATEST-setup.exe",

  [string]$WsbPath = "tmp/offline-installer-sandbox/silent-install-test.wsb",

  [string]$SandboxFolderName = "SilentInstallTest",

  [int]$MemoryMB = 4096,

  [int]$InstallTimeoutSec = 300,

  [int]$UninstallTimeoutSec = 180,

  [switch]$GenerateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
  Write-Error $Message
  exit 1
}

function Info([string]$Message) {
  Write-Host $Message
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/release -> scripts -> repo root

function Resolve-RepoPath([string]$PathValue) {
  if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
  return (Join-Path -Path $repoRoot -ChildPath $PathValue)
}

$shareDirFull = Resolve-RepoPath $ShareDir
$wsbFull = Resolve-RepoPath $WsbPath
$guestScriptSrc = Join-Path -Path $scriptDir -ChildPath "sandbox-guest-silent-test.ps1"

if (-not (Test-Path -LiteralPath $guestScriptSrc)) {
  Fail "Guest script not found: $guestScriptSrc (expected next to this launcher)."
}

# ---------------------------------------------------------------------------
# 1) Verify share + installer exist. Point at package-installer-win.ps1's staging rather than
#    invoking a build ourselves -- an installer build is a multi-minute Rust/Gradle operation
#    this launcher should never trigger silently.
# ---------------------------------------------------------------------------
$stableAliasFull = Join-Path -Path $shareDirFull -ChildPath $InstallerFileName
$resolvedInstaller = $null

if ($InstallerPath) {
  $overridePath = Resolve-RepoPath $InstallerPath
  if (-not (Test-Path -LiteralPath $overridePath)) {
    Fail "-InstallerPath does not exist: $overridePath"
  }
  $resolvedInstaller = $overridePath
} elseif (Test-Path -LiteralPath $stableAliasFull) {
  $resolvedInstaller = $stableAliasFull
} else {
  Fail (
    "Installer not found. Expected the stable Sandbox alias at:`n  $stableAliasFull`n" +
    "Stage it by building the installer:`n" +
    "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\package-installer-win.ps1`n" +
    "(this stages tmp/offline-installer-sandbox/share automatically -- see" +
    " scripts/ci/package-installer-win.ps1:316-373, the sandbox_stage phase), or pass" +
    " -InstallerPath pointing at an existing *-setup.exe directly."
  )
}

if (-not (Test-Path -LiteralPath $shareDirFull)) {
  New-Item -ItemType Directory -Force -Path $shareDirFull | Out-Null
}

# If the resolved installer isn't already the stable alias in place, copy it in under the
# deterministic filename the guest script looks for.
if (-not [string]::Equals($resolvedInstaller, $stableAliasFull, [System.StringComparison]::OrdinalIgnoreCase)) {
  Copy-Item -LiteralPath $resolvedInstaller -Destination $stableAliasFull -Force
  Info "Copied installer into share: $stableAliasFull"
}

# ---------------------------------------------------------------------------
# 2) Stage the guest script into the share so it rides along inside the mapped folder.
# ---------------------------------------------------------------------------
$guestScriptDest = Join-Path -Path $shareDirFull -ChildPath "sandbox-guest-silent-test.ps1"
Copy-Item -LiteralPath $guestScriptSrc -Destination $guestScriptDest -Force
Info "Staged guest script: $guestScriptDest"

# ---------------------------------------------------------------------------
# 3) Generate the .wsb.
#
# Schema per Microsoft's published sample configuration files (Windows Sandbox sample
# configuration files, learn.microsoft.com/windows/security/application-security/
# application-isolation/windows-sandbox/windows-sandbox-sample-configuration):
#   <Configuration>
#     <VGpu>Disable|Default|Enable</VGpu>
#     <Networking>Disable|Enable</Networking>
#     <MemoryInMB>N</MemoryInMB>
#     <MappedFolders>
#       <MappedFolder>
#         <HostFolder>...</HostFolder>
#         <SandboxFolder>...</SandboxFolder>
#         <ReadOnly>true|false</ReadOnly>
#       </MappedFolder>
#     </MappedFolders>
#     <LogonCommand>
#       <Command>...</Command>
#     </LogonCommand>
#   </Configuration>
# Mirrors the element set + no-XML-declaration convention already used by this repo's own
# generator at scripts/sandbox/sandbox-launch.py:910-935 (Python ElementTree,
# xml_declaration=False) for consistency with a config file already proven to load correctly.
# ---------------------------------------------------------------------------
$shareDirResolved = (Resolve-Path -LiteralPath $shareDirFull).Path
$sandboxFolderPath = "C:\Users\WDAGUtilityAccount\Desktop\$SandboxFolderName"
$guestScriptSandboxPath = "$sandboxFolderPath\sandbox-guest-silent-test.ps1"
$logonCommandText = (
  "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$guestScriptSandboxPath`"" +
  " -InstallTimeoutSec $InstallTimeoutSec -UninstallTimeoutSec $UninstallTimeoutSec"
)

$xmlDoc = New-Object System.Xml.XmlDocument
$configEl = $xmlDoc.CreateElement("Configuration")
$xmlDoc.AppendChild($configEl) | Out-Null

$vgpuEl = $xmlDoc.CreateElement("VGpu"); $vgpuEl.InnerText = "Disable"; $configEl.AppendChild($vgpuEl) | Out-Null
$netEl = $xmlDoc.CreateElement("Networking"); $netEl.InnerText = "Enable"; $configEl.AppendChild($netEl) | Out-Null
$memEl = $xmlDoc.CreateElement("MemoryInMB"); $memEl.InnerText = "$MemoryMB"; $configEl.AppendChild($memEl) | Out-Null

$foldersEl = $xmlDoc.CreateElement("MappedFolders"); $configEl.AppendChild($foldersEl) | Out-Null
$folderEl = $xmlDoc.CreateElement("MappedFolder"); $foldersEl.AppendChild($folderEl) | Out-Null
$hostEl = $xmlDoc.CreateElement("HostFolder"); $hostEl.InnerText = $shareDirResolved; $folderEl.AppendChild($hostEl) | Out-Null
$sandboxFolderEl = $xmlDoc.CreateElement("SandboxFolder"); $sandboxFolderEl.InnerText = $sandboxFolderPath; $folderEl.AppendChild($sandboxFolderEl) | Out-Null
$readOnlyEl = $xmlDoc.CreateElement("ReadOnly"); $readOnlyEl.InnerText = "false"; $folderEl.AppendChild($readOnlyEl) | Out-Null

$logonEl = $xmlDoc.CreateElement("LogonCommand"); $configEl.AppendChild($logonEl) | Out-Null
$commandEl = $xmlDoc.CreateElement("Command"); $commandEl.InnerText = $logonCommandText; $logonEl.AppendChild($commandEl) | Out-Null

$wsbDir = Split-Path -Parent $wsbFull
if (-not (Test-Path -LiteralPath $wsbDir)) {
  New-Item -ItemType Directory -Force -Path $wsbDir | Out-Null
}

$writerSettings = New-Object System.Xml.XmlWriterSettings
$writerSettings.Indent = $true
$writerSettings.OmitXmlDeclaration = $true
$writerSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
$writer = [System.Xml.XmlWriter]::Create($wsbFull, $writerSettings)
try {
  $xmlDoc.Save($writer)
} finally {
  $writer.Close()
}

Info "Generated .wsb: $wsbFull"
Info "  MappedFolder (host):    $shareDirResolved"
Info "  MappedFolder (sandbox): $sandboxFolderPath"
Info "  LogonCommand:           $logonCommandText"
Info "  Installer staged as:    $stableAliasFull"
Info ""
Info "Once the Sandbox session completes (or is closed), results will appear at:"
Info "  $shareDirFull\silent-test-result-<timestamp>.json"
Info ""
Info "Note: a fresh Windows Sandbox boots with Smart App Control enforcing, which can HARD-block"
Info "an unsigned installer with no 'Run anyway' option. The guest script attempts a best-effort"
Info "SAC disable (mirroring scripts/sandbox/sandbox-launch.py's existing mitigation) but this is"
Info "externally reported unreliable -- an immediate installer failure with no files landed is"
Info "SAC, not necessarily an installer defect. See tempdoc 760 for the signing-drop-in tracking."

if ($GenerateOnly.IsPresent) {
  Info ""
  Info "GenerateOnly was set -- not starting Windows Sandbox. Double-click the .wsb above in a"
  Info "GUI session to run the harness, or re-run this script without -GenerateOnly there."
  exit 0
}

Info ""
Info "Starting Windows Sandbox (requires an interactive desktop session)..."
try {
  Start-Process -FilePath $wsbFull
  Info "Launched. Windows Sandbox will run the LogonCommand automatically; watch for the result JSON above."
} catch {
  Write-Warning "Could not start Windows Sandbox automatically: $($_.Exception.Message)"
  Write-Warning "This requires an interactive GUI session (Windows Sandbox feature must be enabled)."
  Write-Warning "Double-click the generated .wsb manually: $wsbFull"
  exit 1
}
