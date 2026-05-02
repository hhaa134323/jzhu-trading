package ai.jzhu.trading.web.application.usecase;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.KlineWithIndicatorsResponse;
import ai.jzhu.trading.common.dto.indicator.BollResult;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import ai.jzhu.trading.common.dto.indicator.MacdResult;
import ai.jzhu.trading.common.dto.indicator.MaResult;
import ai.jzhu.trading.common.dto.indicator.RsiResult;
import ai.jzhu.trading.web.domain.port.IndicatorPort;
import ai.jzhu.trading.web.domain.port.MarketDataPort;
import ai.jzhu.trading.web.presentation.exception.DownstreamServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class GetKlineUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetKlineUseCase.class);

    private final MarketDataPort marketDataPort;
    private final IndicatorPort indicatorPort;

    public GetKlineUseCase(MarketDataPort marketDataPort, IndicatorPort indicatorPort) {
        this.marketDataPort = marketDataPort;
        this.indicatorPort = indicatorPort;
    }

    public KlineWithIndicatorsResponse execute(
            String symbol,
            String market,
            String period,
            String startDate,
            String endDate
    ) {
        List<KlineResponse> klines = marketDataPort.getKline(symbol, market, period, startDate, endDate);
        IndicatorResponse indicators;
        try {
            indicators = indicatorPort.calculate(klines, symbol, market, period);
        } catch (DownstreamServiceUnavailableException ex) {
            log.warn("indicator-service unavailable, fallback to empty indicators: symbol={}, market={}, period={}, reason={}",
                    symbol, market, period, ex.getMessage());
            indicators = buildEmptyIndicators(klines.size());
        }
        return new KlineWithIndicatorsResponse(klines, indicators, klines.size());
    }

    private IndicatorResponse buildEmptyIndicators(int size) {
        List<Double> emptySeries = Collections.nCopies(size, null);
        return new IndicatorResponse(
                new MacdResult(emptySeries, emptySeries, emptySeries),
                new MaResult(emptySeries, emptySeries, emptySeries, emptySeries, emptySeries),
                new RsiResult(emptySeries, emptySeries, emptySeries),
                new BollResult(emptySeries, emptySeries, emptySeries)
        );
    }
}
