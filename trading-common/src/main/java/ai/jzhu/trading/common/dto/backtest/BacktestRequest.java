package ai.jzhu.trading.common.dto.backtest;

public record BacktestRequest(
        String symbol,
        String market,
        String period,
        String startDate,
        String endDate,
        String strategyId,
        StrategySource strategySource
) {
}
