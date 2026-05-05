package ai.jzhu.trading.backtest.application.service;

import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.common.dto.backtest.BacktestMetrics;
import ai.jzhu.trading.common.dto.backtest.RunParameters;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BacktestMetricsCalculatorTest {

    @Test
    public void testNoTrades() {
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 101, 101, 101, 101, 0L)
        );
        List<BacktestTradeDetail> trades = Collections.emptyList();
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m = calc.calculate(klines, trades);
        Assertions.assertEquals("no_trades", m.reason());
        Assertions.assertNull(m.totalReturnPct());
        Assertions.assertEquals(0, m.closedTrades());
    }

    @Test
    public void testSingleProfitTrade() {
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m = calc.calculate(klines, trades);
        Assertions.assertEquals(10.0, m.totalReturnPct(), 0.01);
        Assertions.assertEquals(100.0, m.winRatePct(), 0.01);
        Assertions.assertEquals(1, m.closedTrades());
    }

    @Test
    public void testSingleLossTrade() {
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 90, 90, 90, 90, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 90.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m = calc.calculate(klines, trades);
        Assertions.assertEquals(-10.0, m.totalReturnPct(), 0.01);
        Assertions.assertEquals(-10.0, m.maxDrawdownPct(), 0.01);
        Assertions.assertEquals(1, m.closedTrades());
    }

    @Test
    public void testMaxDrawdownScenario() {
        // construct 4 bars, 3 trades producing equity: 1.0 -> 1.5 -> 1.2 -> 1.4
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 150, 150, 150, 150, 0L),
                new KlineData("2023-01-03", 80, 80, 80, 80, 0L),
                new KlineData("2023-01-04", 116.6666, 116.6666, 116.6666, 116.6666, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 150.0, "LONG", "o", "c", true),
                new BacktestTradeDetail(1, 2, "2023-01-02", "2023-01-03", 100.0, 80.0, "LONG", "o", "c", true),
                new BacktestTradeDetail(2, 3, "2023-01-03", "2023-01-04", 100.0, 116.6666, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m = calc.calculate(klines, trades);
        // max drawdown should be (1.2/1.5 - 1) * 100 = -20.0
        Assertions.assertEquals(-20.0, m.maxDrawdownPct(), 0.01);
        Assertions.assertEquals(3, m.closedTrades());
    }

    // --- RunParameters acceptance tests ---

    @Test
    public void testDefaultParamsMatchesLegacy() {
        // With default RunParameters, results must match the legacy (no-params) call.
        // Use 1 win + 1 loss trade so profitFactor is non-null.
        // Kline close prices must match trade close prices for equity curve to align with
        // the legacy calculator (which uses kline close price for the index).
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L),
                new KlineData("2023-01-03", 110, 110, 110, 110, 0L),
                new KlineData("2023-01-04", 99, 99, 99, 99, 0L),
                new KlineData("2023-01-05", 99, 99, 99, 99, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true),
                new BacktestTradeDetail(2, 3, "2023-01-03", "2023-01-04", 100.0, 90.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics legacy = calc.calculate(klines, trades);
        BacktestMetrics withDefaults = calc.calculate(klines, trades, RunParameters.defaults());
        Assertions.assertEquals(legacy.totalReturnPct(), withDefaults.totalReturnPct(), 0.001);
        Assertions.assertEquals(legacy.maxDrawdownPct(), withDefaults.maxDrawdownPct(), 0.001);
        Assertions.assertEquals(legacy.sharpeRatio(), withDefaults.sharpeRatio(), 0.001);
        Assertions.assertEquals(legacy.winRatePct(), withDefaults.winRatePct(), 0.001);
        Assertions.assertEquals(legacy.closedTrades(), withDefaults.closedTrades());
        Assertions.assertEquals(legacy.profitFactor(), withDefaults.profitFactor(), 0.001);
        // new absolute fields
        Assertions.assertNotNull(withDefaults.finalEquity());
        Assertions.assertNotNull(withDefaults.totalPnl());
    }

    @Test
    public void testCapitalScalesFinalEquity() {
        // capital 100k vs 200k → totalReturnPct unchanged, finalEquity doubled
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m100 = calc.calculate(klines, trades, RunParameters.of(100_000.0, 1.0, 0.0));
        BacktestMetrics m200 = calc.calculate(klines, trades, RunParameters.of(200_000.0, 1.0, 0.0));
        Assertions.assertEquals(m100.totalReturnPct(), m200.totalReturnPct(), 0.001);
        Assertions.assertEquals(m100.finalEquity() * 2.0, m200.finalEquity(), 0.01);
        Assertions.assertEquals(m100.totalPnl() * 2.0, m200.totalPnl(), 0.01);
    }

    @Test
    public void testLeverageAmplifiesReturnAndRisk() {
        // leverage 1 vs 2 → totalReturnPct ≈ 2x, maxDrawdown ≈ 2x
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m1 = calc.calculate(klines, trades, RunParameters.of(100_000.0, 1.0, 0.0));
        BacktestMetrics m2 = calc.calculate(klines, trades, RunParameters.of(100_000.0, 2.0, 0.0));
        // with no fee, leverage 2 → 2x total return and 2x drawdown
        Assertions.assertEquals(m1.totalReturnPct() * 2.0, m2.totalReturnPct(), 0.01);
        Assertions.assertEquals(m1.maxDrawdownPct() * 2.0, m2.maxDrawdownPct(), 0.01);
    }

    @Test
    public void testFeeRateReducesReturn() {
        // fee 0 vs 0.001 → totalReturnPct decreases, absolute PnL decreases
        // Use 4 bars, 2 trades (one win, one loss) so profitFactor is calculable
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L),
                new KlineData("2023-01-03", 110, 110, 110, 110, 0L),
                new KlineData("2023-01-04", 99, 99, 99, 99, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true),
                new BacktestTradeDetail(2, 3, "2023-01-03", "2023-01-04", 110.0, 99.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics noFee = calc.calculate(klines, trades, RunParameters.of(100_000.0, 1.0, 0.0));
        BacktestMetrics withFee = calc.calculate(klines, trades, RunParameters.of(100_000.0, 1.0, 0.001));
        // totalReturnPct and profitFactor should both decrease when fee is applied
        Assertions.assertTrue(withFee.totalReturnPct() < noFee.totalReturnPct());
        Assertions.assertNotNull(withFee.profitFactor());
        Assertions.assertNotNull(noFee.profitFactor());
        Assertions.assertTrue(withFee.profitFactor() < noFee.profitFactor());
        // totalPnl should also decrease
        Assertions.assertTrue(withFee.totalPnl() < noFee.totalPnl());
    }

    @Test
    public void testLeverageWithFeeProducesCorrectSharpe() {
        // leverage + fee combination: both should affect metrics
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 90, 90, 90, 90, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 90.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics m = calc.calculate(klines, trades, RunParameters.of(100_000.0, 2.0, 0.001));
        // raw = -10%, leveraged = -20%, feeFactor = (1-0.001)^2 = 0.998001
        // netRet = (1-0.20)*0.998001 - 1 = -0.2015992 → totalReturnPct ≈ -20.16%
        Assertions.assertEquals(-20.16, m.totalReturnPct(), 0.02);
        Assertions.assertNotNull(m.finalEquity());
        // finalEquity = 100000 * (1 - 0.2015992) = 79840.08
        Assertions.assertEquals(79840.08, m.finalEquity(), 0.01);
        // totalPnl = finalEquity - capital
        Assertions.assertEquals(m.totalPnl(), m.finalEquity() - 100_000.0, 0.001);
    }

    @Test
    public void testCommissionBpsReducesReturn() {
        // commissionBps=10 (0.1%) should reduce return vs commissionBps=0
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        BacktestMetrics noCost = calc.calculate(klines, trades, RunParameters.zeroCost());
        BacktestMetrics withCost = calc.calculate(klines, trades, new RunParameters(100_000.0, 1.0, 0.0, 0.0, 10.0));
        // with 10 bps commission per side → feeFactor = (1 - 0.001)^2 = 0.998001
        // netRet = (1 + 0.10) * 0.998001 - 1 = 0.0978011 → 9.78% vs 10%
        Assertions.assertTrue(withCost.totalReturnPct() < noCost.totalReturnPct(),
                "Commission should reduce total return");
        Assertions.assertTrue(withCost.totalPnl() < noCost.totalPnl(),
                "Commission should reduce total PnL");
        // exact check: raw 10%, feeFactor 0.998001 → net 9.7801%
        Assertions.assertEquals(9.78, withCost.totalReturnPct(), 0.02);
    }

    @Test
    public void testCommissionBpsPreferredOverFeeRate() {
        // When both commissionBps and feeRate are provided, commissionBps takes priority
        List<KlineData> klines = Arrays.asList(
                new KlineData("2023-01-01", 100, 100, 100, 100, 0L),
                new KlineData("2023-01-02", 110, 110, 110, 110, 0L)
        );
        List<BacktestTradeDetail> trades = Arrays.asList(
                new BacktestTradeDetail(0, 1, "2023-01-01", "2023-01-02", 100.0, 110.0, "LONG", "o", "c", true)
        );
        BacktestMetricsCalculator calc = new BacktestMetricsCalculator();
        // feeRate=0.05 (5%) vs commissionBps=10 (0.1%) — commissionBps should be used
        BacktestMetrics m = calc.calculate(klines, trades, new RunParameters(100_000.0, 1.0, 0.05, 0.0, 10.0));
        // netRet = (1+0.10) * (1-0.001)^2 - 1 = 1.10 * 0.998001 - 1 = 0.0978011 → 9.78%
        Assertions.assertEquals(9.78, m.totalReturnPct(), 0.02, "commissionBps should be used, not feeRate");
    }
}
