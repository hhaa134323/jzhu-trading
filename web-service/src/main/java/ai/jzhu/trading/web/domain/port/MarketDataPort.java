package ai.jzhu.trading.web.domain.port;

import ai.jzhu.trading.common.dto.KlineResponse;

import java.util.List;

public interface MarketDataPort {

    List<KlineResponse> getKline(
            String symbol,
            String market,
            String period,
            String startDate,
            String endDate
    );
}
