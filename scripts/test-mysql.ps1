$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectDir '.env.mysql-test'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Copy .env.mysql-test.example to .env.mysql-test and fill in your dedicated test database credentials.' }
$allowed = @('MYSQL_TEST_URL','MYSQL_TEST_USERNAME','MYSQL_TEST_PASSWORD')
$original = @{}
foreach ($name in $allowed) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($line in [IO.File]::ReadAllLines($configPath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $parts = $line.Split('=', 2)
        if ($parts.Length -ne 2 -or $parts[0].Trim() -notin $allowed) { throw 'Invalid key in MySQL test configuration.' }
        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, 'Process')
    }
    foreach ($name in $allowed) { if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name" } }
    if ($env:MYSQL_TEST_URL -notmatch '^jdbc:mysql://') { throw 'MYSQL_TEST_URL must be a MySQL JDBC URL.' }
    Push-Location (Join-Path $projectDir 'platform-server')
    try { & '..\mvnw.cmd' '-Dtest=MySqlAcceptanceTest,MySqlAttachmentAcceptanceTest,MySqlRowScopeAcceptanceTest,MySqlUserDeletionAcceptanceTest,MySqlAnalyticsAndLogAcceptanceTest' test -B -ntp; if ($LASTEXITCODE -ne 0) { throw 'MySQL acceptance failed. See the test report; credentials were not printed by this script.' } }
    finally { Pop-Location }
} finally { foreach ($name in $allowed) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') } }
