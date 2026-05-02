package ai.jzhu.trading.indicator.domain.port;

import ai.jzhu.trading.indicator.domain.model.IndicatorValues;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndicatorRepository {

    Optional<IndicatorValues> findCachedDaily(String symbol, String market, List<LocalDate> dates);

    void saveDaily(String symbol, String market, List<LocalDate> dates, IndicatorValues values);
}
