<#
.SYNOPSIS
  Stage 0 regression check — Step 2: run 3 smoke backtest cases and verify
  each returns non-null, non-empty metrics.

  Calls POST /api/web/backtest/run on web-service (port 8181) for 3 strategies:
    1. maCrossLong          + TSLA 2024-01-01~2024-12-31
    2. donchianBreakoutLong + TSLA 2024-01-01~2024-12-31
    3. bollReversionLong    + TSLA 2024-01-01~2024-12-31

  Returns a hashtable with status + cases array.
  Each case has: strategyId, symbol, status (PASS/FAIL), totalTrades, and
  a brief error string on failure.
#>

param(
    [int]$TimeoutSeconds = 60
)

$webUrl = 'http://localhost:8181/api/web/backtest/run'

$cases = @(
    @{ strategyId = 'maCrossLong';          symbol = 'TSLA'; market = 'us'; period = 'daily'; startDate = '2024-01-01'; endDate = '2024-12-31' }
    @{ strategyId = 'donchianBreakoutLong';  symbol = 'TSLA'; market = 'us'; period = 'daily'; startDate = '2024-01-01'; endDate = '2024-12-31' }
    @{ strategyId = 'bollReversionLong';     symbol = 'TSLA'; market = 'us'; period = 'daily'; startDate = '2024-01-01'; endDate = '2024-12-31' }
)

$caseResults = @()

foreach ($case in $cases) {
    $body = $case | ConvertTo-Json
    $result = @{
        strategyId   = $case.strategyId
        symbol       = $case.symbol
        status       = 'FAIL'
        totalTrades  = $null
        error        = $null
        responsePath = $null
    }

    try {
        $response = Invoke-RestMethod -Uri $webUrl -Method POST `
            -Body $body -ContentType 'application/json' `
            -TimeoutSec $TimeoutSeconds -ErrorAction Stop

        $hasMetrics = $null -ne $response.metrics
        $metricsNonNull = $hasMetrics -and ($response.metrics.PSObject.Properties.Name.Count -gt 0)

        $totalTrades = $response.totalTrades
        $result.totalTrades = $totalTrades

        if ($hasMetrics -and $metricsNonNull) {
            $result.status = 'PASS'
        }
        else {
            $result.error = 'metrics is null or empty'
        }

        # Save response for step 3 (verify_metrics)
        $outDir = Join-Path $PSScriptRoot '..\..\logs\regression-check'
        if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
        $outFile = Join-Path $outDir "resp_$($case.strategyId).json"
        $response | ConvertTo-Json -Depth 10 | Set-Content -Path $outFile -Encoding UTF8
        $result.responsePath = $outFile
    }
    catch {
        $result.error = $_.Exception.Message
    }

    $caseResults += $result
}

$allPassed = ($caseResults | Where-Object { $_.status -eq 'FAIL' }).Count -eq 0

return @{
    status = if ($allPassed) { 'PASS' } else { 'FAIL' }
    cases  = $caseResults
}