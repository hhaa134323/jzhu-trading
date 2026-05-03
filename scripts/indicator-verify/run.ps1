[CmdletBinding()]
param(
    [string]$Root = '',
    [string]$MarketDataBaseUrl = 'http://localhost:8182',
    [string]$IndicatorBaseUrl = 'http://localhost:8183',
    [string]$StartDate = '2016-01-01',
    [string]$EndDate = '2024-06-30',
    [string]$OutputDir = '',
    [string]$DockerImage = 'python:3.11-slim'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = if ($PSCommandPath) { $PSCommandPath } elseif ($MyInvocation.MyCommand.Path) { $MyInvocation.MyCommand.Path } else { $null }
$scriptDir = if ($scriptPath) { Split-Path -Parent $scriptPath } else { Get-Location }
$repoRoot = if ([string]::IsNullOrWhiteSpace($Root)) {
    (Resolve-Path (Join-Path $scriptDir '..\..')).Path
} else {
    $Root
}

$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    Join-Path $repoRoot 'logs\indicator-verify'
} else {
    $OutputDir
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

$markets = @(
    @{ market = 'us'; symbol = 'TSLA' },
    @{ market = 'hk'; symbol = '00700' },
    @{ market = 'cn'; symbol = '600519' }
)
$periods = @('daily', 'weekly', 'monthly')

function New-Url {
    param(
        [string]$BaseUrl,
        [string]$Path
    )

    return ($BaseUrl.TrimEnd('/') + $Path)
}

function Invoke-CurlGet {
    param(
        [string]$Url,
        [string]$OutFile
    )

    & curl.exe --silent --show-error --fail-with-body --location $Url --output $OutFile
}

function Invoke-CurlPostJson {
    param(
        [string]$Url,
        [string]$BodyFile,
        [string]$OutFile
    )

    & curl.exe --silent --show-error --fail-with-body --location $Url `
        -H 'Content-Type: application/json' `
        --data-binary "@$BodyFile" `
        --output $OutFile
}

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value
    )

    ($Value | ConvertTo-Json -Depth 32) | Set-Content -Path $Path -Encoding utf8
}

function New-JsonString {
    param([string]$Value)

    return ($Value | ConvertTo-Json -Compress)
}

Write-Host "Writing artifacts to $outputRoot"

foreach ($entry in $markets) {
    $market = $entry.market
    $symbol = $entry.symbol

    $dailyPath = Join-Path $outputRoot "$market`_$symbol`_daily_kline.json"
    $dailyUrl = New-Url -BaseUrl $MarketDataBaseUrl -Path "/api/market-data/kline?symbol=$symbol&market=$market&period=daily&startDate=$StartDate&endDate=$EndDate"
    Write-Host "Fetching daily baseline for $market/$symbol"
    Invoke-CurlGet -Url $dailyUrl -OutFile $dailyPath

    foreach ($period in $periods) {
        $klinePath = Join-Path $outputRoot "$market`_$symbol`_$period`_kline.json"
        if ($period -eq 'daily') {
            $klinePath = $dailyPath
        } else {
            $klineUrl = New-Url -BaseUrl $MarketDataBaseUrl -Path "/api/market-data/kline?symbol=$symbol&market=$market&period=$period&startDate=$StartDate&endDate=$EndDate"
            Write-Host "Fetching $period kline for $market/$symbol"
            Invoke-CurlGet -Url $klineUrl -OutFile $klinePath
        }

        $indicatorRequestPath = Join-Path $outputRoot "$market`_$symbol`_$period`_indicator_request.json"
        $indicatorResponsePath = Join-Path $outputRoot "$market`_$symbol`_$period`_indicator.json"

                $klineJson = Get-Content -Raw -Path $klinePath
                $indicatorRequest = @"
{
    "klines": $klineJson,
    "symbol": $(New-JsonString $symbol),
    "market": $(New-JsonString $market),
    "period": $(New-JsonString $period)
}
"@

                Set-Content -Path $indicatorRequestPath -Value $indicatorRequest -Encoding utf8

        $indicatorUrl = New-Url -BaseUrl $IndicatorBaseUrl -Path '/api/indicators/calculate'
        Write-Host "Fetching indicator response for $market/$symbol/$period"
        Invoke-CurlPostJson -Url $indicatorUrl -BodyFile $indicatorRequestPath -OutFile $indicatorResponsePath
    }
}

$dockerArgs = @(
    'run', '--rm',
    '-v', "${repoRoot}:/workspace",
    '-w', '/workspace',
    $DockerImage,
    'python', 'scripts/indicator-verify/verify_correctness.py',
    '--input-dir', 'logs/indicator-verify',
    '--output-dir', 'logs/indicator-verify',
    '--start-date', $StartDate,
    '--end-date', $EndDate
)

Write-Host "Running correctness check in Docker image $DockerImage"
& docker @dockerArgs

Write-Host "Correctness artifacts written under $outputRoot"