# BacktestMetrics Correctness Verification — Report

Summary
- Implemented an independent verifier script and unit tests to validate BacktestMetrics fields returned by backtest-service.

Step A — 指标口径冻结（字段 → 公式 / 口径 → 代码位置）
- totalReturnPct: (finalEquity - 1.0) * 100.0 — finalEquity = equity[n-1]; see [backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L83-L84)
- maxDrawdownPct: minimum over i of (equity[i]/runningMax - 1.0) * 100.0 — negative percent. See file: [backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L87-L93)
- sharpeRatio: mean(series)/std(series) * sqrt(252). series = per-bar equity returns (cur/prev - 1.0). See [annualFactor and sharpe calc](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L121-L129)
- annualReturnPct: (finalEquity / equity[0])^(252/days) - 1 then *100. days = n (#bars). See [file](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L129)
- volatilityPct: std(series) * sqrt(252) * 100. See [file](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L121-L129)
- winRatePct: (#wins / closedTrades) * 100. wins counted by sign*(close/open -1) > 0. See code near wins loop.
- profitFactor: sum(win returns)/abs(sum(loss returns)) based on decimal trade returns (close/open -1). See [file](backtest-service/src/main/java/ai/jzhu/trading/backtest/application/service/BacktestMetricsCalculator.java#L141-L151)
- closedTrades: count of trades with closed()==true and closeIndex >= 0.
- averageHoldBars: average(closeIndex - openIndex).
- averageHoldDays: average days between openDate and closeDate parsed as ISO local dates.

Key clarifications to align on:
- maxDrawdownPct is a negative value (e.g. -20.0 for a 20% drawdown).
- Sharpe uses per-bar returns over the equity curve (includes zero-return bars) and annualizes by sqrt(252); risk-free assumed 0.
- ProfitFactor is computed from decimal returns, not dollar P/L.

Step B — 黑盒对账（如何运行）
1) Start services (Docker-only):
   Set environment and restart via manage script:
   $env:FORCE_DOCKER='1'; $env:FORCE_DOCKER_MAVEN='1'; .\scripts\manage.cmd restart

2) Run backtest and save response JSON (examples):
   curl -X POST "http://localhost:8181/api/web/backtest/run" -H "Content-Type: application/json" -d @request_tsla.json > logs/backtest-metrics/tsla_response.json
   curl -X POST "http://localhost:8181/api/web/backtest/run" -H "Content-Type: application/json" -d @request_600519.json > logs/backtest-metrics/600519_response.json

3) Run verifier inside Docker (example; use host.docker.internal on Docker Desktop to reach host services):
   docker run --rm -v %CD%:/work -w /work python:3.11-slim bash -c "pip install requests; python jzhu-trading/scripts/metrics-verify/verify_metrics.py --response jzhu-trading/logs/backtest-metrics/tsla_response.json --api-base http://host.docker.internal:8181 --market us"

Notes: the script will fetch klines if they are not included in the response. Default thresholds: percent metrics abs diff ≤ 0.05, sharpe ≤ 0.05.

Step C — 白盒单测
- Added `BacktestMetricsCalculatorTest` under backtest-service/src/test/java covering: no_trades, single profit, single loss, drawdown scenario.
  Run tests: from jzhu-trading run `./mvnw -pl backtest-service test` (or use Docker/mvn wrapper as preferred).

Deliverables added:
- jzhu-trading/scripts/metrics-verify/verify_metrics.py
- jzhu-trading/backtest-service/src/test/java/.../BacktestMetricsCalculatorTest.java
- This REPORT.md

Potential mismatch root causes:
- rounding rules (service rounds to 2 decimals), annualization base (bars vs calendar days), whether Sharpe excludes zero-return bars. These are documented above.

Next steps (I can do if you want):
- Run two sample backtests (I can POST using reasonable request JSON) and run the verifier, saving outputs under logs/backtest-metrics/*.verify.json.
- Run unit tests in a container and paste results.
