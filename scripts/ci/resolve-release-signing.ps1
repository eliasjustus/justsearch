#Requires -Version 5.1
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$GitRef,
  [string]$ProjectVersion = "",
  [string]$Mode = "pfx",
  [switch]$SignInput,
  [string]$PfxBase64 = "",
  [string]$PfxPassword = "",
  [string]$TimestampUrl = "",
  [string]$Thumbprint = "",
  [string]$CommandTemplate = "",
  [switch]$AllowUntrusted,
  [string]$ProviderRemainingSignatures = "",
  [int]$SignatureMaximum = 12
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($SignatureMaximum -lt 1) { throw "SignatureMaximum must be positive." }
$releaseTag = $GitRef -like 'refs/tags/v*'
$requested = $SignInput.IsPresent -or $releaseTag
$normalizedMode = if ([string]::IsNullOrWhiteSpace($Mode)) { "pfx" } else { $Mode.Trim().ToLowerInvariant() }
$configured = switch ($normalizedMode) {
  "pfx" { -not [string]::IsNullOrWhiteSpace($PfxBase64) -and -not [string]::IsNullOrWhiteSpace($PfxPassword) -and -not [string]::IsNullOrWhiteSpace($TimestampUrl) }
  "store" { -not [string]::IsNullOrWhiteSpace($Thumbprint) }
  "command" { -not [string]::IsNullOrWhiteSpace($CommandTemplate) }
  default { throw "Unknown JUSTSEARCH_CODESIGN_MODE '$normalizedMode' (expected pfx | store | command)." }
}

if ($releaseTag -and $AllowUntrusted.IsPresent) {
  throw "JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED is rehearsal-only and cannot be used on a release tag."
}

if ($requested -and $normalizedMode -eq "command" -and $AllowUntrusted.IsPresent) {
  throw "Command signing mode cannot use JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED; vendor signing always requires provider budget verification."
}

if ($releaseTag) {
  $tagVersion = $GitRef.Substring('refs/tags/v'.Length)
  if ([string]::IsNullOrWhiteSpace($ProjectVersion) -or $ProjectVersion.Trim() -cne $tagVersion) {
    throw "Release tag version '$tagVersion' must exactly match gradle.properties version '$ProjectVersion'."
  }
}

if ($requested -and $normalizedMode -eq "command" -and $configured -and $CommandTemplate -notmatch '\{file\}') {
  throw "Command signing mode requires a {file} placeholder before packaging can begin."
}

if ($requested -and -not $configured) {
  throw "Signing was requested for '$GitRef', but mode '$normalizedMode' is missing its complete credential inputs."
}

$remaining = $null
if ($requested -and -not $AllowUntrusted.IsPresent) {
  $parsed = 0
  if (-not [int]::TryParse($ProviderRemainingSignatures, [ref]$parsed) -or $parsed -lt $SignatureMaximum) {
    throw "Production signing requires providerRemainingSignatures >= $SignatureMaximum from the provider portal."
  }
  $remaining = $parsed
}

$result = [ordered]@{
  schemaVersion = 1
  releaseTag = $releaseTag
  signingRequested = $requested
  signingMode = $normalizedMode
  signingConfigured = $configured
  rehearsal = $AllowUntrusted.IsPresent
  packageSwitch = if ($releaseTag) { "Release" } elseif ($requested) { "Sign" } else { "None" }
  signatureMaximum = $SignatureMaximum
  providerRemainingSignatures = $remaining
}
Write-Output ($result | ConvertTo-Json -Compress)
