Param()
$ErrorActionPreference = 'Stop'
Write-Host "Node version:"; node -v
Write-Host "Java version:"; java -version
Write-Host "Installing SSOT tools..."
npm --prefix SSOT/tools ci
Write-Host "Validating SSOT..."
npm --prefix SSOT/tools run validate
Write-Host "Repro manifest:" (Resolve-Path SSOT\manifests\repro\repro.v1.json)

