$nsysDir = 'C:\Program Files\NVIDIA Corporation\Nsight Systems 2026.2.1\target-windows-x64'
$currentPath = [Environment]::GetEnvironmentVariable('PATH', 'Machine')
if ($currentPath -notlike "*$nsysDir*") {
  [Environment]::SetEnvironmentVariable('PATH', "$currentPath;$nsysDir", 'Machine')
  Write-Output 'Added to machine PATH'
} else {
  Write-Output 'Already on machine PATH'
}
& "$nsysDir\nsys.exe" --version
