# Stage 0 Regression Check

## 用途

单条命令验证"当前环境一切正常，可以合并代码"。

每次改动合并前在 `jzhu-trading/` 目录下执行一次，确保：

1. **5 个核心服务均存活**（8181/8182/8183/8185/3000）
2. **3 个 smoke 回测 case 都能跑出 metrics**
3. **verify_metrics.py 独立重算与 Java 端输出全字段 < 1e-2**

---

## 运行命令

```powershell
# 从 jzhu-trading/ 目录执行
.\scripts\regression-check\run.ps1
```

如果已经用 Docker 启动了所有服务（通过 `scripts\manage.cmd start` 或 `$env:FORCE_DOCKER='1'; $env:FORCE_DOCKER_MAVEN='1'; .\scripts\manage.cmd start`），直接运行即可。

---

## PASS 标准

- stdout 末尾打印 `[PASS] Stage 0 regression check`
- exit code = 0
- `logs/regression-check/<YYYYMMDD-HHmmss>.json` 中 `overall: "PASS"`

任何一步 FAIL → 整体 FAIL（exit code ≠ 0）。

---

## 输出

| 产出 | 路径 |
|------|------|
| JSON 报告 | `logs/regression-check/<YYYYMMDD-HHmmss>.json` |
| Smoke 响应快照 | `logs/regression-check/resp_<strategyId>.json` |
| stdout 总结 | 终端直接打印 |

### 报告格式

```json
{
  "timestamp": "2026-05-06T18:50:00+08:00",
  "overall": "PASS",
  "checks": {
    "services_up":    { "status": "PASS", "details": [...] },
    "smoke_backtests": { "status": "PASS", "cases": [...] },
    "verify_metrics":  { "status": "PASS", "max_abs_diff": 0.0 }
  },
  "duration_seconds": 142
}
```

---

## 脚本流程

```
run.ps1
 ├─ Step 1: check-services-up.ps1
 │   └─ HTTP GET 5 个端口，确认服务 UP
 ├─ Step 2: run-3-smoke-backtests.ps1
 │   └─ POST /api/web/backtest/run × 3 case，断言 metrics 非空
 └─ Step 3: invoke-verify-metrics.ps1
     └─ docker run python:3.11-slim 执行 verify_metrics.py
        对每个 case 独立重算 + 逐字段比较
```

---

## 失败排查

| 现象 | 排查方向 |
|------|---------|
| services_up FAIL | 运行 `docker ps` 检查容器是否在跑；用 `scripts\manage.cmd logs` 看日志 |
| smoke_backtests FAIL | 检查 backtest-service / market-data-service 日志；确认数据库连接正常 |
| verify_metrics FAIL | 检查 `logs/regression-check/resp_*.json` 是否完整；确认 `kline_tsla_2024.json` 存在 |
| verify_metrics SKIPPED | 上一步 smoke 失败导致跳过，先修 smoke |
| Docker 报错 | Docker Desktop 是否在运行？`docker info` 检查 |

---

## 高级用法

```powershell
# 跳过服务检查（服务已知已启动）
.\scripts\regression-check\run.ps1 -SkipServices

# 只跑服务检查
.\scripts\regression-check\run.ps1 -SkipSmoke -SkipVerify

# 只跑 verify
.\scripts\regression-check\run.ps1 -SkipServices -SkipSmoke

# 跳过 Docker 环境依赖的 verify 步骤（本机无 Docker 时）
.\scripts\regression-check\run.ps1 -SkipVerify
```

---

## 失败回滚

```powershell
Remove-Item -Recurse -Force .\scripts\regression-check\
```

不影响任何现有代码。删除后重新创建目录即可恢复。