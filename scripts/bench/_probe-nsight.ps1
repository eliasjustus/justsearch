$url = 'https://developer.nvidia.com/downloads/assets/tools/secure/nsight-systems/2026_2/NsightSystems-2026.2.1.210-3763964.msi'
try {
  $response = Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing -MaximumRedirection 5 -ErrorAction Stop
  Write-Output ("Status: {0}" -f $response.StatusCode)
  Write-Output ("Content-Type: {0}" -f $response.Headers['Content-Type'])
  Write-Output ("Content-Length: {0}" -f $response.Headers['Content-Length'])
  Write-Output ("Final-URL: {0}" -f $response.BaseResponse.ResponseUri.AbsoluteUri)
} catch {
  Write-Output ("ERR: {0}" -f $_.Exception.Message)
  if ($_.Exception.Response) {
    Write-Output ("Status: {0}" -f [int]$_.Exception.Response.StatusCode)
  }
}
