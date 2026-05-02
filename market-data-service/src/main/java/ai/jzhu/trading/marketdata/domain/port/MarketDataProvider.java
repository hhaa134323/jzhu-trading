package ai.jzhu.trading.marketdata.domain.port;

import ai.jzhu.trading.marketdata.domain.model.Kline;

import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {

    List<Kline> fetchKlines(String symbol, String market, LocalDate startDate, LocalDate endDate);
}
