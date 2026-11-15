param([switch]$Build)
$ErrorActionPreference = 'Stop'
if ($Build) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}
$serverDir = Join-Path $PSScriptRoot 'platform-server'
$jarPath = Join-Path $serverDir 'target\platform-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Run .\build.ps1 first, or use .\start.ps1 -Build.'
}
Write-Host 'MDD platform: http://127.0.0.1:8080 (Ctrl+C to stop)'
Push-Location $serverDir
try { & java -jar $jarPath } finally { Pop-Location }
