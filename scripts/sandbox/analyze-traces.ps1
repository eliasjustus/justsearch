<#
.SYNOPSIS
  Regex-based traces.ndjson analyzer: discovered http.* attribute keys,
  mutating-span count, and the token-health discriminator (any mutating
  span answered 401).

.DESCRIPTION
  Round 12's FIRST self-check reported "mutating spans: 0; of those 401: 0"
  -- a FALSE CLEAN PASS on the round's single most important discriminator
  (R11-F2 / check_token_health.py's sub-5ms-401 fingerprint) -- for two
  reasons:

    1. The HTTP attribute container in each span is `attrs`, NOT
       `attributes`. A script that reads `.attributes.http.method` silently
       gets $null on every line and "finds" zero mutating spans.
    2. Span `attrs` embed document excerpts containing CRLFs, so a "line" of
       the NDJSON is often not a complete JSON document -- `Get-Content |
       ConvertFrom-Json` throws on many lines (round 12 saw dozens of
       "Unterminated string" / "Expecting value" errors) and a caller that
       does not fail loud on that can end up silently skipping every span
       that happened to parse-fail, including the real ones.

  This script never assembles or parses a "line" as a complete JSON object,
  so it cannot hit either trap: it regexes directly for the field
  SUBSTRINGS it needs ("http.method":"...", "http.status_code":"...", ...)
  wherever Select-String's line-splitting happens to put them. http.method /
  http.status_code / http.target sit together in a short, non-excerpt
  attrs entry, so a CRLF embedded elsewhere in the same object (inside an
  unrelated excerpt attribute) does not separate them from each other in
  practice -- this is the exact method the round used to catch its own false
  negative (sandbox-CLAUDE.md's "Coverage & evidence" section), promoted
  here to a staged, reusable instrument so the next round does not have to
  re-derive it live.

.PARAMETER TracesPath
  Path to the round's traces.ndjson (default: the standard telemetry
  location under %APPDATA%).

.EXAMPLE
  .\analyze-traces.ps1
  .\analyze-traces.ps1 -TracesPath ".\evidence\traces.ndjson"

.NOTES
  Provenance: tempdoc 806 W3 item 2, round-12 session retrospective A2 /
  ranked-fix #3. Read-only; makes no network calls; never mutates the
  traces file. Not a pass/fail gate -- it prints the discriminator for a
  human (or the round) to read, the same numbers
  scripts/sandbox/check_token_health.py asserts on host-side at finalize.
#>
param(
  [string]$TracesPath = "$env:APPDATA\io.justsearch.shell\telemetry\traces.ndjson"
)

if (-not (Test-Path -LiteralPath $TracesPath)) {
  Write-Error "analyze-traces.ps1: traces file not found at '$TracesPath'"
  exit 1
}

Write-Host "traces file: $TracesPath"
Write-Host ""

# --- 1. Discovered http.* attribute keys ------------------------------------
# Confirms the real field names BEFORE trusting any count below -- this is
# the step round 12 skipped the first time and had to re-derive after
# getting a false "0 mutating spans" result.
Write-Host "=== http.* attribute keys (name: occurrence count) ==="
$keyMatches = Select-String -LiteralPath $TracesPath -Pattern '"(http\.[a-zA-Z0-9_.]+)":' -AllMatches
$keyCounts = @{}
foreach ($m in $keyMatches) {
  foreach ($g in $m.Matches) {
    $k = $g.Groups[1].Value
    if ($keyCounts.ContainsKey($k)) { $keyCounts[$k]++ } else { $keyCounts[$k] = 1 }
  }
}
if ($keyCounts.Count -eq 0) {
  Write-Host "  (none found -- if this is unexpected, confirm the field container really is 'attrs', not 'attributes')"
} else {
  $keyCounts.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
    "{0,8}  {1}" -f $_.Value, $_.Key
  }
}
Write-Host ""

# --- 2. Mutating spans (POST/PUT/DELETE) ------------------------------------
$mutatingLines = Select-String -LiteralPath $TracesPath -Pattern '"http\.method":"(POST|PUT|DELETE)"'
Write-Host "=== Mutating spans (POST/PUT/DELETE) ==="
Write-Host "mutating spans: $($mutatingLines.Count)"
Write-Host ""

# --- 3. Mutating spans answered 401 (token-health discriminator) -----------
# Round 11's blocker fingerprint (tempdoc 805 Part G.4, mechanized host-side
# in check_token_health.py): a POST/PUT/DELETE span rejected 401 -- a
# missing/stale session token reaching the webview. A single allowlisted
# no-token control probe on /mcp is expected; anything else is the defect.
Write-Host "=== Mutating spans with a 401 response (token-health discriminator) ==="
$mutating401 = $mutatingLines | Where-Object { $_.Line -match '"http\.status_code":"?401"?' }
Write-Host "mutating 401 count: $($mutating401.Count)"
foreach ($m in $mutating401) {
  $line = $m.Line
  $method = "?"; $target = "?"; $dur = "?"; $start = "?"
  if ($line -match '"http\.method":"(POST|PUT|DELETE)"') { $method = $Matches[1] }
  if ($line -match '"http\.target":"([^"]*)"') {
    $target = $Matches[1]
  } elseif ($line -match '"http\.route":"([^"]*)"') {
    $target = $Matches[1]
  }
  if ($line -match '"duration_ms":([0-9.]+)') { $dur = $Matches[1] }
  if ($line -match '"start":"([^"]*)"') { $start = $Matches[1] }
  "$start  $method $target  status=401  dur=${dur}ms"
}
if ($mutating401.Count -eq 0) {
  Write-Host "  (none -- clean on the token-health discriminator)"
}
