$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot '.logs\server.pid'
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host 'No background server PID file. For a foreground server press Ctrl+C in its terminal.'
    exit 0
}
$serverProcessId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
if (-not $process) { Write-Host 'Background server is already stopped.'; exit 0 }
$expectedJar = Join-Path $PSScriptRoot 'platform-server\target\platform-server-0.1.0-SNAPSHOT.jar'
if (-not $process.CommandLine -or -not $process.CommandLine.Contains($expectedJar)) {
    throw 'PID no longer belongs to this project server; refusing to stop another process.'
}
Stop-Process -Id $serverProcessId
Write-Host 'Background project server stopped.'
