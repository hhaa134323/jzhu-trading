package ai.jzhu.trading.marketdata.domain.port;

import ai.jzhu.trading.marketdata.domain.model.Kline;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KlineRepository {

    List<Kline> findByRange(
            String tableName,
            String symbol,
            String market,
            LocalDate startDate,
            LocalDate endDate
    );

    void saveAll(String tableName, String symbol, String market, List<Kline> klines);

    Optional<LocalDate> findLatestDate(String tableName, String symbol, String market);
}
