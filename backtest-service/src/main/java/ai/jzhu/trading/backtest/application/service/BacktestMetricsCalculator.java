package ai.jzhu.trading.backtest.application.service;

import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.common.dto.backtest.BacktestMetrics;
import ai.jzhu.trading.common.dto.backtest.RunParameters;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BacktestMetricsCalculator {

    public BacktestMetrics calculate(List<KlineData> klines, List<BacktestTradeDetail> trades) {
        return calculate(klines, trades, null);
    }

    public BacktestMetrics calculate(List<KlineData> klines, List<BacktestTradeDetail> trades, RunParameters runParams) {
        if (klines == null || klines.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "insufficient_data", null, null);
        }

        if (trades == null || trades.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "no_trades", null, null);
        }

        RunParameters params = runParams != null ? runParams : RunParameters.defaults();
        double capital = params.capitalOrDefault();
        double leverage = params.leverageOrDefault();
        double feeRate = params.feeRateOrDefault();
        double commissionBps = params.commissionBpsOrDefault();

        List<BacktestTradeDetail> closedTrades = new ArrayList<>();
        for (BacktestTradeDetail t : trades) {
            if (t.closed() && t.closeIndex() >= 0) {
                closedTrades.add(t);
            }
        }

        if (closedTrades.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "no_trades", null, null);
        }

        int n = klines.size();
        double[] equity = new double[n];
        equity[0] = capital;

        Map<Integer, List<Double>> returnsAtIndex = new HashMap<>();
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        int totalHoldBars = 0;
        double totalHoldDays = 0.0;

        for (BacktestTradeDetail t : closedTrades) {
            double open = t.openPrice();
            double close = t.closePrice();
            if (open <= 0) continue;
            int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
            double rawRet = sign * (close / open - 1.0);
            // leverage amplifies return
            double leveragedRet = rawRet * leverage;
            // Commission deducted per side — use commissionBps if >0, else legacy feeRate
            double effectiveCommission = commissionBps > 0 ? commissionBps / 10000.0 : feeRate;
            double feeFactor = (1.0 - effectiveCommission) * (1.0 - effectiveCommission);
            double netRet = (1.0 + leveragedRet) * feeFactor - 1.0;
            returnsAtIndex.computeIfAbsent(t.closeIndex(), k -> new ArrayList<>()).add(netRet);

            // gross profit/loss in percentage terms (for profitFactor consistency)
            if (netRet > 0) grossProfit += netRet;
            if (netRet < 0) grossLoss += netRet;

            totalHoldBars += Math.max(0, t.closeIndex() - t.openIndex());
            if (t.openDate() != null && t.closeDate() != null) {
                try {
                    LocalDate od = LocalDate.parse(t.openDate());
                    LocalDate cd = LocalDate.parse(t.closeDate());
                    long days = ChronoUnit.DAYS.between(od, cd);
                    if (days > 0) totalHoldDays += days;
                } catch (DateTimeParseException ex) {
                    // ignore parse errors
                }
            }
        }

        // accumulate equity curve
        for (int i = 1; i < n; i++) {
            equity[i] = equity[i - 1];
            List<Double> list = returnsAtIndex.get(i);
            if (list != null && !list.isEmpty()) {
                double mult = 1.0;
                for (Double r : list) {
                    mult *= (1.0 + r);
                }
                equity[i] = equity[i - 1] * mult;
            }
        }

        double finalEquity = equity[n - 1];
        double totalReturnPct = (finalEquity / capital - 1.0) * 100.0;
        double totalPnl = finalEquity - capital;

        // max drawdown (negative percent)
        double runningMax = equity[0];
        double maxDrawdown = 0.0;
        for (int i = 0; i < n; i++) {
            if (equity[i] > runningMax) {
                runningMax = equity[i];
            } else {
                double dd = (equity[i] / runningMax - 1.0) * 100.0;
                if (dd < maxDrawdown) {
                    maxDrawdown = dd;
                }
            }
        }

        // daily returns series from equity
        List<Double> series = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double prev = equity[i - 1];
            double cur = equity[i];
            if (prev <= 0) continue;
            series.add(cur / prev - 1.0);
        }

        Double sharpe = null;
        Double annualReturnPct = null;
        Double volatilityPct = null;

        if (series.size() >= 2) {
            double sum = 0.0;
            for (double r : series) sum += r;
            double mean = sum / series.size();
            double var = 0.0;
            for (double r : series) var += (r - mean) * (r - mean);
            double denom = Math.max(1, series.size() - 1);
            double std = Math.sqrt(var / denom);
            double annualFactor = Math.sqrt(252.0);
            if (std > 0.0) {
                sharpe = mean / std * annualFactor;
            }
            volatilityPct = std * annualFactor * 100.0;

            double days = (double) n;
            if (days > 0) {
                annualReturnPct = (Math.pow(finalEquity / equity[0], 252.0 / days) - 1.0) * 100.0;
            }
        }

        int closedCount = closedTrades.size();
        double winRatePct = 0.0;
        int wins = 0;
        for (BacktestTradeDetail t : closedTrades) {
            double open = t.openPrice();
            double close = t.closePrice();
            if (open <= 0) continue;
            int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
            double rawRet = sign * (close / open - 1.0);
            double leveragedRet = rawRet * leverage;
            double effectiveCommission = commissionBps > 0 ? commissionBps / 10000.0 : feeRate;
            double feeFactor = (1.0 - effectiveCommission) * (1.0 - effectiveCommission);
            double netRet = (1.0 + leveragedRet) * feeFactor - 1.0;
            if (netRet > 0) wins++;
        }
        if (closedCount > 0) {
            winRatePct = ((double) wins / closedCount) * 100.0;
        }

        Double profitFactor = null;
        double grossLossAbs = Math.abs(grossLoss);
        if (grossLossAbs > 0.0) {
            profitFactor = grossProfit / grossLossAbs;
        }

        Double avgHoldBars = null;
        Double avgHoldDays = null;
        if (closedCount > 0) {
            avgHoldBars = ((double) totalHoldBars) / closedCount;
            avgHoldDays = totalHoldDays / closedCount;
        }

        return new BacktestMetrics(
                roundOrNull(totalReturnPct),
                roundOrNull(maxDrawdown),
                sharpe == null ? null : roundOrNull(sharpe),
                annualReturnPct == null ? null : roundOrNull(annualReturnPct),
                volatilityPct == null ? null : roundOrNull(volatilityPct),
                roundOrNull(winRatePct),
                profitFactor == null ? null : roundOrNull(profitFactor),
                closedCount,
                avgHoldBars == null ? null : roundOrNull(avgHoldBars),
                avgHoldDays == null ? null : roundOrNull(avgHoldDays),
                null,
                roundOrNull(finalEquity),
                roundOrNull(totalPnl)
        );
    }

    private static Double roundOrNull(Double v) {
        if (v == null) return null;
        return Math.round(v * 100.0) / 100.0 / 1.0;
    }

    private static Double roundOrNull(double v) {
        return Math.round(v * 100.0) / 100.0 / 1.0;
    }
}
