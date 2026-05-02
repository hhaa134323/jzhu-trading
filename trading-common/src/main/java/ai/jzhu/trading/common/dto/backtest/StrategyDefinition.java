package ai.jzhu.trading.common.dto.backtest;

public record StrategyDefinition(
        String engineType,
        String baseStrategyId,
        StrategyParameters parameters
) {
}
