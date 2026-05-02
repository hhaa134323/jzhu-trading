package ai.jzhu.trading.marketdata.infrastructure.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FmpHistoricalResponse(
        String symbol,
        String date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
}
