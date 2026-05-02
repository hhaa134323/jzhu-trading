# Common Commands

This document lists commonly used startup and shutdown commands for the project.

## 1. One-click Start and Stop

Windows (recommended):

```cmd
scripts\manage.cmd start
scripts\manage.cmd stop
scripts\manage.cmd restart
scripts\manage.cmd status
scripts\manage.cmd logs
```

Git Bash / Linux / macOS:

From project root:

```bash
chmod +x scripts/manage.sh
./scripts/manage.sh start
```

Stop all:

```bash
./scripts/manage.sh stop
```

Restart all:

```bash
./scripts/manage.sh restart
```

Check status:

```bash
./scripts/manage.sh status
```

View latest logs:

```bash
./scripts/manage.sh logs
```

## 2. Start Single Components

Start TimescaleDB only:

```bash
docker start trading-timescaledb
```

Start market-data-service only:

```bash
mvn -pl market-data-service -am spring-boot:run -DskipTests
```

Start web-service only:

```bash
mvn -pl web-service -am spring-boot:run -DskipTests
```

Start indicator-service only:

```bash
mvn -pl indicator-service -am spring-boot:run -DskipTests
```

Start web-app only:

```bash
cd web-app
npm install
npm run dev -- --host 0.0.0.0 --port 3000
```

## 3. Docker Maven Alternative (when local mvn is missing)

Compile web-service:

```bash
./mvnw-docker.cmd -pl web-service -am -DskipTests compile
```

Compile market-data-service:

```bash
./mvnw-docker.cmd -pl market-data-service -am -DskipTests compile
```

## 4. Database Init Script

Use this command in PowerShell (Windows):

```powershell
Get-Content .\db\init\01_init_kline.sql | docker exec -i trading-timescaledb psql -U trading -d trading_platform
```

## 5. API Quick Test

Get K-line data from web-service:

```bash
curl "http://localhost:8181/api/web/kline?symbol=TSLA&market=us&period=daily&startDate=2021-03-18&endDate=2026-03-18"
```
