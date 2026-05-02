package ai.jzhu.trading.common.dto.indicator;

public record IndicatorResponse(
        MacdResult macd,
        MaResult ma,
        RsiResult rsi,
        BollResult boll
) {
}
