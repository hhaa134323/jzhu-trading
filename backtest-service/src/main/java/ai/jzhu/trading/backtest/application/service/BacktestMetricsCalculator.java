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

        // ---- Per-bar mark-to-market equity accumulation ----
        // Three-state machine across bars using a "current position" pointer:
        //   FLAT (no position)       → equity[i] = equity[i-1]   (平推)
        //   HOLDING_OPEN_BAR (i == openIndex)  → mark using bar close vs entry, deduct open commission
        //   HOLDING_MID (openIndex < i < closeIndex) → mark using bar close vs prev bar close
        //   HOLDING_CLOSE_BAR (i == closeIndex) → mark using trade closePrice vs prev bar close, deduct close commission
        double effectiveCommission = commissionBps > 0 ? commissionBps / 10000.0 : feeRate;

        double grossProfit = 0.0;
        double grossLoss = 0.0;
        int totalHoldBars = 0;
        double totalHoldDays = 0.0;

        // Pre-index trades by open/close for O(1) lookup during scan
        Map<Integer, BacktestTradeDetail> tradeByOpenIndex = new HashMap<>();
        Map<Integer, BacktestTradeDetail> tradeByCloseIndex = new HashMap<>();
        for (BacktestTradeDetail t : closedTrades) {
            tradeByOpenIndex.put(t.openIndex(), t);
            tradeByCloseIndex.put(t.closeIndex(), t);

            // gross profit/loss in percentage terms — still trade-level (unchanged semantics)
            double open = t.openPrice();
            double close = t.closePrice();
            if (open <= 0) continue;
            int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
            double rawRet = sign * (close / open - 1.0);
            double leveragedRet = rawRet * leverage;
            double netRet = (1.0 + leveragedRet) * (1.0 - effectiveCommission) * (1.0 - effectiveCommission) - 1.0;
            if (netRet > 0) grossProfit += netRet;
            if (netRet < 0) grossLoss += netRet;

            totalHoldBars += Math.max(0, t.closeIndex() - t.openIndex());
            if (t.openDate() != null && t.closeDate() != null) {
                try {
                    LocalDate od = parseDate(t.openDate());
                    LocalDate cd = parseDate(t.closeDate());
                    if (od != null && cd != null) {
                        long days = ChronoUnit.DAYS.between(od, cd);
                        totalHoldDays += Math.max(0, days); // include 0-day holds
                    }
                } catch (DateTimeParseException ex) {
                    // ignore parse errors
                }
            }
        }

        // State-machine scan: track current active trade with a pointer
        BacktestTradeDetail currentTrade = null;

        for (int i = 0; i < n; i++) {
            double prevEquity = i == 0 ? equity[0] : equity[i - 1];
            equity[i] = prevEquity; // default flat bar (no position)

            // --- open bar: i is the openIndex of a trade ---
            if (tradeByOpenIndex.containsKey(i)) {
                BacktestTradeDetail t = tradeByOpenIndex.get(i);
                currentTrade = t;
                double entry = t.openPrice();            // engine already applied open slippage
                double barClose = klines.get(i).close();
                int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
                double barReturn = sign * (barClose / entry - 1.0) * leverage;
                double feeOpen = 1.0 - effectiveCommission;
                equity[i] = prevEquity * (1.0 + barReturn) * feeOpen;
            }
            // --- mid-holding bar: we have an active trade, and this is NOT its close bar ---
            else if (currentTrade != null && i < currentTrade.closeIndex()) {
                double prevClose = klines.get(i - 1).close();
                double curClose = klines.get(i).close();
                int sign = "SHORT".equalsIgnoreCase(currentTrade.direction()) ? -1 : 1;
                double barReturn = sign * (curClose / prevClose - 1.0) * leverage;
                equity[i] = prevEquity * (1.0 + barReturn);
            }
            // --- close bar: i == closeIndex of currentTrade ---
            else if (currentTrade != null && i == currentTrade.closeIndex()) {
                BacktestTradeDetail t = currentTrade;
                currentTrade = null; // position closed

                if (t.openIndex() == i) {
                    // Edge case: same-bar open+close (shouldn't happen with t+1 model, but guard)
                    double entry = t.openPrice();
                    double exit = t.closePrice();
                    int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
                    double rawRet = sign * (exit / entry - 1.0);
                    double leveragedRet = rawRet * leverage;
                    double feeFactor = (1.0 - effectiveCommission) * (1.0 - effectiveCommission);
                    equity[i] = prevEquity * (1.0 + leveragedRet) * feeFactor;
                } else {
                    double prevClose = klines.get(i - 1).close();
                    double exitPrice = t.closePrice();   // engine already applied close slippage
                    int sign = "SHORT".equalsIgnoreCase(t.direction()) ? -1 : 1;
                    double barReturn = sign * (exitPrice / prevClose - 1.0) * leverage;
                    double feeClose = 1.0 - effectiveCommission;
                    equity[i] = prevEquity * (1.0 + barReturn) * feeClose;
                }
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

            // Wall-clock CAGR: use calendar days / 365.0 instead of bar count * 252
            if (klines.size() >= 2) {
                LocalDate firstDate = parseDate(klines.get(0).date());
                LocalDate lastDate = parseDate(klines.get(klines.size() - 1).date());
                if (firstDate != null && lastDate != null) {
                    long calendarDays = ChronoUnit.DAYS.between(firstDate, lastDate);
                    if (calendarDays > 0) {
                        double years = calendarDays / 365.0;
                        annualReturnPct = (Math.pow(finalEquity / equity[0], 1.0 / years) - 1.0) * 100.0;
                    } else {
                        // Less than 1 calendar day → total return as fallback
                        annualReturnPct = totalReturnPct;
                    }
                }
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

    private static LocalDate parseDate(Object dateObj) {
        if (dateObj == null) return null;
        String str = dateObj.toString();
        try {
            return LocalDate.parse(str);
        } catch (DateTimeParseException e) {
            // Try ISO instant format: "2024-01-15T00:00:00"
            try {
                return java.time.LocalDateTime.parse(str).toLocalDate();
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
