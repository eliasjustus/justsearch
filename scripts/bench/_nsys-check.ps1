Get-Process QdstrmImporter,nsys -ErrorAction SilentlyContinue | Select-Object Id, Name, CPU, @{N='WS_GB';E={[math]::Round($_.WS/1GB,1)}} | Format-Table -AutoSize
Write-Output '---mem-free-GB---'
[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB, 1)
Write-Output '---nsys-file---'
Get-ChildItem F:\JustSearch\tmp\nsys | Format-Table -AutoSize Name, Length
