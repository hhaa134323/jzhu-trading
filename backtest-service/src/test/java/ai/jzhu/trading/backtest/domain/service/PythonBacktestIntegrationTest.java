package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.BollData;
import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.indicator.MacdData;
import ai.jzhu.strategy.domain.indicator.MaData;
import ai.jzhu.strategy.domain.indicator.RsiData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.backtest.application.service.BacktestMetricsCalculator;
import ai.jzhu.trading.common.dto.backtest.BacktestMetrics;
import ai.jzhu.trading.common.dto.backtest.RunParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that a PYTHON_CODE strategy can complete a
 * full backtest cycle: {@link BacktestEngine#run} with a
 * {@link PythonTradingStrategyAdapter} → trades → metrics.
 *
 * <p>These tests require Python 3 on the system (same as PythonStrategyRunnerTest).
 */
class PythonBacktestIntegrationTest {

    private static PythonStrategyRunner runner;
    private static BacktestEngine backtestEngine;
    private static BacktestMetricsCalculator metricsCalculator;

    /** Empty indicator data with lists sized to match 10-bar tests. */
    private static IndicatorData emptyIndicators(int barCount) {
        List<Double> empty = new ArrayList<>();
        for (int i = 0; i < barCount; i++) empty.add(null);
        return new IndicatorData(
                new MacdData(empty, empty, empty),
                new MaData(empty, empty, empty, empty, empty),
                new RsiData(empty, empty, empty),
                new BollData(empty, empty, empty)
        );
    }

    @BeforeAll
    static void setUp() {
        runner = new PythonStrategyRunner();
        backtestEngine = new BacktestEngine();
        metricsCalculator = new BacktestMetricsCalculator();
    }

    /**
     * Helper: make a list of daily klines with consistent price pattern.
     */
    private static List<KlineData> makeKlines(double... closes) {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            double o = c - 1.0;
            double h = c + 2.0;
            double l = c - 2.0;
            list.add(new KlineData(
                    "2024-01-" + (i + 1),
                    o, h, l, c,
                    1000L + i * 100
            ));
        }
        return list;
    }

    // ================================================================
    //  Test 1: Simple BUY strategy — buys on first bar, then HOLDS
    // ================================================================

    @Test
    void testPythonStrategyBuyAndHold() {
        String code = """
                def on_bar(ctx):
                    if ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": 100}
                    return {"action": "HOLD"}
                """;

        List<KlineData> klines = makeKlines(100, 102, 101, 103, 105, 104, 106, 108, 107, 110);
        IndicatorData indicators = emptyIndicators(klines.size());
        Map<String, Object> params = Map.of("qty", 100);

        TradingStrategy strategy = new PythonTradingStrategyAdapter(
                "test-py#v1", "Python Buy Test", code, "on_bar",
                klines, indicators, params, runner
        );

        RunParameters runParams = RunParameters.zeroCost();
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);

        assertNotNull(trades, "trades should not be null");
        assertFalse(trades.isEmpty(), "Should have at least one trade (BUY at bar 0, force-close at end)");

        // First trade should be an open
        BacktestTradeDetail first = trades.get(0);
        assertTrue(first.openPrice() > 0, "Open price should be positive");

        // Check that metrics are computed
        BacktestMetrics metrics = metricsCalculator.calculate(klines, trades, runParams);
        assertNotNull(metrics, "metrics should not be null");
        assertTrue(metrics.closedTrades() >= 1, "Should have at least 1 closed trade");
        assertNotNull(metrics.totalReturnPct(), "totalReturnPct should be computed");
        assertNotNull(metrics.finalEquity(), "finalEquity should be computed");
        assertNotNull(metrics.totalPnl(), "totalPnl should be computed");
    }

    // ================================================================
    //  Test 2: BUY then SELL — produces both open and close signals
    // ================================================================

    @Test
    void testPythonStrategyBuyThenSell() {
        String code = """
                def on_bar(ctx):
                    if ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": 100}
                    return {"action": "SELL", "qty": ctx["position"]["qty"]}
                """;

        List<KlineData> klines = makeKlines(100, 102, 101, 103);
        IndicatorData indicators = emptyIndicators(klines.size());
        Map<String, Object> params = Map.of();

        TradingStrategy strategy = new PythonTradingStrategyAdapter(
                "test-py#v2", "Python Buy Sell Test", code, "on_bar",
                klines, indicators, params, runner
        );

        RunParameters runParams = RunParameters.zeroCost();
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);

        assertNotNull(trades, "trades should not be null");
        assertFalse(trades.isEmpty(), "Should have trades from BUY then SELL + force-close");

        // We expect at least one closed trade where BUY was followed by a SELL signal.
        // Bar 0: no position → BUY signal at bar 0 → fill at bar 1 open
        // Bar 1: has position → SELL signal at bar 1 → fill at bar 2 open
        // So there should be a closed trade with openIndex=1, closeIndex=2
        boolean foundBuySell = trades.stream().anyMatch(t ->
                t.closed() && t.closeIndex() > t.openIndex()
        );
        assertTrue(foundBuySell, "Should have at least one properly closed BUY→SELL trade");

        // Metrics should be computed with valid values
        BacktestMetrics metrics = metricsCalculator.calculate(klines, trades, runParams);
        assertNotNull(metrics, "metrics should not be null");
        assertTrue(metrics.closedTrades() >= 1, "Should have at least 1 closed trade");
        assertNotNull(metrics.totalReturnPct(), "totalReturnPct should be computed");
    }

    // ================================================================
    //  Test 3: Python error — invalid code should not produce fake metrics
    // ================================================================

    @Test
    void testPythonStrategySyntaxErrorReturnsError() {
        String code = """
                def on_bar(ctx)
                    return {"action": "BUY"}
                """;

        List<KlineData> klines = makeKlines(100, 102, 101);
        IndicatorData indicators = emptyIndicators(klines.size());
        Map<String, Object> params = Map.of();

        TradingStrategy strategy = new PythonTradingStrategyAdapter(
                "test-py#err", "Python Error Test", code, "on_bar",
                klines, indicators, params, runner
        );

        RunParameters runParams = RunParameters.zeroCost();
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);

        // Syntax error means the adapter returns empty signals for all bars
        // → no trades should be generated
        assertTrue(trades == null || trades.isEmpty(),
                "Syntax error strategy should produce no trades");

        // Metrics calculator should return "no_trades" reason
        if (trades == null) trades = List.of();
        BacktestMetrics metrics = metricsCalculator.calculate(klines, trades, runParams);
        assertEquals("no_trades", metrics.reason(),
                "Should report no_trades, not fake metrics");
        assertNull(metrics.totalReturnPct(),
                "totalReturnPct should be null when no trades");
    }

    // ================================================================
    //  Test 4: Non-existent entrypoint
    // ================================================================

    @Test
    void testPythonStrategyMissingEntrypoint() {
        String code = """
                def my_func(ctx):
                    return {"action": "BUY"}
                """;

        List<KlineData> klines = makeKlines(100, 102, 101);
        IndicatorData indicators = emptyIndicators(klines.size());
        Map<String, Object> params = Map.of();

        TradingStrategy strategy = new PythonTradingStrategyAdapter(
                "test-py#noentry", "No Entrypoint", code, "on_bar",
                klines, indicators, params, runner
        );

        RunParameters runParams = RunParameters.zeroCost();
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);

        // Entrypoint not found → no signals → no trades
        assertTrue(trades == null || trades.isEmpty(),
                "Missing entrypoint strategy should produce no trades");
    }

    // ================================================================
    //  Test 5: Python strategy accessing indicators
    // ================================================================

    @Test
    void testPythonStrategyUsesIndicators() {
        String code = """
                def on_bar(ctx):
                    ma = ctx["indicators"].get("ma_10")
                    if ma is not None and ma > ctx["bar"]["close"]:
                        return {"action": "BUY", "qty": 100}
                    if ctx["position"].get("qty", 0) > 0:
                        return {"action": "SELL", "qty": ctx["position"]["qty"]}
                    return {"action": "HOLD"}
                """;

        List<KlineData> klines = makeKlines(100, 102, 101, 103, 105);
        int n = klines.size();

        // Provide ma_10 values: high so that at bar 2, ma_10 > close triggers BUY
        List<Double> ma10 = new ArrayList<>();
        for (int i = 0; i < n; i++) ma10.add(102.0); // ma10 is 102 across all bars
        List<Double> empty = new ArrayList<>();
        for (int i = 0; i < n; i++) empty.add(null);

        IndicatorData indicators = new IndicatorData(
                new MacdData(empty, empty, empty),
                new MaData(empty, ma10, empty, empty, empty),
                new RsiData(empty, empty, empty),
                new BollData(empty, empty, empty)
        );

        Map<String, Object> params = Map.of();

        TradingStrategy strategy = new PythonTradingStrategyAdapter(
                "test-py#indicator", "Python Indicator Test", code, "on_bar",
                klines, indicators, params, runner
        );

        RunParameters runParams = RunParameters.zeroCost();
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);

        // There should be at least one trade (the BUY signal)
        assertNotNull(trades, "trades should not be null");
        assertFalse(trades.isEmpty(), "Should have at least one trade when indicator triggers BUY");

        BacktestMetrics metrics = metricsCalculator.calculate(klines, trades, runParams);
        assertNotNull(metrics, "metrics should not be null");
        assertTrue(metrics.closedTrades() >= 1, "Should have closed trades with indicator logic");
    }

    // ================================================================
    //  Test 6: BUILTIN / JAVA_PARAMS regression — PythonStrategyAdapter
    //          does not interfere with existing strategies
    // ================================================================

    @Test
    void testBuiltinStrategyStillWorks() {
        // Verify that a dummy TradingStrategy implementation still functions
        // when PythonStrategyAdapter exists in the classpath
        TradingStrategy neverOpen = new TradingStrategy() {
            @Override public String getId() { return "neverOpen"; }
            @Override public String getName() { return "Never Open"; }
            @Override public String getDescription() { return ""; }
            @Override
            public java.util.Optional<ai.jzhu.strategy.domain.model.TradeSignal> checkOpenSignal(
                    List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
                return java.util.Optional.empty();
            }
            @Override
            public java.util.Optional<ai.jzhu.strategy.domain.model.TradeSignal> checkCloseSignal(
                    List<KlineData> klines, IndicatorData indicators, int currentIndex,
                    ai.jzhu.strategy.domain.model.TradeSignal openSignal) {
                return java.util.Optional.empty();
            }
        };

        List<KlineData> klines = makeKlines(100, 101, 102);
        IndicatorData indicators = emptyIndicators(klines.size());
        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, neverOpen);

        assertTrue(trades.isEmpty(), "Never-open strategy should produce no trades");
    }
}