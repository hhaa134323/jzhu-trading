# QuantSimulator

This repository conuantSimulatortains the `QuantSimulator` microservices and web app used for local development and backtesting.

Quick start (requires Docker Desktop):

```powershell
cd QuantSimulator
scripts\manage.cmd start
```

Services:
- market-data-service (8182)
- indicator-service (8183)
- backtest-service (8185)
- web-service (8181)
- web-app (Vite dev server, 3000)
