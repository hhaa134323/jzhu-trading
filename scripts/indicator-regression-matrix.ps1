[CmdletBinding()]
param(
    [string]$Root = '',
    [string]$MarketDataBaseUrl = 'http://localhost:8182',
    [string]$IndicatorBaseUrl = 'http://localhost:8183',
    [string]$StartDate = '2024-01-01',
    [string]$EndDate = '2024-06-30',
    [string]$OutputDir = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not ('System.Net.Http.HttpClient' -as [type])) {
    Add-Type -AssemblyName System.Net.Http
}

$scriptPath = if ($PSCommandPath) { $PSCommandPath } elseif ($MyInvocation.MyCommand.Path) { $MyInvocation.MyCommand.Path } else { $null }
$scriptDir = if ($scriptPath) { Split-Path -Parent $scriptPath } else { Get-Location }
$resolvedRoot = if ([string]::IsNullOrWhiteSpace($Root)) {
    (Resolve-Path (Join-Path $scriptDir '..')).Path
} else {
    $Root
}

function Join-Url {
    param(
        [string]$BaseUrl,
        [string]$Path
    )

    return ($BaseUrl.TrimEnd('/') + $Path)
}

function New-HttpClient {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $client.DefaultRequestHeaders.Accept.Clear()
    $client.DefaultRequestHeaders.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('application/json'))
    return $client
}

function Invoke-JsonRequest {
    param(
        [System.Net.Http.HttpClient]$Client,
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    $response = $null
    $contentText = ''
    $json = $null
    $statusCode = 0
    $reason = ''

    try {
        if ($Method -eq 'GET') {
            $response = $Client.GetAsync($Uri).GetAwaiter().GetResult()
        } else {
            $bodyJson = ($Body | ConvertTo-Json -Depth 32 -Compress)
            $content = [System.Net.Http.StringContent]::new($bodyJson, [System.Text.Encoding]::UTF8, 'application/json')
            $response = $Client.PostAsync($Uri, $content).GetAwaiter().GetResult()
        }

        $statusCode = [int]$response.StatusCode
        $reason = $response.ReasonPhrase
        $contentText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not [string]::IsNullOrWhiteSpace($contentText)) {
            try {
                $json = $contentText | ConvertFrom-Json
            } catch {
                $json = $null
            }
        }

        return [pscustomobject]@{
            Ok         = $response.IsSuccessStatusCode
            StatusCode = $statusCode
            Reason     = $reason
            Body       = $contentText
            Json       = $json
        }
    } catch {
        $message = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            $reason = [string]$_.Exception.Response.StatusDescription
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($stream) {
                    $reader = [System.IO.StreamReader]::new($stream)
                    $contentText = $reader.ReadToEnd()
                    $reader.Dispose()
                }
            } catch {
                $contentText = $message
            }
        } else {
            $contentText = $message
        }

        if (-not [string]::IsNullOrWhiteSpace($contentText)) {
            try {
                $json = $contentText | ConvertFrom-Json
            } catch {
                $json = $null
            }
        }

        return [pscustomobject]@{
            Ok         = $false
            StatusCode = $statusCode
            Reason     = $reason
            Body       = $contentText
            Json       = $json
        }
    }
}

function Wait-ForEndpoint {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = Invoke-JsonRequest -Client $Client -Method GET -Uri $Uri
        if ($result.StatusCode -gt 0) {
            return $true
        }
        Start-Sleep -Seconds 2
    }

    return $false
}

function Get-ArrayValue {
    param([object]$Value)

    if ($null -eq $Value) {
        return @()
    }

    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        return @($Value)
    }

    return @($Value)
}

function Test-FiniteNumber {
    param([object]$Value)

    if ($null -eq $Value) {
        return $false
    }

    try {
        $number = [double]$Value
        return -not ([double]::IsNaN($number) -or [double]::IsInfinity($number))
    } catch {
        return $false
    }
}

function Count-NonNullNumbers {
    param([object[]]$Values)

    $count = 0
    foreach ($value in $Values) {
        if (Test-FiniteNumber $value) {
            $count++
        }
    }

    return $count
}

