package ai.jzhu.trading.web.domain.port;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;

import java.util.List;

public interface IndicatorPort {

    IndicatorResponse calculate(
            List<KlineResponse> klines,
            String symbol,
            String market,
            String period
    );
}