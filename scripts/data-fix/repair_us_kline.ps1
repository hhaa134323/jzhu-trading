<#
.SYNOPSIS
    Delete flattened US K-line rows from kline_daily that were written by the buggy
    /historical-price-eod/light endpoint (which only returned price+volume, causing
    open=high=low=close).
.DESCRIPTION
    Docker-only script. Connects to the trading-timescaledb container and:
      1. Counts flat rows (open=high=low=close) for market='us'.
      2. Deletes them.
      3. Counts remaining flat rows (should be 0).
.PARAMETER WhatIf
    Only count, do not delete.
.EXAMPLE
    .\repair_us_kline.ps1              # dry-run: show counts only
    .\repair_us_kline.ps1 -Confirm     # interactive: ask before delete
    .\repair_us_kline.ps1 -Force       # non-interactive: count + delete + verify
#>

param(
    [switch]$WhatIf,
    [switch]$Confirm,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$container = 'trading-timescaledb'
$psql = "docker exec -i $container psql -U trading -d trading_platform"

Write-Host "=== US K-line Data Repair ===" -ForegroundColor Cyan

# Step 1: Count flat rows
Write-Host "`n[1/3] Counting flat rows (open=high=low=close) for market='us'..." -ForegroundColor Yellow
$countResult = Invoke-Expression "$psql -At -c ""SELECT count(*) FROM kline_daily WHERE market='us' AND open=high AND high=low AND low=close;"""
$totalResult = Invoke-Expression "$psql -At -c ""SELECT count(*) FROM kline_daily WHERE market='us';"""
Write-Host "  Flat rows: $countResult / $totalResult total US rows" -ForegroundColor Magenta

if ([int]$countResult -eq 0) {
    Write-Host "  No flat rows found. Nothing to clean." -ForegroundColor Green
    exit 0
}

# Step 2: Decide whether to delete
$doDelete = $false
if ($Force) {
    $doDelete = $true
} elseif ($WhatIf) {
    Write-Host "`n[2/3] SKIP (WhatIf mode)" -ForegroundColor Yellow
    $doDelete = $false
} else {
    # Interactive confirmation
    $response = Read-Host "`nDelete $countResult flat rows? (y/N)"
    $doDelete = ($response -eq 'y' -or $response -eq 'Y')
}

if ($doDelete) {
    Write-Host "`n[2/3] Deleting $countResult flat rows..." -ForegroundColor Yellow
    Invoke-Expression "$psql -c ""DELETE FROM kline_daily WHERE market='us' AND open=high AND high=low AND low=close;"""
    Write-Host "  Done." -ForegroundColor Green
} else {
    Write-Host "`n[2/3] Skipped (no delete performed)." -ForegroundColor Gray
}

# Step 3: Verify
Write-Host "`n[3/3] Verifying..." -ForegroundColor Yellow
$remaining = Invoke-Expression "$psql -At -c ""SELECT count(*) FROM kline_daily WHERE market='us' AND open=high AND high=low AND low=close;"""
$newTotal = Invoke-Expression "$psql -At -c ""SELECT count(*) FROM kline_daily WHERE market='us';"""
Write-Host "  Remaining flat rows: $remaining / $newTotal total US rows" -ForegroundColor Magenta

if ([int]$remaining -eq 0) {
    Write-Host "  CLEAN: All flat rows removed. Data ready for re-fetch." -ForegroundColor Green
} else {
    Write-Host "  WARNING: $remaining flat rows still remain." -ForegroundColor Red
}

Write-Host "`n=== Done ===" -ForegroundColor Cyan