function Test-IndicatorBlock {
    param(
        [string]$IndicatorName,
        [object]$Block,
        [int]$KlineCount
    )

    $issues = New-Object System.Collections.Generic.List[string]

    if ($null -eq $Block) {
        $issues.Add('missing block')
        return [pscustomobject]@{ Passed = $false; Issues = $issues; Metrics = @{} }
    }

    switch ($IndicatorName) {
        'macd' {
            $dif = Get-ArrayValue $Block.difList
            $dea = Get-ArrayValue $Block.deaList
            $macd = Get-ArrayValue $Block.macdList

            if ($dif.Count -ne $KlineCount) { $issues.Add("difList length $($dif.Count) != kline count $KlineCount") }
            if ($dea.Count -ne $KlineCount) { $issues.Add("deaList length $($dea.Count) != kline count $KlineCount") }
            if ($macd.Count -ne $KlineCount) { $issues.Add("macdList length $($macd.Count) != kline count $KlineCount") }

            $metrics = @{
                difNonNull  = Count-NonNullNumbers $dif
                deaNonNull  = Count-NonNullNumbers $dea
                macdNonNull = Count-NonNullNumbers $macd
            }

            foreach ($value in @($dif + $dea + $macd)) {
                if ($null -ne $value -and -not (Test-FiniteNumber $value)) {
                    $issues.Add('MACD contains non-finite numeric values')
                    break
                }
            }

            if ($metrics.difNonNull -eq 0 -or $metrics.deaNonNull -eq 0 -or $metrics.macdNonNull -eq 0) {
                if ($KlineCount -ge 1) {
                    $issues.Add('MACD should produce numeric values from the first row')
                }
            }

            return [pscustomobject]@{ Passed = ($issues.Count -eq 0); Issues = $issues; Metrics = $metrics }
        }

        'ma' {
            $series = @{
                ma5  = Get-ArrayValue $Block.ma5List
                ma10 = Get-ArrayValue $Block.ma10List
                ma20 = Get-ArrayValue $Block.ma20List
                ma30 = Get-ArrayValue $Block.ma30List
                ma60 = Get-ArrayValue $Block.ma60List
            }

            foreach ($key in $series.Keys) {
                if ($series[$key].Count -ne $KlineCount) {
                    $issues.Add("$key length $($series[$key].Count) != kline count $KlineCount")
                }
            }

            $metrics = @{}
            foreach ($key in $series.Keys) {
                $metrics[$key + 'NonNull'] = Count-NonNullNumbers $series[$key]
            }

            foreach ($entry in $series.GetEnumerator()) {
                foreach ($value in $entry.Value) {
                    if ($null -ne $value -and -not (Test-FiniteNumber $value)) {
                        $issues.Add("$($entry.Key) contains non-finite numeric values")
                        break
                    }
                }
            }

            return [pscustomobject]@{ Passed = ($issues.Count -eq 0); Issues = $issues; Metrics = $metrics }
        }

        'boll' {
            $upper = Get-ArrayValue $Block.upperList
            $middle = Get-ArrayValue $Block.middleList
            $lower = Get-ArrayValue $Block.lowerList

            if ($upper.Count -ne $KlineCount) { $issues.Add("upperList length $($upper.Count) != kline count $KlineCount") }
            if ($middle.Count -ne $KlineCount) { $issues.Add("middleList length $($middle.Count) != kline count $KlineCount") }
            if ($lower.Count -ne $KlineCount) { $issues.Add("lowerList length $($lower.Count) != kline count $KlineCount") }

            $metrics = @{
                upperNonNull  = Count-NonNullNumbers $upper
                middleNonNull = Count-NonNullNumbers $middle
                lowerNonNull  = Count-NonNullNumbers $lower
            }

            foreach ($value in @($upper + $middle + $lower)) {
                if ($null -ne $value -and -not (Test-FiniteNumber $value)) {
                    $issues.Add('BOLL contains non-finite numeric values')
                    break
                }
            }

            return [pscustomobject]@{ Passed = ($issues.Count -eq 0); Issues = $issues; Metrics = $metrics }
        }

        'rsi' {
            $series = @{
                rsi6  = Get-ArrayValue $Block.rsi6List
                rsi12 = Get-ArrayValue $Block.rsi12List
                rsi24 = Get-ArrayValue $Block.rsi24List
            }

            foreach ($key in $series.Keys) {
                if ($series[$key].Count -ne $KlineCount) {
                    $issues.Add("$key length $($series[$key].Count) != kline count $KlineCount")
                }
            }

            $metrics = @{}
            foreach ($key in $series.Keys) {
                $metrics[$key + 'NonNull'] = Count-NonNullNumbers $series[$key]
            }

            foreach ($entry in $series.GetEnumerator()) {
                foreach ($value in $entry.Value) {
                    if ($null -ne $value) {
                        if (-not (Test-FiniteNumber $value)) {
                            $issues.Add("$($entry.Key) contains non-finite numeric values")
                            break
                        }
                        $numeric = [double]$value
                        if ($numeric -lt 0 -or $numeric -gt 100) {
                            $issues.Add("$($entry.Key) value $numeric out of 0-100 range")
                            break
                        }
                    }
                }
            }

            return [pscustomobject]@{ Passed = ($issues.Count -eq 0); Issues = $issues; Metrics = $metrics }
        }
    }
}

