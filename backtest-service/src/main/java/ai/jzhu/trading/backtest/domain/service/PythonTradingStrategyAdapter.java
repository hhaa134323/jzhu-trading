package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.indicator.MaData;
import ai.jzhu.strategy.domain.model.Direction;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts a PYTHON_CODE strategy definition to the {@link TradingStrategy} interface.
 *
 * <p>On construction, it pre-computes one {@code on_bar(ctx)} call per bar index,
 * caching the action (HOLD/BUY/SELL) and quantity. The cache is then used by
 * {@link #checkOpenSignal} and {@link #checkCloseSignal} so that each bar only
 * triggers a single Python invocation.
 *
 * <p>Two execution modes are supported:
 * <ul>
 *   <li><b>Daemon mode</b> (preferred): uses {@link PythonDaemonRunner}, one long-running
 *       Python process per backtest. Call {@link #close()} after the backtest.</li>
 *   <li><b>Legacy mode</b>: uses {@link PythonStrategyRunner}, per-bar subprocess.
 *       Retained for test compatibility.</li>
 * </ul>
 */
public class PythonTradingStrategyAdapter implements TradingStrategy, AutoCloseable {

    private final String id;
    private final String name;
    private final String code;
    private final String entrypoint;
    private final List<KlineData> klines;
    private final IndicatorData indicators;
    private final Map<String, Object> params;
    private final PythonStrategyRunner runner;
    private final PythonDaemonRunner daemon;

    /** Cached on_bar result per bar index. Indexed by bar position. */
    private final PythonStrategyExecutionResult[] barResults;

    // Lazy-computed EMA caches for MACD params (computed once on first access)
    private Double[] emaFastCache;
    private Double[] emaSlowCache;
    private Double[] difCache;
    private Double[] deaCache;

    /**
     * Legacy constructor — uses per-bar {@link PythonStrategyRunner} subprocess.
     *
     * @param id         Strategy identifier (e.g. templateId#version).
     * @param name       Human-readable name.
     * @param code       Python source code.
     * @param entrypoint Function name to call (default "on_bar").
     * @param klines     Kline data for the backtest period.
     * @param indicators Indicator data for the backtest period.
     * @param params     Strategy parameters (from StrategyDefinition).
     * @param runner     PythonStrategyRunner instance.
     */
    public PythonTradingStrategyAdapter(
            String id,
            String name,
            String code,
            String entrypoint,
            List<KlineData> klines,
            IndicatorData indicators,
            Map<String, Object> params,
            PythonStrategyRunner runner
    ) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.entrypoint = entrypoint != null && !entrypoint.isBlank() ? entrypoint : "on_bar";
        this.klines = klines;
        this.indicators = indicators;
        this.params = params;
        this.runner = runner;
        this.daemon = null;
        this.barResults = new PythonStrategyExecutionResult[klines.size()];
    }

    /**
     * Daemon constructor — uses a long-running {@link PythonDaemonRunner} process.
     * The daemon is owned by this adapter; call {@link #close()} to shut it down.
     *
     * @param id         Strategy identifier (e.g. templateId#version).
     * @param name       Human-readable name.
     * @param klines     Kline data for the backtest period.
     * @param indicators Indicator data for the backtest period.
     * @param params     Strategy parameters (from StrategyDefinition).
     * @param daemon     PythonDaemonRunner instance (already initialized).
     */
    public PythonTradingStrategyAdapter(
            String id,
            String name,
            List<KlineData> klines,
            IndicatorData indicators,
            Map<String, Object> params,
            PythonDaemonRunner daemon
    ) {
        this.id = id;
        this.name = name;
        this.code = null;
        this.entrypoint = null;
        this.klines = klines;
        this.indicators = indicators;
        this.params = params;
        this.runner = null;
        this.daemon = daemon;
        this.barResults = new PythonStrategyExecutionResult[klines.size()];
    }

    @Override
    public void close() {
        if (daemon != null) {
            daemon.close();
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "Python strategy: " + entrypoint + "()";
    }

    /**
     * Called by BacktestEngine when no position is held.
     * If Python returned BUY, generate an open-long signal.
     */
    @Override
    public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators,
                                                  int currentIndex, boolean hasPosition) {
        PythonStrategyExecutionResult result = getOrCompute(currentIndex, false);
        if (result == null || !result.success()) {
            return Optional.empty();
        }
        if (result.isBuy()) {
            KlineData k = klines.get(currentIndex);
            double qty = result.getQtyOptional().orElse(1.0);
            return Optional.of(TradeSignal.openLong(
                    currentIndex,
                    k.close(),
                    "Python BUY qty=" + qty
            ));
        }
        return Optional.empty();
    }

    /**
     * Called by BacktestEngine when a position is held.
     * If Python returned SELL, generate a close-long signal.
     */
    @Override
    public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators,
                                                   int currentIndex, TradeSignal openSignal) {
        PythonStrategyExecutionResult result = getOrCompute(currentIndex, true);
        if (result == null || !result.success()) {
            return Optional.empty();
        }
        if (result.isSell()) {
            KlineData k = klines.get(currentIndex);
            double qty = result.getQtyOptional().orElse(1.0);
            return Optional.of(TradeSignal.closeLong(
                    currentIndex,
                    k.close(),
                    "Python SELL qty=" + qty
            ));
        }
        return Optional.empty();
    }

    /**
     * Get the cached Python execution result for a bar index, or compute it on first access.
     *
     * <p>The ctx passed to Python contains:
     * <ul>
     *   <li>{@code params} — the strategy parameters</li>
     *   <li>{@code indicators} — indicator values at this bar, keyed by name</li>
     *   <li>{@code position} — current position info (qty=0 for open check, 1 for close check)</li>
     *   <li>{@code bar} — current kline data (open, high, low, close, volume)</li>
     * </ul>
     */
    private PythonStrategyExecutionResult getOrCompute(int barIndex, boolean hasPosition) {
        if (barIndex < 0 || barIndex >= barResults.length) {
            return null;
        }
        if (barResults[barIndex] != null) {
            return barResults[barIndex];
        }

        // Build context for Python on_bar(ctx)
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("params", params != null ? params : Map.of());
        ctx.put("indicators", buildIndicatorMap(barIndex));

        Map<String, Object> position = new HashMap<>();
        position.put("qty", hasPosition ? 1 : 0);
        ctx.put("position", position);

        KlineData k = klines.get(barIndex);
        Map<String, Object> bar = new HashMap<>();
        bar.put("open", k.open());
        bar.put("high", k.high());
        bar.put("low", k.low());
        bar.put("close", k.close());
        bar.put("volume", k.volume());
        if (k.date() != null) {
            bar.put("timestamp", k.date());
        }
        ctx.put("bar", bar);

        PythonStrategyExecutionResult result;
        if (daemon != null) {
            result = daemon.onBar(ctx);
        } else {
            result = runner.execute(code, entrypoint, ctx);
        }
        barResults[barIndex] = result;
        return result;
    }

    /**
     * Extract indicator values at a given bar index into a flat map suitable for Python ctx.
     */
    private Map<String, Object> buildIndicatorMap(int barIndex) {
        Map<String, Object> map = new HashMap<>();
        if (indicators == null) {
            return map;
        }

        // MA values
        if (indicators.ma() != null) {
            putIfNotNull(map, "ma_5", indicators.ma().getMa5At(barIndex));
            putIfNotNull(map, "ma_10", indicators.ma().getMa10At(barIndex));
            putIfNotNull(map, "ma_20", indicators.ma().getMa20At(barIndex));
            putIfNotNull(map, "ma_30", indicators.ma().getMa30At(barIndex));
            putIfNotNull(map, "ma_60", indicators.ma().getMa60At(barIndex));

            // Resolve ma_fast / ma_slow from params
            int fast = paramInt("fast", 5);
            int slow = paramInt("slow", 20);

            putIfNotNull(map, "ma_fast", getMaByPeriod(indicators.ma(), fast, barIndex));
            putIfNotNull(map, "ma_slow", getMaByPeriod(indicators.ma(), slow, barIndex));

            if (barIndex > 0) {
                putIfNotNull(map, "ma_fast_prev", getMaByPeriod(indicators.ma(), fast, barIndex - 1));
                putIfNotNull(map, "ma_slow_prev", getMaByPeriod(indicators.ma(), slow, barIndex - 1));
            }
        }

        // RSI values
        if (indicators.rsi() != null) {
            putIfNotNull(map, "rsi_6", indicators.rsi().getRsi6At(barIndex));
            putIfNotNull(map, "rsi_12", indicators.rsi().getRsi12At(barIndex));
            putIfNotNull(map, "rsi_24", indicators.rsi().getRsi24At(barIndex));
            if (barIndex > 0) {
                putIfNotNull(map, "rsi_12_prev", indicators.rsi().getRsi12At(barIndex - 1));
            }
        }

        // Bollinger values (+ prev for cross-band detection)
        if (indicators.boll() != null) {
            putIfNotNull(map, "boll_mid", indicators.boll().getMiddleAt(barIndex));
            putIfNotNull(map, "boll_upper", indicators.boll().getUpperAt(barIndex));
            putIfNotNull(map, "boll_lower", indicators.boll().getLowerAt(barIndex));
            if (barIndex > 0) {
                putIfNotNull(map, "boll_lower_prev", indicators.boll().getLowerAt(barIndex - 1));
                putIfNotNull(map, "boll_mid_prev", indicators.boll().getMiddleAt(barIndex - 1));
                putIfNotNull(map, "boll_upper_prev", indicators.boll().getUpperAt(barIndex - 1));
            }
        }

        // EMA-based MACD (computed from klines, parameterized by fast/slow/signal)
        // Lazy compute once; then read from caches per barIndex.
        ensureMacdComputed();
        if (emaFastCache != null && barIndex < emaFastCache.length) {
            putIfNotNull(map, "ema_fast", emaFastCache[barIndex]);
            putIfNotNull(map, "ema_slow", emaSlowCache[barIndex]);
            putIfNotNull(map, "dif", difCache[barIndex]);
            putIfNotNull(map, "dea", deaCache[barIndex]);
            if (barIndex > 0) {
                putIfNotNull(map, "ema_fast_prev", emaFastCache[barIndex - 1]);
                putIfNotNull(map, "ema_slow_prev", emaSlowCache[barIndex - 1]);
                putIfNotNull(map, "dif_prev", difCache[barIndex - 1]);
                putIfNotNull(map, "dea_prev", deaCache[barIndex - 1]);
            }
        }

        // Rolling extrema (Donchian-style) — computed from raw klines
        int lookback = paramInt("breakout_lookback_bars", 20);
        int exitLookback = paramInt("pullback_ma_period", 10);
        int hiStart = barIndex - lookback;
        if (hiStart >= 0) {
            double maxHigh = Double.NEGATIVE_INFINITY;
            for (int j = hiStart; j < barIndex; j++) {
                KlineData kj = klines.get(j);
                if (kj != null) {
                    maxHigh = Math.max(maxHigh, kj.high());
                }
            }
            if (maxHigh != Double.NEGATIVE_INFINITY) {
                map.put("rolling_high", maxHigh);
            }
        }
        int loStart = barIndex - exitLookback;
        if (loStart >= 0) {
            double minLow = Double.POSITIVE_INFINITY;
            for (int j = loStart; j < barIndex; j++) {
                KlineData kj = klines.get(j);
                if (kj != null) {
                    minLow = Math.min(minLow, kj.low());
                }
            }
            if (minLow != Double.POSITIVE_INFINITY) {
                map.put("rolling_low", minLow);
            }
        }
        if (barIndex > 0) {
            KlineData prev = klines.get(barIndex - 1);
            if (prev != null) {
                map.put("close_prev", prev.close());
            }
        }

        return map;
    }

    /**
     * Lazy-compute parameterized EMA arrays for MACD from klines.
     * Seed: SMA of first N closes at index N-1.
     * Recurrence: EMA_t = α * close_t + (1-α) * EMA_{t-1}, α = 2/(period+1).
     */
    private void ensureMacdComputed() {
        if (emaFastCache != null) return;

        int fast = paramInt("fast", 12);
        int slow = paramInt("slow", 26);
        int signal = paramInt("signal", 9);
        int n = klines.size();

        if (n == 0) return;

        emaFastCache = new Double[n];
        emaSlowCache = new Double[n];
        difCache = new Double[n];
        deaCache = new Double[n];

        computeEma(klines, fast, emaFastCache);
        computeEma(klines, slow, emaSlowCache);

        // dif = ema_fast - ema_slow, then EMA(dif, signal) = dea
        double[] difArr = new double[n];
        for (int i = 0; i < n; i++) {
            if (emaFastCache[i] != null && emaSlowCache[i] != null) {
                difArr[i] = emaFastCache[i] - emaSlowCache[i];
                difCache[i] = difArr[i];
            }
        }
        // Compute EMA of dif for dea (use double[] for primitive speed)
        computeEmaOnDouble(difArr, signal, deaCache, n);
    }

    private static void computeEma(List<KlineData> klines, int period, Double[] out) {
        int n = klines.size();
        if (period <= 0 || n == 0) return;
        double alpha = 2.0 / (period + 1.0);
        double sum = 0.0;
        int count = 0;
        // SMA seed at index period-1
        for (int i = 0; i < period && i < n; i++) {
            KlineData k = klines.get(i);
            if (k != null) {
                sum += k.close();
                count++;
            }
        }
        if (count == 0) return;
        double seed = sum / count;
        for (int i = 0; i < n; i++) {
            KlineData k = klines.get(i);
            if (k == null) continue;
            if (i < period - 1) {
                // before seed bar: store NaN-like null (will be omitted by putIfNotNull)
                continue;
            } else if (i == period - 1) {
                out[i] = seed;
            } else {
                double prev = out[i - 1] != null ? out[i - 1] : seed;
                out[i] = alpha * k.close() + (1.0 - alpha) * prev;
            }
        }
    }

    private static void computeEmaOnDouble(double[] values, int period, Double[] out, int n) {
        if (period <= 0 || n == 0) return;
        double alpha = 2.0 / (period + 1.0);
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < period && i < n; i++) {
            sum += values[i];
            count++;
        }
        if (count == 0) return;
        double seed = sum / count;
        for (int i = 0; i < n; i++) {
            if (i < period - 1) continue;
            else if (i == period - 1) out[i] = seed;
            else {
                double prev = out[i - 1] != null ? out[i - 1] : seed;
                out[i] = alpha * values[i] + (1.0 - alpha) * prev;
            }
        }
    }

    /** Read param as int with fallback default. */
    private int paramInt(String key, int fallback) {
        if (params == null) {
            return fallback;
        }
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /** Get MA value for an arbitrary period by mapping to the nearest pre-computed list. */
    private Double getMaByPeriod(MaData ma, int period, int index) {
        return switch (period) {
            case 5 -> ma.getMa5At(index);
            case 10 -> ma.getMa10At(index);
            case 20 -> ma.getMa20At(index);
            case 30 -> ma.getMa30At(index);
            case 60 -> ma.getMa60At(index);
            default -> null; // unsupported period — only 5/10/20/30/60 are pre-computed
        };
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}