package ai.jzhu.trading.common.dto.indicator;

import ai.jzhu.trading.common.dto.KlineResponse;

import java.util.List;

public record IndicatorRequest(
        List<KlineResponse> klines,
        String symbol,
        String market,
        String period
) {
}
