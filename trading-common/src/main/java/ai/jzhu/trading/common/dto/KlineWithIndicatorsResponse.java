package ai.jzhu.trading.common.dto;

import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;

import java.util.List;

public record KlineWithIndicatorsResponse(
        List<KlineResponse> klines,
        IndicatorResponse indicators,
        int totalCount
) {
}