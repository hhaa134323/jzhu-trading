# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo overview

This repo is a multi-module quant trading platform. **Docker Desktop is required** — all services and the frontend run exclusively through Docker containers.

- **Backend**: Java 21 + Spring Boot 4.0, built as a Maven multi-module reactor ([pom.xml](pom.xml)).
- **Frontend**: React 19 + Vite 7 + TypeScript in [web-app/](web-app/).
- **Local runtime**: TimescaleDB (PostgreSQL 16) via Docker.

## Common commands

### Start/stop (the only supported way)

All commands from repo root. Everything runs inside Docker containers managed by these scripts.

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
  - `./scripts/manage.sh restart`
  - `./scripts/manage.sh status`
  - `./scripts/manage.sh logs`

`start` brings up containers in order: TimescaleDB → market-data-service → indicator-service → backtest-service → web-service → web-app. The script auto-detects whether to use local `mvn` or Docker-based Maven for backend services. Set `FORCE_DOCKER_MAVEN=1` to force Docker mode.

Environment variables are loaded from [.env](.env) at startup (`FMP_API_KEY`, `SPRING_PROFILES_ACTIVE`, DB credentials).

### Backend (Maven build/test)

No Maven Wrapper is checked in. Backend build and test commands MUST run through Docker — use the `mvnw-docker` scripts:

- Build all modules:
  - `./mvnw-docker.cmd -DskipTests package`
  - `./mvnw-docker.ps1 -DskipTests package`

- Run all tests:
  - `./mvnw-docker.cmd test`
  - `./mvnw-docker.ps1 test`

- Run a single module's tests:
  - `./mvnw-docker.cmd -pl market-data-service test`

- Run a single test class:
  - `./mvnw-docker.cmd -pl market-data-service -Dtest=SomeTest test`

These run `maven:3.9.9-eclipse-temurin-21` with the repo root mounted at `/workspace` and `~/.m2` mounted for dependency caching.

### Frontend

The web-app dev server is started automatically by `manage.cmd start` (inside a `node:22-alpine` container). **Do not run `npm install`, `npm run dev`, or `npm run build` locally** — all of these happen inside the Docker container.

No `lint` or `test` scripts are defined.

### Database init

TimescaleDB container must be running. Two init scripts:

- PowerShell:
  - `Get-Content .\db\init\01_init_kline.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform`
  - `Get-Content .\db\init\02_init_indicators.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform`

- Bash:
  - `cat db/init/01_init_kline.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform`
  - `cat db/init/02_init_indicators.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform`

`01_init_kline.sql` creates the K-line hypertable; `02_init_indicators.sql` creates MA, MACD, RSI, and Bollinger Band hypertables.

### API quick test

```bash
curl "http://localhost:8181/api/web/kline?symbol=TSLA&market=us&period=daily&startDate=2021-03-18&endDate=2026-03-18"
```

## Architecture

### Maven modules and responsibilities

The parent reactor is [pom.xml](pom.xml). Modules fall into two categories:

**Shared libraries** (not runnable on their own):

- `trading-common`: shared DTOs/types used across services.
- `strategy-core`: strategy/backtest domain library used by backtest execution.

**Runnable services** (Spring Boot apps):

- `market-data-service`: integrates FMP as external market data provider; persists/caches K-line data to TimescaleDB.
- `indicator-service`: computes technical indicators (MA, MACD, RSI, Bollinger) and persists them to TimescaleDB.
- `backtest-service`: runs simulations using `strategy-core`; depends on market-data and indicator services. Supports Python-based strategy execution via a custom Docker image (see below).
- `web-service`: BFF/API gateway for the web UI; aggregates calls to downstream services.

### Service topology and ports

| Service | Port |
| --- | --- |
| `web-app` (Vite dev) | 3000 |
| `web-service` | 8181 |
| `market-data-service` | 8182 |
| `indicator-service` | 8183 |
| `backtest-service` | 8185 |
| TimescaleDB | 5432 |

### Service-to-service wiring

`web-service` is the only user-facing entry point. It calls downstream services via configurable URLs:

- `web-service` receives: `SERVICE_MARKET_DATA_URL`, `SERVICE_INDICATOR_URL`, `SERVICE_BACKTEST_URL`
- `backtest-service` receives: `SERVICE_MARKET_DATA_URL`, `SERVICE_INDICATOR_URL`
- All services receive: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `market-data-service` additionally receives: `FMP_API_KEY`

Defaults (set in [scripts/manage.sh](scripts/manage.sh)) use `http://host.docker.internal:{port}` for inter-service calls and `localhost:5432` for the DB.

### Backend layering convention

Services use a 4-layer architecture (varying slightly per module):

- `presentation/` — HTTP controllers, request/response DTOs, exception handlers
- `application/` — use cases (`usecase/`) and application services (`service/`)
- `domain/` — core business logic, domain models (`model/`), and ports/interfaces (`port/`)
- `infrastructure/` — DB access (`persistence/`), HTTP clients (`client/`), external API integrations (`external/`), Spring config

Cross-cutting: `trading-common` provides shared DTOs under `ai.jzhu.trading.common.dto`.

### backtest-service Python3 support

[backtest-service/Dockerfile](backtest-service/Dockerfile) extends the base Maven image with Python3, enabling `PYTHON_CODE` strategy execution. The manage script builds this as `jzhu-backtest-service:latest` on startup. If the build fails, Python strategies are unavailable but the service still runs with Java strategies only.

### Configuration

- [.env](.env) — runtime secrets and Spring profile (`FMP_API_KEY`, `SPRING_PROFILES_ACTIVE`, DB credentials). Loaded by manage.sh and by `spring-dotenv` at app startup. **Do not commit real API keys.**
- Service `application.yml` files reside in each module's `src/main/resources/`.
