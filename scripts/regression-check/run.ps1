<#
.SYNOPSIS
  Stage 0 regression check — one-command smoke + metrics verification.

  Runs from the `jzhu-trading` working directory.  Expects services to already
  be running (via `manage.cmd start`).

  Steps:
    1. check-services-up  —  probes 5 service ports (8181/8182/8183/8185/3000)
    2. run-3-smoke-backtests  —  POST 3 backtest cases, assert non-null metrics
    3. invoke-verify-metrics  —  Docker-based verify_metrics.py on saved responses

  Output: logs/regression-check/<YYYYMMDD-HHmmss>.json   (machine report)
          stdout human-friendly summary.

  Exit code: 0 = all PASS, 1 = any FAIL/SKIPPED.
#>

param(
    [switch]$SkipServices,
    [switch]$SkipSmoke,
    [switch]$SkipVerify
)

$ErrorActionPreference = 'Continue'  # don't abort on sub-step failures
$startTime = Get-Date

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir '..\..')
$logDir = Join-Path $projectRoot 'logs\regression-check'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

Write-Host "=== Stage 0 Regression Check ===" -ForegroundColor Cyan
Write-Host "Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray
Write-Host ""

# ── Step 1: Service ports ──────────────────────────────────────────────
$servicesResult = $null
$step1Duration = 0
if (-not $SkipServices) {
    Write-Host "[Step 1/3] Checking service ports..." -ForegroundColor Yellow
    $s1 = Get-Date
    $servicesResult = & (Join-Path $scriptDir 'check-services-up.ps1')
    $step1Duration = ((Get-Date) - $s1).TotalSeconds
    $statusColor = if ($servicesResult.status -eq 'PASS') { 'Green' } else { 'Red' }
    Write-Host "  -> Services: $($servicesResult.status)" -ForegroundColor $statusColor
    foreach ($d in $servicesResult.details) {
        $c = if ($d.status -eq 'UP') { 'Green' } else { 'Red' }
        Write-Host "     $($d.service) (port $($d.port)) : $($d.status)" -ForegroundColor $c
    }
}
else {
    Write-Host "[Step 1/3] SKIPPED (--SkipServices)" -ForegroundColor Gray
    $servicesResult = @{ status = 'SKIPPED'; details = @() }
}

