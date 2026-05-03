package ai.jzhu.trading.backtest.application.service;

import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.common.dto.backtest.BacktestMetrics;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BacktestMetricsCalculator {

    public BacktestMetrics calculate(List<KlineData> klines, List<BacktestTradeDetail> trades) {
        if (klines == null || klines.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "insufficient_data");
        }

        if (trades == null || trades.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "no_trades");
        }

        List<BacktestTradeDetail> closedTrades = new ArrayList<>();
        for (BacktestTradeDetail t : trades) {
            if (t.closed() && t.closeIndex() >= 0) {
                closedTrades.add(t);
            }
        }

        if (closedTrades.isEmpty()) {
            return new BacktestMetrics(null, null, null, null, null, null, null, 0, null, null, "no_trades");
        }

        int n = klines.size();
        double[] equity = new double[n];
        equity[0] = 1.0;

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
            double ret = sign * (close / open - 1.0); // decimal return
            returnsAtIndex.computeIfAbsent(t.closeIndex(), k -> new ArrayList<>()).add(ret);

            if (ret > 0) grossProfit += ret;
            if (ret < 0) grossLoss += ret; // negative

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
        double totalReturnPct = (finalEquity - 1.0) * 100.0;

        // max drawdown (negative percent)
        double runningMax = equity[0];
        double maxDrawdown = 0.0; // will be negative or zero
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
            double ret = sign * (close / open - 1.0);
            if (ret > 0) wins++;
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
                null
        );
    }

    private static Double roundOrNull(Double v) {
        if (v == null) return null;
        return Math.round(v * 100.0) / 100.0 / 1.0; // round to 2 decimals
    }

    private static Double roundOrNull(double v) {
        return Math.round(v * 100.0) / 100.0 / 1.0;
    }
}
