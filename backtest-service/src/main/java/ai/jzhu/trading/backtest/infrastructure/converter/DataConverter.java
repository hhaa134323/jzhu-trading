package ai.jzhu.trading.backtest.infrastructure.converter;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataConverter {

    public List<KlineData> toKlineData(List<KlineResponse> klines) {
        if (klines == null) {
            return List.of();
        }
        return klines.stream()
                .map(k -> new KlineData(
                        k.date(),
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close(),
                        k.volume()
                ))
                .toList();
    }

    public IndicatorData toIndicatorData(IndicatorResponse response) {
        return IndicatorData.from(response);
    }
}
