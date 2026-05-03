package ai.jzhu.trading.backtest.application.usecase;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.backtest.domain.port.IndicatorPort;
import ai.jzhu.trading.backtest.domain.port.MarketDataPort;
import ai.jzhu.trading.backtest.domain.service.BacktestEngine;
import ai.jzhu.trading.backtest.infrastructure.converter.DataConverter;
import ai.jzhu.trading.backtest.infrastructure.repository.StrategyTemplateRepository;
import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.BacktestTradeDetailResponse;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.BacktestMetrics;
import ai.jzhu.trading.backtest.application.service.BacktestMetricsCalculator;
import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.backtest.StrategySource;
import ai.jzhu.trading.common.dto.backtest.StrategySourceType;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunBacktestUseCase {

    private final MarketDataPort marketDataPort;
    private final IndicatorPort indicatorPort;
    private final BacktestEngine backtestEngine;
    private final DataConverter dataConverter;
    private final StrategyTemplateRepository strategyTemplateRepository;
    private final List<TradingStrategy> strategies;

    public RunBacktestUseCase(
            MarketDataPort marketDataPort,
            IndicatorPort indicatorPort,
            BacktestEngine backtestEngine,
            DataConverter dataConverter,
            StrategyTemplateRepository strategyTemplateRepository,
            List<TradingStrategy> strategies
    ) {
        this.marketDataPort = marketDataPort;
        this.indicatorPort = indicatorPort;
        this.backtestEngine = backtestEngine;
        this.dataConverter = dataConverter;
        this.strategyTemplateRepository = strategyTemplateRepository;
        this.strategies = strategies;
    }

    public SimpleBacktestResponse run(BacktestRequest request) {
        validateRequest(request);

        String symbol = request.symbol().trim().toUpperCase();
        String market = normalize(request.market(), "us");
        String period = normalize(request.period(), "daily");
        String strategyId = resolveStrategyId(request);

        TradingStrategy strategy = strategies.stream()
                .filter(s -> s.getId().equals(strategyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported strategyId: " + strategyId));

        List<KlineResponse> klineResponses = marketDataPort.getKline(symbol, market, period, request.startDate(), request.endDate());
        List<KlineData> klines = dataConverter.toKlineData(klineResponses);
        IndicatorResponse indicatorResponse = indicatorPort.calculate(klineResponses, symbol, market, period);
        IndicatorData indicators = dataConverter.toIndicatorData(indicatorResponse);

        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy);
        List<BacktestTradeDetailResponse> tradeResponses = trades.stream()
            .map(this::toTradeDetailResponse)
            .toList();

        // compute metrics based on klines + trades
        BacktestMetricsCalculator calculator = new BacktestMetricsCalculator();
        BacktestMetrics metrics = calculator.calculate(klines, trades);

        return new SimpleBacktestResponse(
                symbol,
                strategy.getId(),
                strategy.getName(),
            tradeResponses.size(),
            tradeResponses,
            metrics
        );
    }

    public List<StrategyInfoResponse> listStrategies() {
        return strategies.stream()
                .sorted((left, right) -> left.getId().compareToIgnoreCase(right.getId()))
                .map(s -> new StrategyInfoResponse(s.getId(), s.getName(), s.getDescription()))
                .toList();
    }

    private void validateRequest(BacktestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.strategyId() == null || request.strategyId().isBlank()) {
            StrategySource source = request.strategySource();
            boolean hasSource = source != null && source.sourceType() != null;
            if (!hasSource) {
                throw new IllegalArgumentException("strategyId is required");
            }
        }
    }

    private String resolveStrategyId(BacktestRequest request) {
        StrategySource source = request.strategySource();
        if (source == null || source.sourceType() == null) {
            return request.strategyId().trim();
        }

        StrategySourceType sourceType = source.sourceType();
        if (sourceType == StrategySourceType.BUILTIN) {
            if (source.builtinStrategyId() != null && !source.builtinStrategyId().isBlank()) {
                return source.builtinStrategyId().trim();
            }
            if (request.strategyId() != null && !request.strategyId().isBlank()) {
                return request.strategyId().trim();
            }
            throw new IllegalArgumentException("builtinStrategyId is required for BUILTIN source");
        }

        if (sourceType == StrategySourceType.DRAFT) {
            StrategyDefinition definition = source.draftDefinition();
            if (definition == null || definition.baseStrategyId() == null || definition.baseStrategyId().isBlank()) {
                throw new IllegalArgumentException("draftDefinition.baseStrategyId is required for DRAFT source");
            }
            return definition.baseStrategyId().trim();
        }

        if (sourceType == StrategySourceType.TEMPLATE_VERSION) {
            if (source.templateId() == null || source.templateId().isBlank()) {
                throw new IllegalArgumentException("templateId is required for TEMPLATE_VERSION source");
            }
            if (source.templateVersion() == null || source.templateVersion() <= 0) {
                throw new IllegalArgumentException("templateVersion must be greater than 0 for TEMPLATE_VERSION source");
            }
            StrategyDefinition definition = strategyTemplateRepository
                    .findVersion(source.templateId().trim(), source.templateVersion())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Template version not found: " + source.templateId() + "#" + source.templateVersion()
                    ))
                    .definition();
            if (definition == null || definition.baseStrategyId() == null || definition.baseStrategyId().isBlank()) {
                throw new IllegalArgumentException("Template version missing baseStrategyId");
            }
            return definition.baseStrategyId().trim();
        }

        throw new IllegalArgumentException("Unsupported strategy source type: " + sourceType);
    }

    private BacktestTradeDetailResponse toTradeDetailResponse(BacktestTradeDetail trade) {
        return new BacktestTradeDetailResponse(
                trade.openIndex(),
                trade.closeIndex(),
                trade.openDate(),
                trade.closeDate(),
                trade.openPrice(),
                trade.closePrice(),
                trade.direction(),
                trade.openReason(),
                trade.closeReason(),
                trade.closed()
        );
    }

    private String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase();
    }
}
