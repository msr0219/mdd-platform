$MavenArgs = $args
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$mavenPath = if ($mavenCommand) { $mavenCommand.Source } else { $null }
if (-not $mavenPath) {
    $cachedWrapper = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $cachedWrapper) {
        $candidate = Get-ChildItem -LiteralPath $cachedWrapper -Filter mvn.cmd -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { $mavenPath = $candidate.FullName }
    }
}
if (-not $mavenPath) {
    $version = '3.9.9'
    $toolsDir = Join-Path $projectRoot '.tools'
    $mavenPath = Join-Path $toolsDir "apache-maven-$version\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $mavenPath)) {
        New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
        $archivePath = Join-Path $toolsDir "apache-maven-$version-bin.zip"
        $downloadUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip"
        Write-Host "Downloading Apache Maven $version into project .tools directory..."
        Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath -UseBasicParsing
        $expected = (Invoke-WebRequest -Uri "$downloadUrl.sha512" -UseBasicParsing).Content.Trim().Split(' ')[0]
        $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
        if ($actual -ne $expected) { throw 'Maven download SHA512 mismatch.' }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
    }
}
& $mavenPath @MavenArgs
exit $LASTEXITCODE
