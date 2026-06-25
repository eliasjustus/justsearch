# Cross-reference fan control % against CPU temp — reveals whether fans were
# already at max when CPU hit TjMax (= cooler ceiling) or still ramping (= fan curve too soft).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$lhmPath = Join-Path $root "tmp\B1\lhm.csv"

$header = (Get-Content $lhmPath -TotalCount 1) -split ','
function Ix($name) { [array]::IndexOf($header, $name) }

$iCpuT = Ix '12th Gen Intel Core i7-12700K|-|CPU Package|Temperature'
$iCpuP = Ix '12th Gen Intel Core i7-12700K|-|CPU Package|Power'
$iF1c  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #1|Control'
$iF2c  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #2|Control'
$iF4c  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #4|Control'
$iF1r  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #1|Fan'
$iF2r  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #2|Fan'
$iF4r  = Ix 'ASUS ROG STRIX B760-G GAMING WIFI D4|Nuvoton NCT6798D|Fan #4|Fan'

$bins = @{
  "<50"    = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  "50-69"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  "70-79"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  "80-89"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  "90-94"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  "95-99"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
  ">=100"  = @{ count=0; f1c=@(); f2c=@(); f4c=@(); f2r=@() }
}

function ParseD($s) {
  $v = 0.0
  if ([double]::TryParse($s, [ref]$v)) { return $v } else { return $null }
}

$rows = Get-Content $lhmPath | Select-Object -Skip 1
foreach ($row in $rows) {
  $c = $row -split ','
  if ($c.Length -le $iCpuT) { continue }
  $t = ParseD $c[$iCpuT]
  if ($null -eq $t) { continue }

  $bin = switch ($t) {
    { $_ -lt 50 }  { "<50"; break }
    { $_ -lt 70 }  { "50-69"; break }
    { $_ -lt 80 }  { "70-79"; break }
    { $_ -lt 90 }  { "80-89"; break }
    { $_ -lt 95 }  { "90-94"; break }
    { $_ -lt 100 } { "95-99"; break }
    default        { ">=100" }
  }
  $bins[$bin].count += 1
  $v = ParseD $c[$iF1c]; if ($v -ne $null) { $bins[$bin].f1c += $v }
  $v = ParseD $c[$iF2c]; if ($v -ne $null) { $bins[$bin].f2c += $v }
  $v = ParseD $c[$iF4c]; if ($v -ne $null) { $bins[$bin].f4c += $v }
  $v = ParseD $c[$iF2r]; if ($v -ne $null) { $bins[$bin].f2r += $v }
}

Write-Output "Fan control % and Fan #2 RPM binned by CPU Package Temp"
Write-Output ""
Write-Output "Temp bin    n    Fan1%avg  Fan2%avg  Fan4%avg  Fan2RPMavg  Fan2RPMmax"
Write-Output "--------  ----  --------  --------  --------  ----------  ----------"
foreach ($k in "<50","50-69","70-79","80-89","90-94","95-99",">=100") {
  $b = $bins[$k]
  if ($b.count -eq 0) { continue }
  $f1 = if ($b.f1c.Count -gt 0) { ($b.f1c | Measure-Object -Average).Average } else { 0 }
  $f2 = if ($b.f2c.Count -gt 0) { ($b.f2c | Measure-Object -Average).Average } else { 0 }
  $f4 = if ($b.f4c.Count -gt 0) { ($b.f4c | Measure-Object -Average).Average } else { 0 }
  $r  = if ($b.f2r.Count -gt 0) { ($b.f2r | Measure-Object -Average -Maximum) } else { $null }
  $rAvg = if ($r) { $r.Average } else { 0 }
  $rMax = if ($r) { $r.Maximum } else { 0 }
  "{0,-8}  {1,4}  {2,8:F1}  {3,8:F1}  {4,8:F1}  {5,10:F0}  {6,10:F0}" -f $k, $b.count, $f1, $f2, $f4, $rAvg, $rMax
}
