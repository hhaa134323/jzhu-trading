package ai.jzhu.trading.common.dto.backtest;

public record StrategySource(
        StrategySourceType sourceType,
        String builtinStrategyId,
        String templateId,
        Integer templateVersion,
        StrategyDefinition draftDefinition
) {
}
