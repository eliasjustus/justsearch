#Requires -Version 5.1
<#
.SYNOPSIS
  Emit GitHub Actions annotations for PMD violations found in Gradle build reports.

.DESCRIPTION
  Parses all PMD XML reports under modules/*/build/reports/pmd/*.xml and emits
  ::error workflow commands so violations appear as inline annotations on PR diffs.

  Always exits 0 — the Gradle check step already fails on PMD violations; this
  script only adds navigational annotations.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

try {
    $repoRoot = $PSScriptRoot | Split-Path | Split-Path  # scripts/ci -> scripts -> repo root
    $xmlFiles = Get-ChildItem -Path $repoRoot -Recurse -Filter "*.xml" |
        Where-Object { $_.FullName -match '[/\\]build[/\\]reports[/\\]pmd[/\\]' }

    if ($xmlFiles.Count -eq 0) {
        Write-Host "No PMD reports found."
        exit 0
    }

    $pmdNs = "http://pmd.sourceforge.net/report/2.0.0"
    $totalViolations = 0
    $filesWithViolations = 0

    # Normalize workspace for path stripping (CI sets GITHUB_WORKSPACE).
    $workspace = $env:GITHUB_WORKSPACE
    $wsNorm = $null
    if ($workspace) {
        $wsNorm = $workspace.Replace('\', '/').TrimEnd('/')
    }

    foreach ($xmlFile in $xmlFiles) {
        [xml]$xml = Get-Content -LiteralPath $xmlFile.FullName -Raw

        $nsMgr = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
        $nsMgr.AddNamespace("p", $pmdNs)

        $fileNodes = $xml.SelectNodes("//p:file", $nsMgr)
        if ($null -eq $fileNodes -or $fileNodes.Count -eq 0) {
            continue
        }

        foreach ($fileNode in $fileNodes) {
            $absPath = $fileNode.GetAttribute("name")
            $violations = $fileNode.SelectNodes("p:violation", $nsMgr)
            if ($null -eq $violations -or $violations.Count -eq 0) {
                continue
            }

            $filesWithViolations++

            # Convert to repo-relative forward-slash path for GitHub annotations.
            $fpNorm = $absPath.Replace('\', '/')
            if ($wsNorm -and $fpNorm.StartsWith($wsNorm, [System.StringComparison]::OrdinalIgnoreCase)) {
                $relative = $fpNorm.Substring($wsNorm.Length).TrimStart('/')
            } else {
                $relative = $fpNorm
            }

            foreach ($v in $violations) {
                $totalViolations++
                $beginLine = $v.GetAttribute("beginline")
                $endLine   = $v.GetAttribute("endline")
                $rule      = $v.GetAttribute("rule")
                $desc      = ($v.InnerText -replace '\s+', ' ').Trim()

                Write-Host "::error file=$relative,line=$beginLine,endLine=$endLine,title=PMD: $rule::$desc"
            }
        }
    }

    if ($totalViolations -eq 0) {
        Write-Host "PMD: 0 violations found across $($xmlFiles.Count) reports."
    } else {
        $msg = "PMD: $totalViolations violation(s) in $filesWithViolations file(s)."
        if ($totalViolations -gt 10) {
            $msg += " GitHub shows the first 10 as inline annotations; see Gradle output for the full list."
        }
        Write-Host $msg
    }

    exit 0
} catch {
    Write-Host "::warning::post-pmd-annotations.ps1 failed: $_"
    exit 0
}
