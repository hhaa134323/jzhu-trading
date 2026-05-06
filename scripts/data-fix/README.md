# US K-line Data Repair

## Background

`FmpMarketDataProvider` was calling FMP's `/historical-price-eod/light` endpoint,
which only returns `{ symbol, date, price, volume }` — no real open/high/low.
The code had a fallback that set `open=high=low=close=price`, writing flat
OHLC rows into `kline_daily`. This caused:

- Candle bodies invisible in the front-end (height = 0)
- All volume bars painted red (`close == open` always true)
- MA/BOLL indicators worked fine (only depend on close), masking the bug

## Fix

The endpoint was changed to `/historical-price-eod/full`, which returns real
OHLC (`open`, `high`, `low`, `close`, `volume`). The flattened fallback was
removed — instead, any row missing OHLC fields is logged and skipped.

A sanity-check log was added: if >95% of returned rows have `open == close`,
a `log.warn("Suspicious flat OHLC from FMP...")` is emitted.

## Cleanup Steps

Run from the repository root (`finance/jzhu-trading`).

### 1. Delete flat rows

```powershell
# Dry run first (count only):
.\scripts\data-fix\repair_us_kline.ps1

# Interactive (prompt before delete):
.\scripts\data-fix\repair_us_kline.ps1 -Confirm

# Non-interactive:
.\scripts\data-fix\repair_us_kline.ps1 -Force
```

### 2. Rebuild & restart services

```powershell
$env:FORCE_DOCKER='1'; $env:FORCE_DOCKER_MAVEN='1'; .\scripts\manage.cmd restart
```

### 3. Trigger re-fetch

Call the market-data endpoint for any US symbol to re-fetch from FMP with the
correct endpoint:

```powershell
curl "http://localhost:8182/api/market-data/kline?symbol=TSLA&market=us&period=daily&startDate=2024-05-06&endDate=2026-05-05"
```

Or just open the web UI at `http://localhost:3000` and search TSLA.

### 4. Verify

```powershell
docker exec trading-timescaledb psql -U trading -d trading_platform -c "
SELECT count(*) AS flat_rows FROM kline_daily WHERE market='us' AND open=high AND high=low AND low=close;
-- Expected: 0

SELECT time, open, high, low, close, volume
FROM kline_daily WHERE symbol='TSLA' AND market='us'
ORDER BY time DESC LIMIT 10;
-- Expected: at least 5 rows where open != close
"
```

## Rollback

- **Code revert**: `git revert <commit-hash>` — one line, instant rollback.
- **Data cleanup**: The DELETE is irreversible (DROP + re-fetch needed).
  Old flat data is worthless, so no backup needed.
- **Risk**: After rolling back code, re-running will write flat data again.
  Don't roll back unless you're also switching to a different data provider.

## FMP API Note

The `/historical-price-eod/full` endpoint returns real OHLC. It counts toward
the same API key rate/quota as `/light`. Verify your FMP plan supports this
endpoint — most paid plans include it. If you see 403 errors after deployment,
check the key's endpoint permissions on the FMP dashboard.