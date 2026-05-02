param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path $projectRoot).Path
$m2Dir = Join-Path $env:USERPROFILE ".m2"

if (-not (Test-Path $m2Dir)) {
    New-Item -ItemType Directory -Path $m2Dir | Out-Null
}

docker run --rm -t `
    -v "${projectRoot}:/workspace" `
    -v "${m2Dir}:/root/.m2" `
    -w /workspace `
    maven:3.9.9-eclipse-temurin-21 mvn @MavenArgs
