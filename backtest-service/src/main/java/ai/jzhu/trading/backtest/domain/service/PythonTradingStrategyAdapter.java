package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.Direction;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * triggers a single Python subprocess invocation.
 *
 * <p>This avoids re-calling on_bar multiple times for the same bar, which would
 * happen if we called PythonStrategyRunner inside each checkOpenSignal/checkCloseSignal
 * invocation (BacktestEngine calls one OR the other per bar, but not both — however
 * the adapter still pre-computes once to be safe and simple).
 */
public class PythonTradingStrategyAdapter implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PythonTradingStrategyAdapter.class);

    private final String id;
    private final String name;
    private final String code;
    private final String entrypoint;
    private final List<KlineData> klines;
    private final IndicatorData indicators;
    private final Map<String, Object> params;
    private final PythonStrategyRunner runner;

    /** Cached on_bar result per bar index. Indexed by bar position. */
    private final PythonStrategyExecutionResult[] barResults;

    /**
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
        this.barResults = new PythonStrategyExecutionResult[klines.size()];
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

        PythonStrategyExecutionResult result = runner.execute(code, entrypoint, ctx);
        log.debug("Python strategy bar {}: success={}, action={}, qty={}, error={}",
                barIndex, result.success(), result.action(), result.qty(), result.errorMessage());
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
        }

        // RSI values
        if (indicators.rsi() != null) {
            putIfNotNull(map, "rsi_6", indicators.rsi().getRsi6At(barIndex));
            putIfNotNull(map, "rsi_12", indicators.rsi().getRsi12At(barIndex));
            putIfNotNull(map, "rsi_24", indicators.rsi().getRsi24At(barIndex));
        }

        // MACD values
        if (indicators.macd() != null) {
            putIfNotNull(map, "macd_dif", indicators.macd().getDifAt(barIndex));
            putIfNotNull(map, "macd_dea", indicators.macd().getDeaAt(barIndex));
            putIfNotNull(map, "macd_histogram", indicators.macd().getMacdAt(barIndex));
        }

        // Bollinger values
        if (indicators.boll() != null) {
            putIfNotNull(map, "boll_mid", indicators.boll().getMiddleAt(barIndex));
            putIfNotNull(map, "boll_upper", indicators.boll().getUpperAt(barIndex));
            putIfNotNull(map, "boll_lower", indicators.boll().getLowerAt(barIndex));
        }

        return map;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}