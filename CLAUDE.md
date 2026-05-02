# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo overview

This repo is a multi-module quant trading platform:

- **Backend**: Java 21 + Spring Boot 4, built as a Maven multi-module reactor ([pom.xml](pom.xml)).
- **Frontend**: React + Vite in [web-app/](web-app/).
- **Local runtime**: TimescaleDB (Postgres) via Docker; services run either via local `mvn`/`npm` or Docker wrappers.

## Common commands

### One-click start/stop (recommended)

From repo root [jzhu-trading/](.):

- Windows:
  - `scripts\manage.cmd start`
  - `scripts\manage.cmd stop`
  - `scripts\manage.cmd restart`
  - `scripts\manage.cmd status`
  - `scripts\manage.cmd logs`

- Git Bash / Linux / macOS:
  - `chmod +x scripts/manage.sh`
  - `./scripts/manage.sh start`
  - `./scripts/manage.sh stop`

This starts/stops: TimescaleDB, market-data-service, indicator-service, backtest-service, web-service, web-app.

### Backend (Maven / Spring Boot)

Run these from [jzhu-trading/](.) unless noted.

- Build all modules:
  - `./mvnw -DskipTests package`

- Run all tests:
  - `./mvnw test`

- Run a single module’s tests:
  - `./mvnw -pl market-data-service test`

- Run a single test class:
  - `./mvnw -pl market-data-service -Dtest=SomeTest test`

- Run a single service locally:
  - `./mvnw -pl web-service spring-boot:run`

Docker Maven alternative (when local `mvn` is missing):

- `./mvnw-docker.cmd -pl web-service -am -DskipTests compile`

### Frontend (Vite)

Run these from [web-app/](web-app/).

- Install deps:
  - `npm install`

- Dev server:
  - `npm run dev`

- Production build:
  - `npm run build`

- Preview build:
  - `npm run preview`

Note: this frontend `package.json` does not currently define `lint` or `test` scripts.

### Database init
Initialize schema/time-series tables (TimescaleDB container must be running):

- PowerShell:
  - `Get-Content .\db\init\01_init_kline.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform`

### API quick test

- `curl "http://localhost:8181/api/web/kline?symbol=TSLA&market=us&period=daily&startDate=2021-03-18&endDate=2026-03-18"`

## Architecture (big picture)

### Maven modules and responsibilities

The parent reactor is [pom.xml](pom.xml). The modules form a small microservice suite plus shared libraries:

- `trading-common`: shared types/DTOs used across services.
- `strategy-core`: strategy/backtest domain library used by backtest execution.
- `market-data-service`: integrates external market data provider(s) and persists/caches to TimescaleDB.
- `indicator-service`: computes indicators, backed by the same DB.
- `backtest-service`: runs simulations using `strategy-core`; depends on market-data and indicator services.
- `web-service`: BFF/API gateway for the web UI; aggregates calls to downstream services.
- `web-app`: React UI.

### Service topology and ports

Default local ports (also used by `scripts/manage.sh`):

- `web-service`: `8181`
- `market-data-service`: `8182`
- `indicator-service`: `8183`
- `backtest-service`: `8185`
- TimescaleDB: `5432`

### Configuration and service-to-service calls

Service URLs are configured via environment variables in the startup script (useful when running in Docker):

- `web-service` receives:
  - `SERVICE_MARKET_DATA_URL` (default `http://host.docker.internal:8182`)
  - `SERVICE_INDICATOR_URL` (default `http://host.docker.internal:8183`)
  - `SERVICE_BACKTEST_URL` (default `http://host.docker.internal:8185`)

- `backtest-service` receives:
  - `SERVICE_MARKET_DATA_URL`, `SERVICE_INDICATOR_URL`

Database connectivity for services is typically passed as:

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`

External market-data provider key:

- `FMP_API_KEY` (passed into `market-data-service` when started via `scripts/manage.sh`).

### Backend layering convention

Services generally follow a ports/adapters style split (names vary slightly per module):

- `presentation/`: HTTP controllers + request/response mapping + exception handling
- `domain/`: core business logic and ports (interfaces)
- `infrastructure/`: DB access, HTTP clients, and other integrations

This repo’s main integration seams are:

- outbound HTTP calls from `web-service` to the other services
- TimescaleDB access in the services that own persistence

## Repo-specific rules files
No Cursor rules (`.cursor/rules/` or `.cursorrules`) or Copilot instructions (`.github/copilot-instructions.md`) were found in this repo.
