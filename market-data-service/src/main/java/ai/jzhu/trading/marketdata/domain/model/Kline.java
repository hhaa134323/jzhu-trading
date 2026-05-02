package ai.jzhu.trading.marketdata.domain.model;

import java.time.LocalDate;

public record Kline(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
}