function New-ResultRow {
    param(
        [string]$Market,
        [string]$Period,
        [string]$Indicator,
        [string]$Symbol,
        [string]$Status,
        [string]$Detail,
        [int]$KlineCount = 0
    )

    return [pscustomobject]@{
        market     = $Market
        period     = $Period
        indicator  = $Indicator
        symbol     = $Symbol
        klineCount = $KlineCount
        status     = $Status
        detail     = $Detail
    }
}

$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    Join-Path $resolvedRoot 'logs\indicator-matrix'
} else {
    $OutputDir
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

$client = New-HttpClient
$marketHealthUrl = Join-Url $MarketDataBaseUrl '/api/market-data/kline?symbol=TSLA&market=us&period=daily&startDate=2024-01-01&endDate=2024-01-02'
$indicatorHealthUrl = Join-Url $IndicatorBaseUrl '/api/indicators/calculate'

Write-Host 'Waiting for market-data-service...'
if (-not (Wait-ForEndpoint -Client $client -Uri $marketHealthUrl -TimeoutSeconds 120)) {
    throw "market-data-service is not reachable at $MarketDataBaseUrl"
}

Write-Host 'Waiting for indicator-service...'
$indicatorProbe = Invoke-JsonRequest -Client $client -Method POST -Uri $indicatorHealthUrl -Body @{ klines = @(); symbol = 'TSLA'; market = 'us'; period = 'daily' }
if ($indicatorProbe.StatusCode -eq 0) {
    throw "indicator-service is not reachable at $IndicatorBaseUrl"
}

$marketCandidates = @(
    @{ market = 'us'; symbols = @('TSLA', 'AAPL') },
    @{ market = 'hk'; symbols = @('00700', '00005') },
    @{ market = 'cn'; symbols = @('600519', '000001') }
)
$periods = @('daily', 'weekly', 'monthly')

$selectedSymbols = @{}
$prewarmRows = New-Object System.Collections.Generic.List[object]
foreach ($entry in $marketCandidates) {
    $market = $entry['market']
    $picked = $null
    foreach ($symbol in $entry['symbols']) {
        $uri = Join-Url $MarketDataBaseUrl "/api/market-data/kline?symbol=$symbol&market=$market&period=daily&startDate=$StartDate&endDate=$EndDate"
        $result = Invoke-JsonRequest -Client $client -Method GET -Uri $uri
        $count = if ($result.Ok -and $result.Json) { @($result.Json).Count } else { 0 }
        if ($result.Ok -and $count -gt 0) {
            $picked = [pscustomobject]@{ symbol = $symbol; count = $count }
            $prewarmRows.Add([pscustomobject]@{
                market     = $market
                symbol     = $symbol
                status     = 'pass'
                klineCount = $count
                detail     = 'daily prewarm succeeded'
            })
            break
        }

        $prewarmRows.Add([pscustomobject]@{
            market     = $market
            symbol     = $symbol
            status     = 'fail'
            klineCount = $count
            detail     = if ($result.Body) { $result.Body } else { "HTTP $($result.StatusCode) $($result.Reason)" }
        })
    }

    if ($null -eq $picked) {
        throw "No usable daily symbol found for market '$market'"
    }

    $selectedSymbols[$market] = $picked.symbol
}

$matrixRows = New-Object System.Collections.Generic.List[object]
$requestSnapshots = New-Object System.Collections.Generic.List[object]

foreach ($marketEntry in $marketCandidates) {
    $market = $marketEntry['market']
    $symbol = $selectedSymbols[$market]

    foreach ($period in $periods) {
        $klineUri = Join-Url $MarketDataBaseUrl "/api/market-data/kline?symbol=$symbol&market=$market&period=$period&startDate=$StartDate&endDate=$EndDate"
        $klineResult = Invoke-JsonRequest -Client $client -Method GET -Uri $klineUri
        $klines = if ($klineResult.Ok -and $klineResult.Json) { @($klineResult.Json) } else { @() }
        $klineCount = $klines.Count

        $snapshot = [ordered]@{
            market   = $market
            period   = $period
            symbol   = $symbol
            request  = $null
            response = $null
        }

        if (-not $klineResult.Ok) {
            $message = if ($klineResult.Body) { $klineResult.Body } else { "HTTP $($klineResult.StatusCode) $($klineResult.Reason)" }
            foreach ($indicator in @('macd', 'ma', 'boll', 'rsi')) {
                $matrixRows.Add((New-ResultRow -Market $market -Period $period -Indicator $indicator -Symbol $symbol -Status 'fail' -Detail "kline fetch failed: $message" -KlineCount 0))
            }
            $snapshot.request = @{ market = $market; period = $period; symbol = $symbol; startDate = $StartDate; endDate = $EndDate }
            $snapshot.response = @{ statusCode = $klineResult.StatusCode; body = $klineResult.Body }
            $requestSnapshots.Add([pscustomobject]$snapshot)
            continue
        }

        $indicatorRequest = @{
            klines = $klines
            symbol = $symbol
            market = $market
            period = $period
        }
        $indicatorResult = Invoke-JsonRequest -Client $client -Method POST -Uri $indicatorHealthUrl -Body $indicatorRequest
        $snapshot.request = $indicatorRequest
        $snapshot.response = @{ statusCode = $indicatorResult.StatusCode; body = $indicatorResult.Body }
        $requestSnapshots.Add([pscustomobject]$snapshot)

        if (-not $indicatorResult.Ok) {
            $detail = if ($indicatorResult.Body) { $indicatorResult.Body } else { "HTTP $($indicatorResult.StatusCode) $($indicatorResult.Reason)" }
            foreach ($indicator in @('macd', 'ma', 'boll', 'rsi')) {
                $matrixRows.Add((New-ResultRow -Market $market -Period $period -Indicator $indicator -Symbol $symbol -Status 'fail' -Detail "indicator request failed: $detail" -KlineCount $klineCount))
            }
            continue
        }

        $responseJson = $indicatorResult.Json
        $checks = @{
            macd = Test-IndicatorBlock -IndicatorName 'macd' -Block $responseJson.macd -KlineCount $klineCount
            ma   = Test-IndicatorBlock -IndicatorName 'ma' -Block $responseJson.ma -KlineCount $klineCount
            boll = Test-IndicatorBlock -IndicatorName 'boll' -Block $responseJson.boll -KlineCount $klineCount
            rsi  = Test-IndicatorBlock -IndicatorName 'rsi' -Block $responseJson.rsi -KlineCount $klineCount
        }

        foreach ($indicatorName in @('macd', 'ma', 'boll', 'rsi')) {
            $check = $checks[$indicatorName]
            $detail = if ($check.Passed) { 'ok' } else { ($check.Issues -join '; ') }
            $matrixRows.Add((New-ResultRow -Market $market -Period $period -Indicator $indicatorName -Symbol $symbol -Status ($(if ($check.Passed) { 'pass' } else { 'fail' })) -Detail $detail -KlineCount $klineCount))
        }
    }
}

$boundaryRows = New-Object System.Collections.Generic.List[object]

# Short range: verify the response stays stable when the lookback windows are not yet filled.
$shortSymbol = $selectedSymbols['us']
$shortKlinesUri = Join-Url $MarketDataBaseUrl "/api/market-data/kline?symbol=$shortSymbol&market=us&period=daily&startDate=2024-01-01&endDate=2024-01-05"
$shortKlinesResult = Invoke-JsonRequest -Client $client -Method GET -Uri $shortKlinesUri
$shortKlines = if ($shortKlinesResult.Ok -and $shortKlinesResult.Json) { @($shortKlinesResult.Json) } else { @() }
$shortIndicatorResult = Invoke-JsonRequest -Client $client -Method POST -Uri $indicatorHealthUrl -Body @{
    klines = $shortKlines
    symbol = $shortSymbol
    market = 'us'
    period = 'daily'
}
if ($shortIndicatorResult.Ok) {
    $shortChecks = @{
        macd = Test-IndicatorBlock -IndicatorName 'macd' -Block $shortIndicatorResult.Json.macd -KlineCount $shortKlines.Count
        ma   = Test-IndicatorBlock -IndicatorName 'ma' -Block $shortIndicatorResult.Json.ma -KlineCount $shortKlines.Count
        boll = Test-IndicatorBlock -IndicatorName 'boll' -Block $shortIndicatorResult.Json.boll -KlineCount $shortKlines.Count
        rsi  = Test-IndicatorBlock -IndicatorName 'rsi' -Block $shortIndicatorResult.Json.rsi -KlineCount $shortKlines.Count
    }
    foreach ($indicatorName in @('macd', 'ma', 'boll', 'rsi')) {
        $check = $shortChecks[$indicatorName]
        $boundaryRows.Add([pscustomobject]@{
            case      = 'short-range-5d'
            market    = 'us'
            period    = 'daily'
            indicator = $indicatorName
            status    = $(if ($check.Passed) { 'pass' } else { 'fail' })
            detail    = if ($check.Passed) { 'ok' } else { ($check.Issues -join '; ') }
        })
    }
} else {
    foreach ($indicatorName in @('macd', 'ma', 'boll', 'rsi')) {
        $boundaryRows.Add([pscustomobject]@{
            case      = 'short-range-5d'
            market    = 'us'
            period    = 'daily'
            indicator = $indicatorName
            status    = 'fail'
            detail    = if ($shortIndicatorResult.Body) { $shortIndicatorResult.Body } else { "HTTP $($shortIndicatorResult.StatusCode) $($shortIndicatorResult.Reason)" }
        })
    }
}

function Test-ExpectedBadRequest {
    param(
        [string]$CaseName,
        [string]$Market,
        [string]$Period,
        [string]$Indicator,
        [object]$RequestBody,
        [string]$ExpectedPattern
    )

    $result = Invoke-JsonRequest -Client $client -Method POST -Uri $indicatorHealthUrl -Body $RequestBody
    $detail = if ($result.Body) { $result.Body } else { "HTTP $($result.StatusCode) $($result.Reason)" }
    $passed = ($result.StatusCode -ge 400 -and $result.StatusCode -lt 500 -and ($detail -match $ExpectedPattern))
    return [pscustomobject]@{
        case   = $CaseName
        market = $Market
        period = $Period
        indicator = $Indicator
        status = $(if ($passed) { 'pass' } else { 'fail' })
        http   = $result.StatusCode
        detail = $detail
    }
}

$validDaily = Invoke-JsonRequest -Client $client -Method GET -Uri (Join-Url $MarketDataBaseUrl "/api/market-data/kline?symbol=$shortSymbol&market=us&period=daily&startDate=$StartDate&endDate=$EndDate")
$validDailyKlines = if ($validDaily.Ok -and $validDaily.Json) { @($validDaily.Json) } else { @() }

$boundaryRows.Add((Test-ExpectedBadRequest -CaseName 'invalid-period-hourly' -Market 'us' -Period 'hourly' -Indicator 'all' -RequestBody @{ klines = $validDailyKlines; symbol = $shortSymbol; market = 'us'; period = 'hourly' } -ExpectedPattern 'Unsupported|period|hourly'))
$boundaryRows.Add((Test-ExpectedBadRequest -CaseName 'invalid-market' -Market 'moon' -Period 'daily' -Indicator 'all' -RequestBody @{ klines = $validDailyKlines; symbol = $shortSymbol; market = 'moon'; period = 'daily' } -ExpectedPattern 'market|supported|invalid'))
$boundaryRows.Add((Test-ExpectedBadRequest -CaseName 'invalid-symbol' -Market 'us' -Period 'daily' -Indicator 'all' -RequestBody @{ klines = $validDailyKlines; symbol = '@@@'; market = 'us'; period = 'daily' } -ExpectedPattern 'symbol|supported|invalid'))

$report = New-Object System.Text.StringBuilder
[void]$report.AppendLine('# Indicator Regression Matrix')
[void]$report.AppendLine('')
[void]$report.AppendLine("Generated: $(Get-Date -Format o)")
[void]$report.AppendLine('')
[void]$report.AppendLine('## 1) Interface inventory')
[void]$report.AppendLine('')
[void]$report.AppendLine('- Main indicator API: `POST /api/indicators/calculate`')
[void]$report.AppendLine('- Request body: `{ klines: KlineResponse[], symbol: string, market: string, period: string }`')
[void]$report.AppendLine('- Response body: `{ macd: { difList, deaList, macdList }, ma: { ma5List, ma10List, ma20List, ma30List, ma60List }, rsi: { rsi6List, rsi12List, rsi24List }, boll: { upperList, middleList, lowerList } }`')
[void]$report.AppendLine('- Prerequisite market-data API: `GET /api/market-data/kline?symbol=...&market=...&period=...&startDate=...&endDate=...`')
[void]$report.AppendLine('- Important mismatch: this indicator endpoint does not return a standalone volume series; volume is present in the kline prerequisite payload, not in the indicator response.')
[void]$report.AppendLine('')
[void]$report.AppendLine('## 2) Market prewarm')
[void]$report.AppendLine('')
[void]$report.AppendLine('| market | selected symbol | daily prewarm | kline count | detail |')
[void]$report.AppendLine('| --- | --- | --- | ---: | --- |')
foreach ($row in $prewarmRows) {
    [void]$report.AppendLine("| $($row.market) | $($row.symbol) | $($row.status) | $($row.klineCount) | $($row.detail -replace '\|', '\\|') |")
}
[void]$report.AppendLine('')
[void]$report.AppendLine('## 3) Regression matrix')
[void]$report.AppendLine('')
[void]$report.AppendLine('| market | period | indicator | symbol | kline count | status | detail |')
[void]$report.AppendLine('| --- | --- | --- | --- | ---: | --- | --- |')
foreach ($row in $matrixRows) {
    [void]$report.AppendLine("| $($row.market) | $($row.period) | $($row.indicator) | $($row.symbol) | $($row.klineCount) | $($row.status) | $($row.detail -replace '\|', '\\|') |")
}
[void]$report.AppendLine('')
[void]$report.AppendLine('## 4) Boundary cases')
[void]$report.AppendLine('')
[void]$report.AppendLine('| case | market | period | indicator | status | detail |')
[void]$report.AppendLine('| --- | --- | --- | --- | --- | --- |')
foreach ($row in $boundaryRows) {
    [void]$report.AppendLine("| $($row.case) | $($row.market) | $($row.period) | $($row.indicator) | $($row.status) | $($row.detail -replace '\|', '\\|') |")
}
[void]$report.AppendLine('')
[void]$report.AppendLine('## 5) Failure details')
[void]$report.AppendLine('')
$failures = @($prewarmRows | Where-Object { $_.status -eq 'fail' }) + @($matrixRows | Where-Object { $_.status -eq 'fail' }) + @($boundaryRows | Where-Object { $_.status -eq 'fail' })
if ($failures.Count -eq 0) {
    [void]$report.AppendLine('No failures detected.')
} else {
    foreach ($failure in $failures | Select-Object -First 10) {
        [void]$report.AppendLine("- $($failure | ConvertTo-Json -Depth 8 -Compress)")
    }
    if ($failures.Count -gt 10) {
        [void]$report.AppendLine("- ... $($failures.Count - 10) more failures omitted from the inline summary; see results.json for the full set.")
    }
}
[void]$report.AppendLine('')
[void]$report.AppendLine('## 6) Run instructions')
[void]$report.AppendLine('')
[void]$report.AppendLine('```powershell')
[void]$report.AppendLine('.\scripts\indicator-regression-matrix.ps1')
[void]$report.AppendLine('```')
[void]$report.AppendLine('')
[void]$report.AppendLine("Output directory: $outputRoot")
[void]$report.AppendLine("Raw JSON: $(Join-Path $outputRoot 'results.json')")
[void]$report.AppendLine("Markdown report: $(Join-Path $outputRoot 'report.md')")

$results = @{}
$results['generatedAt'] = (Get-Date).ToString('o')
$results['baseUrls'] = @{ marketData = $MarketDataBaseUrl; indicator = $IndicatorBaseUrl }
$results['prewarm'] = $prewarmRows.Count
$results['matrix'] = $matrixRows.Count
$results['boundary'] = $boundaryRows.Count
$results['selectedSymbols'] = $selectedSymbols.Count
$results['snapshots'] = $requestSnapshots.Count

$reportPath = Join-Path $outputRoot 'report.md'
$jsonPath = Join-Path $outputRoot 'results.json'
Set-Content -Path $reportPath -Value $report.ToString() -Encoding utf8
Set-Content -Path $jsonPath -Value ($results | ConvertTo-Json -Depth 30) -Encoding utf8

Write-Host "Report written to $reportPath"
Write-Host "Results written to $jsonPath"