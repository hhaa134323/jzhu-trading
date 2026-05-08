package ai.jzhu.trading.backtest.application.usecase;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.backtest.domain.model.StrategyTemplateVersion;
import ai.jzhu.trading.backtest.domain.port.IndicatorPort;
import ai.jzhu.trading.backtest.domain.port.MarketDataPort;
import ai.jzhu.trading.backtest.domain.service.BacktestEngine;
import ai.jzhu.trading.backtest.domain.service.PythonStrategyRunner;
import ai.jzhu.trading.backtest.domain.service.PythonTradingStrategyAdapter;
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
import ai.jzhu.trading.common.dto.backtest.RunParameters;
import ai.jzhu.trading.common.dto.backtest.StrategySourceType;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RunBacktestUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunBacktestUseCase.class);
    private static final String SOURCE_KIND_PYTHON_CODE = "PYTHON_CODE";

    private final MarketDataPort marketDataPort;
    private final IndicatorPort indicatorPort;
    private final BacktestEngine backtestEngine;
    private final DataConverter dataConverter;
    private final StrategyTemplateRepository strategyTemplateRepository;
    private final List<TradingStrategy> strategies;
    private final PythonStrategyRunner pythonStrategyRunner;

    public RunBacktestUseCase(
            MarketDataPort marketDataPort,
            IndicatorPort indicatorPort,
            BacktestEngine backtestEngine,
            DataConverter dataConverter,
            StrategyTemplateRepository strategyTemplateRepository,
            List<TradingStrategy> strategies,
            PythonStrategyRunner pythonStrategyRunner
    ) {
        this.marketDataPort = marketDataPort;
        this.indicatorPort = indicatorPort;
        this.backtestEngine = backtestEngine;
        this.dataConverter = dataConverter;
        this.strategyTemplateRepository = strategyTemplateRepository;
        this.strategies = strategies;
        this.pythonStrategyRunner = pythonStrategyRunner;
    }

    public SimpleBacktestResponse run(BacktestRequest request) {
        validateRequest(request);

        String symbol = request.symbol().trim().toUpperCase();
        String market = normalize(request.market(), "us");
        String period = normalize(request.period(), "daily");

        List<KlineResponse> klineResponses = marketDataPort.getKline(symbol, market, period, request.startDate(), request.endDate());
        List<KlineData> klines = dataConverter.toKlineData(klineResponses);
        IndicatorResponse indicatorResponse = indicatorPort.calculate(klineResponses, symbol, market, period);
        IndicatorData indicators = dataConverter.toIndicatorData(indicatorResponse);

        RunParameters runParams = request.runParameters() != null ? request.runParameters() : RunParameters.defaults();

        // Resolve the TradingStrategy — may be a built-in bean or a PYTHON_CODE adapter
        TradingStrategy strategy = resolveTradingStrategy(request, klines, indicators);

        List<BacktestTradeDetail> trades = backtestEngine.run(klines, indicators, strategy, runParams);
        List<BacktestTradeDetailResponse> tradeResponses = trades.stream()
            .map(t -> toTradeDetailResponse(t, runParams))
            .toList();

        // compute metrics based on klines + trades
        BacktestMetricsCalculator calculator = new BacktestMetricsCalculator();
        BacktestMetrics metrics = calculator.calculate(klines, trades, runParams);

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

    /**
     * Resolve the {@link TradingStrategy} implementation for a given request.
     *
     * <p>For BUILTIN sources, returns a Spring-managed bean by ID.
     * For DRAFT sources, returns the base strategy bean.
     * For TEMPLATE_VERSION sources:
     * <ul>
     *   <li>If the version's sourceKind is PYTHON_CODE, builds a
     *       {@link PythonTradingStrategyAdapter} using the stored code/entrypoint.</li>
     *   <li>Otherwise, falls back to the baseStrategyId bean.</li>
     * </ul>
     */
    private TradingStrategy resolveTradingStrategy(BacktestRequest request,
                                                    List<KlineData> klines,
                                                    IndicatorData indicators) {
        StrategySource source = request.strategySource();
        if (source == null || source.sourceType() == null) {
            String sid = request.strategyId().trim();
            return findBuiltinStrategyById(sid);
        }

        StrategySourceType sourceType = source.sourceType();
        if (sourceType == StrategySourceType.BUILTIN) {
            String sid = source.builtinStrategyId() != null && !source.builtinStrategyId().isBlank()
                    ? source.builtinStrategyId().trim()
                    : request.strategyId().trim();
            return findBuiltinStrategyById(sid);
        }

        if (sourceType == StrategySourceType.DRAFT) {
            StrategyDefinition definition = source.draftDefinition();
            if (definition == null || definition.baseStrategyId() == null || definition.baseStrategyId().isBlank()) {
                throw new IllegalArgumentException("draftDefinition.baseStrategyId is required for DRAFT source");
            }
            return findBuiltinStrategyById(definition.baseStrategyId().trim());
        }

        if (sourceType == StrategySourceType.TEMPLATE_VERSION) {
            if (source.templateId() == null || source.templateId().isBlank()) {
                throw new IllegalArgumentException("templateId is required for TEMPLATE_VERSION source");
            }
            if (source.templateVersion() == null || source.templateVersion() <= 0) {
                throw new IllegalArgumentException("templateVersion must be greater than 0 for TEMPLATE_VERSION source");
            }

            String templateId = source.templateId().trim();
            int versionNo = source.templateVersion();

            StrategyTemplateVersion version = strategyTemplateRepository
                    .findVersion(templateId, versionNo)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Template version not found: " + templateId + "#" + versionNo
                    ));

            StrategyDefinition definition = version.definition();

            // PYTHON_CODE path: build adapter with code + runner
            if (SOURCE_KIND_PYTHON_CODE.equals(version.sourceKind())) {
                return buildPythonTradingStrategy(templateId, versionNo, definition, klines, indicators);
            }

            // JAVA_PARAMS / BUILTIN fallback: use baseStrategyId bean
            if (definition == null || definition.baseStrategyId() == null || definition.baseStrategyId().isBlank()) {
                throw new IllegalArgumentException("Template version missing baseStrategyId");
            }
            return findBuiltinStrategyById(definition.baseStrategyId().trim());
        }

        throw new IllegalArgumentException("Unsupported strategy source type: " + sourceType);
    }

    /**
     * Build a {@link PythonTradingStrategyAdapter} from a PYTHON_CODE template version.
     */
    private TradingStrategy buildPythonTradingStrategy(String templateId, int versionNo,
                                                        StrategyDefinition definition,
                                                        List<KlineData> klines,
                                                        IndicatorData indicators) {
        String code = definition.code();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("PYTHON_CODE template version missing code");
        }
        String entrypoint = definition.entrypoint() != null && !definition.entrypoint().isBlank()
                ? definition.entrypoint()
                : "on_bar";

        Map<String, Object> params = new HashMap<>();
        if (definition.parameters() != null) {
            // Convert StrategyParameters record fields to a flat map for Python
            if (definition.parameters().breakoutLookbackBars() != null) {
                params.put("breakout_lookback_bars", definition.parameters().breakoutLookbackBars());
            }
            if (definition.parameters().pullbackMaPeriod() != null) {
                params.put("pullback_ma_period", definition.parameters().pullbackMaPeriod());
            }
            if (definition.parameters().macdFast() != null) {
                params.put("macd_fast", definition.parameters().macdFast());
            }
            if (definition.parameters().macdSlow() != null) {
                params.put("macd_slow", definition.parameters().macdSlow());
            }
            if (definition.parameters().macdSignal() != null) {
                params.put("macd_signal", definition.parameters().macdSignal());
            }
            if (definition.parameters().closeMaFast() != null) {
                params.put("fast", definition.parameters().closeMaFast());
            }
            if (definition.parameters().closeMaSlow() != null) {
                params.put("slow", definition.parameters().closeMaSlow());
            }
        }

        // When definition.parameters is null (common for PYTHON_CODE saves from UI),
        // extract param defaults from Python code so buildIndicatorMap resolves correct MA periods.
        if (!params.containsKey("fast") || !params.containsKey("slow")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("ctx\\[\"params\"\\]\\.get\\(\"(\\w+)\",\\s*(\\d+)\\s*\\)")
                .matcher(code);
            while (m.find()) {
                String key = m.group(1);
                if (!params.containsKey(key)) {
                    params.put(key, Integer.parseInt(m.group(2)));
                }
            }
        }

        String strategyId = templateId + "#v" + versionNo;
        String strategyName = "Python#" + templateId + "#v" + versionNo;

        log.info("Building PythonTradingStrategyAdapter: id={}, entrypoint={}, bars={}",
                strategyId, entrypoint, klines.size());

        return new PythonTradingStrategyAdapter(
                strategyId,
                strategyName,
                code,
                entrypoint,
                klines,
                indicators,
                params,
                pythonStrategyRunner
        );
    }

    private TradingStrategy findBuiltinStrategyById(String strategyId) {
        return strategies.stream()
                .filter(s -> s.getId().equals(strategyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported strategyId: " + strategyId));
    }

    private BacktestTradeDetailResponse toTradeDetailResponse(BacktestTradeDetail trade, RunParameters runParams) {
        Double fee = null;
        if (runParams != null && trade.closed()) {
            double capital = runParams.capitalOrDefault();
            double leverage = runParams.leverageOrDefault();
            double commissionBps = runParams.commissionBpsOrDefault();
            if (commissionBps > 0) {
                // commission based on actual notional per side (open notional + close notional)
                double openNotional = trade.openPrice() * (capital / trade.openPrice()) * leverage;
                double closeNotional = trade.closePrice() * (capital / trade.openPrice()) * leverage;
                double commission = (openNotional + closeNotional) * commissionBps / 10000.0;
                fee = Math.round(commission * 100.0) / 100.0;
            }
        }
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
                trade.closed(),
                fee
        );
    }

    private String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase();
    }
}
