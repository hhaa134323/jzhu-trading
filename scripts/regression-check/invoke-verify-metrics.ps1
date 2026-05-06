<#
.SYNOPSIS
  Stage 0 regression check — Step 3: run verify_metrics.py for all 3 smoke
  backtest cases inside Docker (no local Python required).

  Uses docker run python:3.11-slim with bind-mounted workspace.
  Reads response files saved by run-3-smoke-backtests.ps1 from
  logs/regression-check/resp_<strategyId>.json.
  Uses the pre-bundled kline file scripts/metrics-verify/kline_tsla_2024.json.

  Returns a hashtable with status and per-case comparison results.
#>

param(
    [int]$TimeoutSeconds = 120
)

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$respDir = Join-Path $projectRoot 'logs\regression-check'
$klineFile = Join-Path $projectRoot 'scripts\metrics-verify\kline_tsla_2024.json'

# Verify pre-requisites
if (-not (Test-Path $klineFile)) {
    return @{
        status       = 'SKIPPED'
        reason       = "kline file not found: $klineFile"
        maxAbsDiff   = $null
        caseResults  = @()
    }
}

$strategyIds = @('maCrossLong', 'donchianBreakoutLong', 'bollReversionLong')
$caseResults = @()
$allDiffs = @()

foreach ($sid in $strategyIds) {
    $respFile = Join-Path $respDir "resp_$sid.json"
    if (-not (Test-Path $respFile)) {
        $caseResults += @{
            strategyId = $sid
            status     = 'SKIPPED'
            reason     = "response file not found: $respFile"
        }
        continue
    }

    # Use Linux container paths (python:3.11-slim is a Linux image)
    # Mount project root to /work inside container
    $containerResp = "/work/jzhu-trading/logs/regression-check/resp_$sid.json"
    $containerKline = "/work/jzhu-trading/scripts/metrics-verify/kline_tsla_2024.json"
    $workDir = Resolve-Path (Join-Path $projectRoot '..')  # parent of jzhu-trading

    $dockerCmd = @(
        'run', '--rm',
        '-v', "${workDir}:/work",
        '-w', '/work/jzhu-trading',
        'python:3.11-slim',
        'bash', '-c',
        "pip install requests -q && python scripts/metrics-verify/verify_metrics.py --response '$containerResp' --kline-file '$containerKline' --percent-threshold 0.05 --sharpe-threshold 0.05"
    )

    try {
        $output = & 'docker' $dockerCmd 2>&1
        $outputStr = $output | Out-String
        $lines = $outputStr -split "`n" | Where-Object { $_.Trim() -ne '' }

        # Parse the comparison lines
        $parsed = @()
        $overallPass = $true
        foreach ($line in $lines) {
            if ($line -match '^(\S+)\s+expected=(\S+)\s+actual=(\S+)\s+diff=(\S+)\s+(PASS|FAIL)') {
                $metric = $Matches[1]
                $expected = $Matches[2]
                $actual = $Matches[3]
                $diff = $Matches[4]
                $diffVal = if ($diff -eq 'null' -or $diff -eq 'None') { $null }
                          else { [double]::Parse($diff) }

                # Treat "None == None" as PASS (e.g., profitFactor when no losing trades)
                $passed = if ($diffVal -eq $null -and $expected -eq 'None' -and $actual -eq 'None') { $true }
                          else { $Matches[5] -eq 'PASS' }
                if (-not $passed) { $overallPass = $false }

                $allDiffs += $diffVal

                $parsed += @{
                    metric   = $metric
                    expected = $expected
                    actual   = $actual
                    absDiff  = $diffVal
                    passed   = $passed
                }
            }
        }

        $caseResults += @{
            strategyId = $sid
            status     = if ($overallPass) { 'PASS' } else { 'FAIL' }
            details    = $parsed
            rawOutput  = $outputStr
        }
    }
    catch {
        $caseResults += @{
            strategyId = $sid
            status     = 'FAIL'
            error      = $_.Exception.Message
        }
    }
}

$validDiffs = $allDiffs | Where-Object { $null -ne $_ }
$maxAbsDiff = if ($validDiffs.Count -gt 0) { ($validDiffs | Measure-Object -Maximum).Maximum } else { $null }
$allPass = ($caseResults | Where-Object { $_.status -eq 'FAIL' }).Count -eq 0

return @{
    status      = if ($allPass) { 'PASS' } else { 'FAIL' }
    maxAbsDiff  = $maxAbsDiff
    caseResults = $caseResults
}