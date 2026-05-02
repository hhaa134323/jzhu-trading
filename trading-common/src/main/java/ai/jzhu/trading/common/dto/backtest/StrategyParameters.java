package ai.jzhu.trading.common.dto.backtest;

public record StrategyParameters(
        Integer breakoutLookbackBars,
        Integer pullbackMaPeriod,
        Integer macdFast,
        Integer macdSlow,
        Integer macdSignal,
        Integer closeMaFast,
        Integer closeMaSlow
) {
}
