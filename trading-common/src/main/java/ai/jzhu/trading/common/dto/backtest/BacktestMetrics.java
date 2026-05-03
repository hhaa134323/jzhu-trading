package ai.jzhu.trading.common.dto.backtest;

public record BacktestMetrics(
        Double totalReturnPct,
        Double maxDrawdownPct,
        Double sharpeRatio,
        Double annualReturnPct,
        Double volatilityPct,
        Double winRatePct,
        Double profitFactor,
        Integer closedTrades,
        Double averageHoldBars,
        Double averageHoldDays,
        String reason
) {
}
