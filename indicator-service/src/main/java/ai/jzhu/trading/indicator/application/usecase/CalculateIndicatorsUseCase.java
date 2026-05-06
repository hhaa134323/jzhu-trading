package ai.jzhu.trading.indicator.application.usecase;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.indicator.BollResult;
import ai.jzhu.trading.common.dto.indicator.IndicatorRequest;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import ai.jzhu.trading.common.dto.indicator.MaResult;
import ai.jzhu.trading.common.dto.indicator.MacdResult;
import ai.jzhu.trading.common.dto.indicator.RsiResult;
import ai.jzhu.trading.indicator.domain.calculator.BollCalculator;
import ai.jzhu.trading.indicator.domain.calculator.MaCalculator;
import ai.jzhu.trading.indicator.domain.calculator.MacdCalculator;
import ai.jzhu.trading.indicator.domain.calculator.RsiCalculator;
import ai.jzhu.trading.indicator.domain.model.IndicatorValues;
import ai.jzhu.trading.indicator.domain.port.IndicatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class CalculateIndicatorsUseCase {

    private static final Logger log = LoggerFactory.getLogger(CalculateIndicatorsUseCase.class);

    private final IndicatorRepository indicatorRepository;
    private final MacdCalculator macdCalculator;
    private final MaCalculator maCalculator;
    private final RsiCalculator rsiCalculator;
    private final BollCalculator bollCalculator;

    public CalculateIndicatorsUseCase(
            IndicatorRepository indicatorRepository,
            MacdCalculator macdCalculator,
            MaCalculator maCalculator,
            RsiCalculator rsiCalculator,
            BollCalculator bollCalculator
    ) {
        this.indicatorRepository = indicatorRepository;
        this.macdCalculator = macdCalculator;
        this.maCalculator = maCalculator;
        this.rsiCalculator = rsiCalculator;
        this.bollCalculator = bollCalculator;
    }

    public IndicatorResponse execute(IndicatorRequest request) {
        validateRequest(request);

        String symbol = request.symbol().trim().toUpperCase();
        String market = request.market().trim().toLowerCase();
        String period = request.period().trim().toLowerCase();

        List<KlineResponse> klines = request.klines();
        List<LocalDate> dates = klines.stream().map(k -> LocalDate.parse(k.date())).toList();

        if ("daily".equals(period)) {
            try {
                var cached = indicatorRepository.findCachedDaily(symbol, market, dates);
                if (cached.isPresent()) {
                    log.info("Indicator cache hit: symbol={}, market={}, period={}, size={}",
                            symbol, market, period, klines.size());
                    return toResponse(cached.get());
                }
            } catch (RuntimeException ex) {
                log.warn("Indicator cache read failed, fallback to direct calculation: symbol={}, market={}, period={}, reason={}",
                        symbol, market, period, ex.getMessage());
            }
        }

        log.info("Indicator recalculation: symbol={}, market={}, period={}, size={}",
                symbol, market, period, klines.size());

        List<Double> closes = klines.stream().map(KlineResponse::close).toList();
        MacdResult macd = macdCalculator.calculate(closes);
        MaResult ma = maCalculator.calculate(closes);
        RsiResult rsi = rsiCalculator.calculate(closes);
        BollResult boll = bollCalculator.calculate(closes);

        IndicatorValues values = new IndicatorValues(
                macd.difList(),
                macd.deaList(),
                macd.macdList(),
                ma.ma5List(),
                ma.ma10List(),
                ma.ma20List(),
                ma.ma30List(),
                ma.ma60List(),
                rsi.rsi6List(),
                rsi.rsi12List(),
                rsi.rsi24List(),
                boll.upperList(),
                boll.middleList(),
                boll.lowerList()
        );

        if ("daily".equals(period)) {
            try {
                indicatorRepository.saveDaily(symbol, market, dates, values);
            } catch (RuntimeException ex) {
                log.warn("Indicator cache write failed, skip persistence: symbol={}, market={}, period={}, reason={}",
                        symbol, market, period, ex.getMessage());
            }
        }

        return new IndicatorResponse(
                new MacdResult(round2List(macd.difList()), round2List(macd.deaList()), round2List(macd.macdList())),
                new MaResult(round2List(ma.ma5List()), round2List(ma.ma10List()), round2List(ma.ma20List()), round2List(ma.ma30List()), round2List(ma.ma60List())),
                new RsiResult(round2List(rsi.rsi6List()), round2List(rsi.rsi12List()), round2List(rsi.rsi24List())),
                new BollResult(round2List(boll.upperList()), round2List(boll.middleList()), round2List(boll.lowerList()))
        );
    }

    private IndicatorResponse toResponse(IndicatorValues values) {
        return new IndicatorResponse(
                new MacdResult(round2List(values.difList()), round2List(values.deaList()), round2List(values.macdList())),
                new MaResult(round2List(values.ma5List()), round2List(values.ma10List()), round2List(values.ma20List()), round2List(values.ma30List()), round2List(values.ma60List())),
                new RsiResult(round2List(values.rsi6List()), round2List(values.rsi12List()), round2List(values.rsi24List())),
                new BollResult(round2List(values.upperList()), round2List(values.middleList()), round2List(values.lowerList()))
        );
    }

    /** Round2 at the JSON serialization boundary only. DB stores full precision. */
    private static List<Double> round2List(List<Double> input) {
        if (input == null) return null;
        List<Double> out = new ArrayList<>(input.size());
        for (Double v : input) {
            if (v == null) {
                out.add(null);
            } else {
                out.add(Math.round(v * 100.0) / 100.0);
            }
        }
        return out;
    }

    private void validateRequest(IndicatorRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.klines() == null || request.klines().isEmpty()) {
            throw new IllegalArgumentException("klines must not be empty");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.market() == null || request.market().isBlank()) {
            throw new IllegalArgumentException("market is required");
        }
        if (request.period() == null || request.period().isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
    }
}
