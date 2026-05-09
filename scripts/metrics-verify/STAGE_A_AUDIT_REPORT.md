# Stage A Audit Report: Python vs Java 回测路径 strict 等价性

**日期**: 2026-05-08
**结论**: 引擎层 8/10 pitfall 已规避，根因在 Python 模板源码层面（非引擎 bug）

---

## 1. 4 张前端策略卡 baseStrategyId

| 卡片名称 | baseStrategyId | engineType | 备注 |
|----------|---------------|------------|------|
| 均线交叉-做多 | maCrossLong | JAVA_BUILTIN_ADAPTER | Java MaCrossLongStrategy |
| MACD金叉死叉-做多 | maCrossLong | JAVA_BUILTIN_ADAPTER | 复制 bug，seed 层重复 |
| RSI超卖反弹-做多 | bollReversionLong | JAVA_BUILTIN_ADAPTER | 错位映射 |
| 布林带突破-做多 | donchianBreakoutLong | JAVA_BUILTIN_ADAPTER | BullishLongStrategy.getId() 返回此 id，命名 trap |

- `donchianBreakoutLong` → `BullishLongStrategy.getId()` 返回该 id，前端看起来像布林/Donchian但实际映射到 BullishLongStrategy
- MACD 卡片的 baseStrategyId=maCrossLong 是 seed 层重复 bug（用户已确认）

---

## 2. 实测数据

测试条件: TSLA 美股 日K, 本金 100k, 1bps + 5bps

### 区间 A (2024-01-01 ~ 2024-12-31, 170 bar)

| 路径 | 笔数 | return | MA 周期 |
|------|------|--------|---------|
| Java (MaCrossLongStrategy) | 4 | 90.04% | MA10/MA20 (hardcoded) |
| Python v21 | 6 | 77.88% | MA5/MA20 (code default) |

Δ: trade count +2 (50%), return -12.16%

### 区间 B (2024-05-08 ~ 2026-05-08, 501 bar)

| 路径 | 笔数 | return | MA 周期 |
|------|------|--------|---------|
| Java (MaCrossLongStrategy) | 11 | ~75.38% | MA10/MA20 (hardcoded) |
| Python v18 | 15 | ~103.56% | MA10/MA20 (code default) |

Δ: trade count +4 (36%) — 与用户报告一致

STEP1_VERIFY_REPORT 声称的 "4 vs 4 / Δreturn 0.46%" 在当前代码中已无法复现（v3→v21 模板退化）。

---

## 3. 第一根分化 bar 证据

### 区间 A (v21, fast=5 ≠ Java=10)

- **Java 第一笔 BUY**: bar 34 (2024-06-18)
  - prevFast(MA10)=177.49 ≤ prevSlow(MA20)=177.78 ✓
  - currFast(MA10)=178.49 > currSlow(MA20)=178.28 ✓
  - close=184.86 > slow=178.28 ✓
  - → 金叉触发

- **Python 第一笔 BUY**: bar 4 (2024-05-06)
  - ma_fast(MA5)=181.85 > ma_slow(MA20)=167.80
  - → 立即触发 (no cross check)

### 区间 B (v18, fast=10/slow=20, 同 MA 周期)

- **Java 第一笔**: bar 29 (2024-06-20), golden cross
- **Python 第一笔**: bar 1 (2024-05-09), fast > slow → 无交叉要求

即使在相同 MA 周期下，Python 也不检查 cross 事件。

---

## 4. 10 项 Pitfall 判定

| # | Pitfall | 判定 | 证据 |
|---|---------|------|------|
| 1 | Look-ahead | **已规避** | BacktestEngine 传 bar[t] 给策略，Python adapter 用 klines.get(barIndex).close() |
| 2 | Fill at t+1 | **已规避** | BacktestEngine L83: fillIndex = sig.index() + 1，统一执行 |
| 3 | Warmup | **未规避** | Java 显式检查 currentIndex < 20；Python 仅检查 None（MA20 前 19 bar=null 但 MA5 bar 4 已有值） |
| 4 | Position validation | **已规避** | Java hasPosition 参数；Python ctx.position.qty |
| 5 | Slippage symmetry | **已规避** | BacktestEngine.applySlippage() 统一处理 |
| 6 | Restating | **已规避** | 同一 IndicatorData 对象传入 |
| 7 | Split adjustment | **已规避** | 同一 KlineData 列表 |
| 8 | Force close | **已规避** | BacktestEngine 统一末根强平逻辑 |
| **9** | **Cross detection** | **未规避** | Java: prevFast≤prevSlow && currFast>currSlow && close>slow；Python: ma_fast > ma_slow |
| 10 | Close timing | **已规避** | SELL signal bar[t] → fill bar[t+1].open |

---

## 5. 关键发现

1. **引擎三件套 8/10 pitfall 已规避** — BacktestEngine / PythonStrategyRunner / PythonTradingStrategyAdapter 层无常见回测错误

2. **pitfall #9 #3 不规避属于 Python 模板源码层面** — 不是引擎 bug，是数据库存储的 `on_bar(ctx)` 函数逻辑缺陷

3. **v3 → v21 模板回归**:
   - v3 有完整 cross 逻辑（与 Java 等价）
   - v18/v21 简化为 `fast > slow → BUY; fast < slow → SELL`
   - 丢失了 cross 事件 + close 确认

4. **MaCrossLongStrategy 硬编码 MA10/MA20 不读 params** — Java 侧 bug，留给 Python 端口取代，本次不修

5. **donchianBreakoutLong 是命名 trap** — `BullishLongStrategy.getId()` 返回该 id，不是 silent bug

---

## 6. 根因总结

- **R1 (pitfall #9)**: Python 策略没有 cross 检测（prev bar 关系 + close 确认）
- **R2 (pitfall #9)**: Python 策略缺少 close-below-slow 独立离场条件
- **R3 (pitfall #3)**: MA 默认周期与 Java 不一致（v21: fast=5 vs Java: fast=10）