# ── Step 2: Smoke backtests ────────────────────────────────────────────
$smokeResult = $null
$step2Duration = 0
if (-not $SkipSmoke) {
    # Check if key services for backtest API are reachable
    $keySvcUp = $servicesResult.details | Where-Object { $_.service -in @('web-service', 'backtest') -and $_.status -eq 'UP' }
    if ($keySvcUp.Count -ge 2) {
        Write-Host "[Step 2/3] Running 3 smoke backtests..." -ForegroundColor Yellow
        $s2 = Get-Date
        $smokeResult = & (Join-Path $scriptDir 'run-3-smoke-backtests.ps1')
        $step2Duration = ((Get-Date) - $s2).TotalSeconds
        $statusColor = if ($smokeResult.status -eq 'PASS') { 'Green' } else { 'Red' }
        Write-Host "  -> Smoke backtests: $($smokeResult.status)" -ForegroundColor $statusColor
        foreach ($c in $smokeResult.cases) {
            $sc = if ($c.status -eq 'PASS') { 'Green' } elseif ($c.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' }
            Write-Host "     $($c.strategyId) ($($c.symbol)) : $($c.status) trades=$($c.totalTrades)" -ForegroundColor $sc
            if ($c.error) { Write-Host "       error: $($c.error)" -ForegroundColor DarkRed }
        }
    }
    else {
        Write-Host "[Step 2/3] SKIPPED (web-service or backtest not reachable)" -ForegroundColor Gray
        $smokeResult = @{ status = 'SKIPPED'; cases = @() }
    }
}
else {
    Write-Host "[Step 2/3] SKIPPED (--SkipSmoke)" -ForegroundColor Gray
    $smokeResult = @{ status = 'SKIPPED'; cases = @() }
}

# ── Step 3: Verify metrics ─────────────────────────────────────────────
$verifyResult = $null
$step3Duration = 0
if (-not $SkipVerify -and $smokeResult.status -eq 'PASS') {
    Write-Host "[Step 3/3] Verifying metrics (Docker verify_metrics.py)..." -ForegroundColor Yellow
    $s3 = Get-Date
    $verifyResult = & (Join-Path $scriptDir 'invoke-verify-metrics.ps1')
    $step3Duration = ((Get-Date) - $s3).TotalSeconds
    $statusColor = if ($verifyResult.status -eq 'PASS') { 'Green' } elseif ($verifyResult.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' }
    Write-Host "  -> Verify metrics: $($verifyResult.status)" -ForegroundColor $statusColor
    if ($verifyResult.maxAbsDiff -ne $null) {
        Write-Host "     max abs diff = $($verifyResult.maxAbsDiff)"
    }
    foreach ($cr in $verifyResult.caseResults) {
        $sc = if ($cr.status -eq 'PASS') { 'Green' } elseif ($cr.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' }
        Write-Host "     $($cr.strategyId) : $($cr.status)" -ForegroundColor $sc
    }
}
elseif (-not $SkipVerify) {
    Write-Host "[Step 3/3] SKIPPED (smoke backtests did not all PASS)" -ForegroundColor Gray
    $verifyResult = @{ status = 'SKIPPED'; maxAbsDiff = $null; caseResults = @() }
}
else {
    Write-Host "[Step 3/3] SKIPPED (--SkipVerify)" -ForegroundColor Gray
    $verifyResult = @{ status = 'SKIPPED'; maxAbsDiff = $null; caseResults = @() }
}

# ── Compute overall ────────────────────────────────────────────────────
$totalDuration = ((Get-Date) - $startTime).TotalSeconds

$checkStatuses = @(
    $servicesResult.status,
    $smokeResult.status,
    $verifyResult.status
)

$hasFail = ($checkStatuses -contains 'FAIL')
$hasSkip = ($checkStatuses -contains 'SKIPPED')

if ($hasFail) {
    $overall = 'FAIL'
} elseif ($hasSkip) {
    $overall = 'PASS_WITH_SKIPPED'
} else {
    $overall = 'PASS'
}

$report = @{
    timestamp       = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')
    overall         = $overall
    checks          = @{
        services_up    = @{
            status  = $servicesResult.status
            details = $servicesResult.details
        }
        smoke_backtests = @{
            status = $smokeResult.status
            cases  = $smokeResult.cases
        }
        verify_metrics  = @{
            status      = $verifyResult.status
            max_abs_diff = $verifyResult.maxAbsDiff
            caseResults = $verifyResult.caseResults
        }
    }
    duration_seconds = [math]::Round($totalDuration, 1)
    step_durations_seconds = @{
        services_check   = [math]::Round($step1Duration, 1)
        smoke_backtests  = [math]::Round($step2Duration, 1)
        verify_metrics   = [math]::Round($step3Duration, 1)
    }
}

# ── Write report ───────────────────────────────────────────────────────
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportFile = Join-Path $logDir "$timestamp.json"
$reportJson = $report | ConvertTo-Json -Depth 10
Set-Content -Path $reportFile -Value $reportJson -Encoding UTF8

# ── Final summary ──────────────────────────────────────────────────────
Write-Host ""
$overallColor = if ($overall -eq 'PASS') { 'Green' } elseif ($overall -eq 'PASS_WITH_SKIPPED') { 'Yellow' } else { 'Red' }
$exitCode = if ($overall -eq 'PASS') { 0 } else { 1 }

Write-Host "[$($overall)] Stage 0 regression check ($([math]::Round($totalDuration))s). Report: $reportFile" -ForegroundColor $overallColor
Write-Host ""

# Summary table
Write-Host "Summary:" -ForegroundColor Cyan
Write-Host "  services_up     : $($servicesResult.status)" -ForegroundColor $(if ($servicesResult.status -eq 'PASS') { 'Green' } elseif ($servicesResult.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' })
Write-Host "  smoke_backtests : $($smokeResult.status)"   -ForegroundColor $(if ($smokeResult.status -eq 'PASS') { 'Green' } elseif ($smokeResult.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' })
Write-Host "  verify_metrics  : $($verifyResult.status)"  -ForegroundColor $(if ($verifyResult.status -eq 'PASS') { 'Green' } elseif ($verifyResult.status -eq 'SKIPPED') { 'Yellow' } else { 'Red' })
Write-Host "Duration: $([math]::Round($totalDuration, 1))s" -ForegroundColor Gray

exit $exitCode