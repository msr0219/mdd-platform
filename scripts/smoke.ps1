param([string]$BaseUrl = 'http://127.0.0.1:8080', [string]$Username = $env:SMOKE_USERNAME, [string]$Password = $env:SMOKE_PASSWORD, [string]$CsrfToken = $env:SMOKE_CSRF_TOKEN, [string]$Cookie = $env:SMOKE_COOKIE)
$ErrorActionPreference = 'Stop'
$httpSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loggedIn = $false
if ($Cookie) { $httpSession.Cookies.Add([Uri]$BaseUrl, [System.Net.Cookie]::new('MDD_SESSION', $Cookie, '/')) }
if ($Username -and $Password) {
    $loginBody = @{username=$Username;password=$Password} | ConvertTo-Json -Compress
    $account = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -WebSession $httpSession -Body ([Text.Encoding]::UTF8.GetBytes($loginBody))
    $CsrfToken = $account.csrfToken
    $loggedIn = $true
    if ($account.mustChangePassword) {
        Invoke-RestMethod -Uri "$BaseUrl/api/auth/logout" -Method Post -Headers @{'X-CSRF-Token'=$CsrfToken} -WebSession $httpSession | Out-Null
        throw 'Please change the initial password in the app before running smoke checks.'
    }
}
if (-not $CsrfToken) { throw 'Provide SMOKE_USERNAME/SMOKE_PASSWORD or SMOKE_COOKIE/SMOKE_CSRF_TOKEN for an authenticated account.' }
function Invoke-Rpc([string]$Method, [hashtable]$Params = @{}) {
    $body = @{ jsonrpc = '2.0'; id = 1; method = $Method; params = $Params } | ConvertTo-Json -Depth 20 -Compress
    $response = Invoke-RestMethod -Uri "$BaseUrl/rpc" -Method Post -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$CsrfToken} -WebSession $httpSession -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    if ($response.error) { throw ($response.error | ConvertTo-Json -Depth 10 -Compress) }
    return $response.result
}
try {
$health = Invoke-RestMethod -Uri "$BaseUrl/health"
if ($health.status -ne 'UP') { throw 'Health check failed.' }
$status = Invoke-Rpc 'meta.status'
$menus = Invoke-Rpc 'meta.getMenus'
if (-not $menus.menus.Count) { throw 'No menu metadata returned.' }
foreach ($model in $status.models) {
    $definition = Invoke-Rpc 'meta.getModel' @{ code = $model }
    if ($definition.model.code -ne $model) { throw "Invalid definition: $model" }
    $result = Invoke-Rpc "$model.list" @{ page = 1; pageSize = 1 }
    Write-Host "$model : $($result.total) records"
}
Write-Host "Smoke checks passed; metadata version $($status.version)."
} finally {
    if ($loggedIn) { Invoke-RestMethod -Uri "$BaseUrl/api/auth/logout" -Method Post -Headers @{'X-CSRF-Token'=$CsrfToken} -WebSession $httpSession | Out-Null }
}
