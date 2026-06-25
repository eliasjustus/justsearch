Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-DeterminismBudget {
  param(
    [string]$StdoutSentinel = "JUSTSEARCH_API_PORT=...",
    [int]$LogScrapeAllowed = 1
  )

  return @{
    budget = @{
      "sleep.fixed.count" = 0
      "log.scrape_unstructured.count" = $LogScrapeAllowed
      "assert.screenshot_only.count" = 0
      "wait.unbounded.count" = 0
    }
    usage = @{
      sleep = @{
        backoff = @{ count = 0; total_ms = 0; reasons = @() }
        fixed   = @{ count = 0; total_ms = 0 }
      }
      log = @{
        parse_stdout_sentinel = @{ count = 0; sentinel = $StdoutSentinel }
        scrape_unstructured   = @{ count = 0 }
      }
      assert = @{ screenshot_only = @{ count = 0 } }
      wait   = @{ unbounded = @{ count = 0 } }
    }
    violations = @()
  }
}

function Add-DeterminismViolation {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][string]$Counter,
    [Parameter(Mandatory = $true)][int]$Observed,
    [Parameter(Mandatory = $true)][int]$Allowed,
    [Parameter(Mandatory = $true)][ValidateSet("error","warn")][string]$Severity,
    [Parameter(Mandatory = $true)][string]$Message,
    [Parameter(Mandatory = $false)][object[]]$Followups
  )

  $v = [ordered]@{
    counter  = $Counter
    observed = $Observed
    allowed  = $Allowed
    severity = $Severity
    message  = $Message
  }
  if ($Followups -and $Followups.Count -gt 0) {
    $v.followups = $Followups
  }
  $Det.violations += [pscustomobject]$v
}

function Add-BackoffSleep {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][string]$Reason,
    [Parameter(Mandatory = $true)][int]$Ms
  )

  if ($Ms -le 0) { return }
  $Det.usage.sleep.backoff.count += 1
  $Det.usage.sleep.backoff.total_ms += $Ms

  $found = $null
  foreach ($r in $Det.usage.sleep.backoff.reasons) {
    if ($r.reason -eq $Reason) { $found = $r; break }
  }
  if (-not $found) {
    $found = [pscustomobject]@{ reason = $Reason; count = 0; total_ms = 0 }
    $Det.usage.sleep.backoff.reasons += $found
  }
  $found.count += 1
  $found.total_ms += $Ms

  Start-Sleep -Milliseconds $Ms
}

function Add-FixedSleep {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][int]$Ms
  )

  if ($Ms -le 0) { return }
  $Det.usage.sleep.fixed.count += 1
  $Det.usage.sleep.fixed.total_ms += $Ms
  Start-Sleep -Milliseconds $Ms
}

function Add-StdoutSentinelParse {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [string]$Sentinel
  )
  $Det.usage.log.parse_stdout_sentinel.count += 1
  if (-not [string]::IsNullOrWhiteSpace($Sentinel)) {
    $Det.usage.log.parse_stdout_sentinel.sentinel = $Sentinel
  }
}

function Add-UnstructuredLogScrape {
  param([Parameter(Mandatory = $true)][hashtable]$Det)
  $Det.usage.log.scrape_unstructured.count += 1
}

function Enforce-DeterminismBudget {
  param([Parameter(Mandatory = $true)][hashtable]$Det)

  $hasError = $false
  $budget = $Det.budget
  $usage = $Det.usage

  $sleepFixedAllowed = [int]($budget["sleep.fixed.count"])
  $sleepFixedObserved = [int]($usage.sleep.fixed.count)
  if ($sleepFixedObserved -gt $sleepFixedAllowed) {
    Add-DeterminismViolation -Det $Det -Counter "sleep.fixed.count" -Observed $sleepFixedObserved -Allowed $sleepFixedAllowed -Severity "error" `
      -Message "Fixed sleeps are disallowed; replace with condition-based waits"
    $hasError = $true
  }

  $shotAllowed = [int]($budget["assert.screenshot_only.count"])
  $shotObserved = [int]($usage.assert.screenshot_only.count)
  if ($shotObserved -gt $shotAllowed) {
    Add-DeterminismViolation -Det $Det -Counter "assert.screenshot_only.count" -Observed $shotObserved -Allowed $shotAllowed -Severity "error" `
      -Message "Screenshot-only assertions are disallowed; add an API/state assertion"
    $hasError = $true
  }

  $waitAllowed = [int]($budget["wait.unbounded.count"])
  $waitObserved = [int]($usage.wait.unbounded.count)
  if ($waitObserved -gt $waitAllowed) {
    Add-DeterminismViolation -Det $Det -Counter "wait.unbounded.count" -Observed $waitObserved -Allowed $waitAllowed -Severity "error" `
      -Message "Unbounded waits are disallowed; use bounded backoff with a deadline"
    $hasError = $true
  }

  # Log scrape: tolerated up to budget (warn) but must be visible debt.
  $logAllowed = [int]($budget["log.scrape_unstructured.count"])
  $logObserved = [int]($usage.log.scrape_unstructured.count)
  if ($logObserved -gt 0) {
    $sev = if ($logObserved -gt $logAllowed) { "error" } else { "warn" }
    $followups = @(
      [pscustomobject]@{
        type  = "doc"
        href  = "docs/tempdocs/13/01-determinism-budget-run-metadata.md"
        title = "Determinism Budget: replace log scraping with state/API signals"
      }
    )
    Add-DeterminismViolation -Det $Det -Counter "log.scrape_unstructured.count" -Observed $logObserved -Allowed $logAllowed -Severity $sev `
      -Message "Unstructured log scraping is temporary debt; replace with an API/state signal" -Followups $followups
    if ($sev -eq "error") { $hasError = $true }
  }

  return $hasError
}

Export-ModuleMember -Function `
  New-DeterminismBudget, `
  Add-DeterminismViolation, `
  Add-BackoffSleep, `
  Add-FixedSleep, `
  Add-StdoutSentinelParse, `
  Add-UnstructuredLogScrape, `
  Enforce-DeterminismBudget


