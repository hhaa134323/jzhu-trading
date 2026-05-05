package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.common.dto.backtest.RunParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BacktestEngine fill price model:
 * - entry/exit should use next bar open (not same-bar close/high)
 * - slippage should be applied correctly based on direction
 * - signals at the last bar without a next bar should be skipped
 */
public class BacktestEngineTest {

    // Minimal empty indicator data for tests that don't check indicator logic
    private static final IndicatorData EMPTY_INDICATORS = new IndicatorData(
            new ai.jzhu.strategy.domain.indicator.MacdData(List.of(), List.of(), List.of()),
            new ai.jzhu.strategy.domain.indicator.MaData(List.of(), List.of(), List.of(), List.of(), List.of()),
            new ai.jzhu.strategy.domain.indicator.RsiData(List.of(), List.of(), List.of()),
            new ai.jzhu.strategy.domain.indicator.BollData(List.of(), List.of(), List.of())
    );

    // A simple long-only strategy that opens at index 1 (when close > 90) and closes at index 3
    private static final TradingStrategy OPEN_AT_1_CLOSE_AT_3 = new TradingStrategy() {
        @Override
        public String getId() { return "testLong"; }
        @Override
        public String getName() { return "Test Long"; }
        @Override
        public String getDescription() { return "Open at index 1, close at index 3"; }
        @Override
        public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
            if (currentIndex == 1 && !hasPosition) {
                return Optional.of(TradeSignal.openLong(1, 100.0, "test open"));
            }
            return Optional.empty();
        }
        @Override
        public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
            if (currentIndex == 3) {
                return Optional.of(TradeSignal.closeLong(3, 110.0, "test close"));
            }
            return Optional.empty();
        }
    };

    // A strategy that never opens (to test edge cases)
    private static final TradingStrategy NEVER_OPEN = new TradingStrategy() {
        @Override
        public String getId() { return "neverOpen"; }
        @Override
        public String getName() { return "Never Open"; }
        @Override
        public String getDescription() { return "Never opens a position"; }
        @Override
        public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
            return Optional.empty();
        }
        @Override
        public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
            return Optional.empty();
        }
    };

    // A strategy that tries to open at the last bar (should be skipped)
    private static final TradingStrategy OPEN_AT_LAST_BAR = new TradingStrategy() {
        @Override
        public String getId() { return "openLast"; }
        @Override
        public String getName() { return "Open Last"; }
        @Override
        public String getDescription() { return "Opens at the last bar"; }
        @Override
        public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
            if (currentIndex == klines.size() - 1 && !hasPosition) {
                return Optional.of(TradeSignal.openLong(currentIndex, 200.0, "last bar open"));
            }
            return Optional.empty();
        }
        @Override
        public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
            return Optional.empty();
        }
    };

    private static List<KlineData> makeKlines(double... opens) {
        return java.util.stream.IntStream.range(0, opens.length)
                .mapToObj(i -> new KlineData(
                        "2023-01-" + (i + 1),
                        opens[i],
                        opens[i] + 5,
                        opens[i] - 5,
                        opens[i] + 1, // close slightly above open
                        1000L
                ))
                .toList();
    }

    @Test
    public void testEntryPriceIsNextBarOpen() {
        // 5 bars: opens = [100, 101, 102, 103, 104]
        // Open signal at index 1 → fill at index 2 open = 102
        // Close signal at index 3 → fill at index 4 open = 104
        List<KlineData> klines = makeKlines(100, 101, 102, 103, 104);
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3);

        assertEquals(1, trades.size(), "Should have 1 trade");
        BacktestTradeDetail t = trades.get(0);
        assertEquals(102.0, t.openPrice(), 0.001, "entry should be next bar open (index 2)");
        assertEquals(104.0, t.closePrice(), 0.001, "exit should be next bar open (index 4)");
        assertEquals(2, t.openIndex(), "openIndex should be fill bar index");
        assertEquals(4, t.closeIndex(), "closeIndex should be fill bar index");
    }

    @Test
    public void testEntryPriceNotEqualToSignalBarCloseOrHigh() {
        // Verify that entry is NOT the close/high of the signal bar.
        // Use opens where bar1 close != bar2 open to avoid coincidental equality.
        // bar 0 open=100 close=106, bar 1 open=110 close=116, bar 2 open=120 close=126, ...
        List<KlineData> klines = List.of(
                new KlineData("2023-01-01", 100, 105, 95, 106, 1000L),
                new KlineData("2023-01-02", 110, 115, 105, 116, 1000L),
                new KlineData("2023-01-03", 120, 125, 115, 126, 1000L),
                new KlineData("2023-01-04", 130, 135, 125, 136, 1000L),
                new KlineData("2023-01-05", 140, 145, 135, 146, 1000L)
        );
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3);

        BacktestTradeDetail t = trades.get(0);
        // Signal at index 1 → fill at index 2 open = 120
        // bar 1 close = 116, bar 1 high = 115
        double signalBarClose = klines.get(1).close();
        double signalBarHigh = klines.get(1).high();
        assertEquals(120.0, t.openPrice(), 0.001, "entry should be next bar open (index 2)");
        assertNotEquals(signalBarClose, t.openPrice(), 0.001, "entry should not equal signal bar close");
        assertNotEquals(signalBarHigh, t.openPrice(), 0.001, "entry should not equal signal bar high");
    }

    @Test
    public void testSlippageLongOpenClose() {
        // 5 bars, opens = [100, 101, 102, 103, 104]
        // With slippage 10 bps (0.1%):
        // LONG open at index 2 open=102 → 102 * 1.001 = 102.102
        // LONG close at index 4 open=104 → 104 * 0.999 = 103.896
        List<KlineData> klines = makeKlines(100, 101, 102, 103, 104);
        BacktestEngine engine = new BacktestEngine();
        RunParameters params = new RunParameters(100_000.0, 1.0, 0.0, 10.0, 0.0);
        List<BacktestTradeDetail> trades = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3, params);

        assertEquals(1, trades.size());
        BacktestTradeDetail t = trades.get(0);
        assertEquals(102.0 * 1.001, t.openPrice(), 0.001, "entry with 10bps slippage");
        assertEquals(104.0 * 0.999, t.closePrice(), 0.001, "exit with 10bps slippage");
    }

    @Test
    public void testZeroSlippageMatchesNoSlippage() {
        List<KlineData> klines = makeKlines(100, 101, 102, 103, 104);
        BacktestEngine engine = new BacktestEngine();
        RunParameters params = new RunParameters(100_000.0, 1.0, 0.0, 0.0, 0.0);
        List<BacktestTradeDetail> withZero = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3, params);
        List<BacktestTradeDetail> without = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3);

        assertEquals(withZero.get(0).openPrice(), without.get(0).openPrice(), 0.001);
        assertEquals(withZero.get(0).closePrice(), without.get(0).closePrice(), 0.001);
    }

    @Test
    public void testNoTradesWhenStrategyNeverOpens() {
        List<KlineData> klines = makeKlines(100, 101, 102);
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(klines, EMPTY_INDICATORS, NEVER_OPEN);
        assertTrue(trades.isEmpty(), "Should have no trades");
    }

    @Test
    public void testSignalAtLastBarIsSkipped() {
        // Strategy tries to open at last bar → no next bar → signal skipped
        List<KlineData> klines = makeKlines(100, 101);
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(klines, EMPTY_INDICATORS, OPEN_AT_LAST_BAR);
        assertTrue(trades.isEmpty(), "Signal at last bar should be skipped");
    }

    @Test
    public void testEmptyKlinesReturnsEmpty() {
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(List.of(), EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3);
        assertTrue(trades.isEmpty());
    }

    @Test
    public void testNullKlinesReturnsEmpty() {
        BacktestEngine engine = new BacktestEngine();
        List<BacktestTradeDetail> trades = engine.run(null, EMPTY_INDICATORS, OPEN_AT_1_CLOSE_AT_3);
        assertTrue(trades.isEmpty());
    }
}