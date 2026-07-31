# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
Serves a staged, flat updater release set on loopback for a Sandbox-only
in-app update qualification round.

.DESCRIPTION
Uses TcpListener instead of HttpListener so a fresh Windows Sandbox needs no
URL ACL setup. Only GET/HEAD requests for files directly under FeedRoot are
accepted; traversal, subdirectories, non-loopback binding, and directory
listing are deliberately absent.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$FeedRoot,
    [int]$Port = 8765,
    [string]$LogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedRoot = (Resolve-Path -LiteralPath $FeedRoot).Path
if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path -Path $resolvedRoot -ChildPath "server.log"
}

function Write-FeedLog([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
}

function Content-Type([string]$Name) {
    switch ([IO.Path]::GetExtension($Name).ToLowerInvariant()) {
        ".json" { return "application/json" }
        ".sig"  { return "text/plain; charset=utf-8" }
        ".exe"  { return "application/vnd.microsoft.portable-executable" }
        default { return "application/octet-stream" }
    }
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-FeedLog "LISTEN http://127.0.0.1:$Port/ root=$resolvedRoot pid=$PID"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new(
                $stream,
                [Text.Encoding]::ASCII,
                $false,
                1024,
                $true
            )
            $requestLine = $reader.ReadLine()
            while (($header = $reader.ReadLine()) -ne "") {
                if ($null -eq $header) { break }
            }

            $parts = @($requestLine -split " ")
            $method = if ($parts.Count -gt 0) { $parts[0] } else { "" }
            $rawTarget = if ($parts.Count -gt 1) { $parts[1] } else { "" }
            $target = [Uri]::UnescapeDataString(($rawTarget -split "\?")[0]).TrimStart("/")
            $safeName = [IO.Path]::GetFileName($target)
            $allowed = (
                ($method -eq "GET" -or $method -eq "HEAD") -and
                $target -eq $safeName -and
                -not [string]::IsNullOrWhiteSpace($safeName)
            )
            $path = if ($allowed) { Join-Path $resolvedRoot $safeName } else { "" }

            if (-not $allowed -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
                $body = [Text.Encoding]::UTF8.GetBytes("not found`n")
                $head = "HTTP/1.1 404 Not Found`r`nContent-Type: text/plain`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
                $headBytes = [Text.Encoding]::ASCII.GetBytes($head)
                $stream.Write($headBytes, 0, $headBytes.Length)
                if ($method -ne "HEAD") { $stream.Write($body, 0, $body.Length) }
                Write-FeedLog "404 method=$method target=$rawTarget"
                continue
            }

            $bytes = [IO.File]::ReadAllBytes($path)
            $head = "HTTP/1.1 200 OK`r`nContent-Type: $(Content-Type $safeName)`r`nContent-Length: $($bytes.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
            $headBytes = [Text.Encoding]::ASCII.GetBytes($head)
            $stream.Write($headBytes, 0, $headBytes.Length)
            if ($method -ne "HEAD") { $stream.Write($bytes, 0, $bytes.Length) }
            Write-FeedLog "200 method=$method file=$safeName bytes=$($bytes.Length)"
        }
        catch {
            Write-FeedLog "ERROR $($_.Exception.Message)"
        }
        finally {
            $client.Dispose()
        }
    }
}
finally {
    $listener.Stop()
    Write-FeedLog "STOP"
}

