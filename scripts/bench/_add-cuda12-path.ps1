$cudaDir = 'C:\tools\cuda12-ort'
$currentPath = [Environment]::GetEnvironmentVariable('PATH', 'Machine')
if ($currentPath -notlike "*$cudaDir*") {
  [Environment]::SetEnvironmentVariable('PATH', "$currentPath;$cudaDir", 'Machine')
  Write-Output 'Added to machine PATH'
} else {
  Write-Output 'Already on machine PATH'
}
Get-ChildItem $cudaDir | Select-Object Name, Length | Format-Table -AutoSize
