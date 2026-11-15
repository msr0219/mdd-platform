param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    Push-Location 'platform-web'
    try {
        if (-not $SkipInstall) {
            if (Test-Path 'package-lock.json') { & npm.cmd ci } else { & npm.cmd install }
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    $staticDir = Join-Path $PSScriptRoot 'platform-server\src\main\resources\static'
    New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
    Copy-Item -Path 'platform-web\dist\*' -Destination $staticDir -Recurse -Force
    Push-Location 'platform-server'
    try {
        & '..\mvnw.cmd' clean verify -B -ntp
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or integration tests failed.' }
    } finally { Pop-Location }
    Write-Host 'Build complete. Run .\start.ps1 and visit http://127.0.0.1:8080'
} finally { Pop-Location }
