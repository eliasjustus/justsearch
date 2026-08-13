<#
.SYNOPSIS
    Manual-fetch control: fetch one URL the way the product does and print
    status, bytes, elapsed time and SHA-256.

.DESCRIPTION
    The environment-vs-product partition instrument. When Install AI reports a
    package download failure, the decisive experiment is to fetch the exact
    failing URL by hand, at the same moment, with the same client the product
    falls back to -- if it succeeds in under a second, the network is not the
    problem and the finding is about the product. Round 16 (tempdoc 823 section 4)
    had to invent this on the spot: one command settled a wedged 872-byte
    `config.json` permanently (HTTP 200, 872 bytes, 0.41s, SHA-256 matching
    the manifest) and refuted the round's leading root-cause hypothesis. It is
    staged now so the next round reaches for it reflexively instead of
    inventing it.

    Client parity is the point. The product tries BITS first and falls back to
    curl.exe with an exact flag set (DownloadExecutor.java `runCurl`:
    --fail --location --retry 3 --retry-delay 2 --continue-at -), so this
    script uses that same flag set verbatim, adds only non-behavioural
    instrumentation (--silent --show-error --write-out), and separately
    reports the BITS service state -- round 16's BITS failed 100% of the time
    because the service was Stopped/Manual, which no curl result can reveal.

    This is a diagnostic, not a gate: it prints what it saw and exits 0 on a
    successful fetch, non-zero when the fetch failed or a supplied
    -ExpectedSha256 did not match. Nothing else in the harness consumes its
    output.

.PARAMETER Url
    The URL to fetch. Take it verbatim from the failing package's manifest
    entry -- a re-typed or CDN-substituted URL answers a different question.

.PARAMETER ExpectedSha256
    Optional. The manifest's expected digest. When given, the computed digest
    is compared and a mismatch exits non-zero (a fetch that succeeds with the
    WRONG bytes is a different, worse finding than a fetch that fails).

.PARAMETER OutFile
    Optional. Where to write the fetched bytes. Defaults to a fresh file under
    the temp directory, deleted afterwards unless -Keep is passed.

.PARAMETER Keep
    Keep the downloaded file (implied when -OutFile is given).

.PARAMETER TimeoutSec
    curl --max-time value. Default 120.

.EXAMPLE
    .\probe-download.ps1 -Url "https://example.com/models/splade/config.json" -ExpectedSha256 abc123...
#>

param(
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$ExpectedSha256 = "",
    [string]$OutFile = "",
    [switch]$Keep,
    [int]$TimeoutSec = 120
)

$ErrorActionPreference = "Stop"

function Write-Probe {
    param([string]$Message)
    Write-Host "[probe-download] $Message"
}

$keepFile = $Keep.IsPresent
$target = $OutFile
if ($target -eq "") {
    $target = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("probe-download-" + [Guid]::NewGuid().ToString("N") + ".bin")
}
else {
    $keepFile = $true
    if (-not [System.IO.Path]::IsPathRooted($target)) {
        $target = Join-Path -Path (Get-Location).Path -ChildPath $target
    }
}

Write-Probe "URL:    $Url"
Write-Probe "Output: $target"

# BITS state first: the product tries BITS BEFORE curl, so a curl-only probe
# can report a perfectly healthy network while the product's primary transport
# is structurally dead. Round 16's BITS service was Stopped with StartType
# Manual and every one of its 17 download attempts fell back.
try {
    $bits = Get-Service -Name BITS -ErrorAction Stop
    Write-Probe "BITS service: Status=$($bits.Status) StartType=$($bits.StartType) (the product tries BITS first, then curl.exe)"
}
catch {
    Write-Probe "BITS service: could not be queried ($($_.Exception.Message)) -- the product's primary transport may be unavailable"
}

# curl.exe with the product's exact flag set (DownloadExecutor.runCurl) plus
# instrumentation-only flags. --continue-at - is kept for parity: it is what
# makes the product's resume path work, and on a fresh/absent file it simply
# starts at zero.
$curl = "curl.exe"
$writeOut = "%{http_code} %{size_download} %{time_total} %{speed_download} %{url_effective}"
$curlArgs = @(
    "--fail",
    "--location",
    "--retry", "3",
    "--retry-delay", "2",
    "--continue-at", "-",
    "--max-time", "$TimeoutSec",
    "--silent", "--show-error",
    # curl's own stderr -> stdout, NOT PowerShell's `2>&1`. In Windows
    # PowerShell 5.1, redirecting a native command's stderr wraps each line in
    # a NativeCommandError ErrorRecord, and under this script's
    # $ErrorActionPreference = "Stop" that THROWS -- swallowing curl's real
    # exit code (a 404 reported as -1 instead of 22). The exit code is the
    # diagnostic: round 16 distinguished 13x exit 52 (empty reply) from 1x
    # exit 35 (TLS) that way, so losing it would defeat the instrument.
    "--stderr", "-",
    "--write-out", $writeOut,
    "--output", $target,
    $Url
)

Write-Probe "curl.exe $($curlArgs -join ' ')"
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$curlOutput = ""
$curlExit = $null
try {
    $curlOutput = (& $curl @curlArgs) | Out-String
    $curlExit = $LASTEXITCODE
}
catch {
    $curlOutput = $_.Exception.Message
    $curlExit = -1
}
$stopwatch.Stop()
$elapsedSec = [math]::Round($stopwatch.Elapsed.TotalSeconds, 3)

Write-Probe "curl exit code: $curlExit  (0 = success; 22 = HTTP >=400 under --fail; 35 = TLS handshake; 52 = empty reply from server)"
Write-Probe "curl --write-out (http_code size_download time_total speed_download url_effective):"
Write-Probe "  $($curlOutput.Trim())"
Write-Probe "wall-clock elapsed: ${elapsedSec}s"

$bytes = $null
if (Test-Path -LiteralPath $target) {
    $bytes = (Get-Item -LiteralPath $target).Length
    Write-Probe "bytes on disk: $bytes"
}
else {
    Write-Probe "bytes on disk: (no file written)"
}

$exitCode = 0
if ($curlExit -ne 0) {
    Write-Probe "RESULT: FETCH FAILED (curl exit $curlExit). The environment/network/server is a live suspect -- this is NOT yet a product finding."
    $exitCode = 1
}
elseif ($bytes -eq $null -or $bytes -eq 0) {
    Write-Probe "RESULT: FETCH REPORTED SUCCESS BUT WROTE $bytes BYTES -- treat as a failed fetch, not a pass."
    $exitCode = 1
}
else {
    $digest = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Probe "SHA-256: $digest"
    if ($ExpectedSha256 -ne "") {
        $expected = $ExpectedSha256.Trim().ToLowerInvariant()
        if ($digest -eq $expected) {
            Write-Probe "SHA-256 matches -ExpectedSha256."
        }
        else {
            Write-Probe "SHA-256 MISMATCH: expected $expected"
            Write-Probe "RESULT: the fetch succeeded but returned DIFFERENT BYTES than the manifest expects -- a content/mirror finding, not a connectivity one."
            $exitCode = 1
        }
    }
    if ($exitCode -eq 0) {
        Write-Probe ("RESULT: FETCH SUCCEEDED ($bytes bytes in ${elapsedSec}s). If the product reports this same URL as failing at the same " +
            "moment, the network is not the cause -- the finding is about the product's download/bookkeeping path.")
    }
}

if ((-not $keepFile) -and (Test-Path -LiteralPath $target)) {
    Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
    Write-Probe "temp file removed (pass -Keep or -OutFile to retain it)"
}

exit $exitCode
