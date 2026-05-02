package ai.jzhu.strategy.domain.indicator;

import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;

import java.util.Objects;

public record IndicatorData(
        MacdData macd,
        MaData ma,
        RsiData rsi,
        BollData boll
) {

    public static IndicatorData from(IndicatorResponse response) {
        Objects.requireNonNull(response, "indicator response must not be null");
        return new IndicatorData(
                MacdData.from(response.macd()),
                MaData.from(response.ma()),
                RsiData.from(response.rsi()),
                BollData.from(response.boll())
        );
    }
}