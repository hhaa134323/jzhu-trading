# Indicator Correctness Verification

This folder contains a Docker-based correctness check for the indicator-service.

## What it does

1. Uses `curl.exe` to fetch and snapshot K-line input data from `market-data-service`.
2. Uses `curl.exe` to snapshot `indicator-service` responses for the same symbol, market, and period.
3. Runs a Python reference implementation inside Docker to compare the service output against independently computed indicators.
4. Writes artifacts to `logs/indicator-verify/`.

## Run

From `jzhu-trading/`:

```powershell
.\scripts\indicator-verify\run.ps1
```

Optional parameters:

```powershell
.\scripts\indicator-verify\run.ps1 -StartDate 2016-01-01 -EndDate 2024-06-30
```

## Output

- `logs/indicator-verify/*_kline.json`
- `logs/indicator-verify/*_indicator.json`
- `logs/indicator-verify/*_indicator_request.json`
- `logs/indicator-verify/correctness-results.json`
- `logs/indicator-verify/correctness-report.md`