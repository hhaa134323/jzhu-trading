package ai.jzhu.trading.backtest.presentation.controller;

import ai.jzhu.trading.backtest.application.usecase.RunBacktestUseCase;
import ai.jzhu.trading.backtest.application.usecase.StrategyTemplateUseCase;
import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final RunBacktestUseCase runBacktestUseCase;
    private final StrategyTemplateUseCase strategyTemplateUseCase;

    public BacktestController(RunBacktestUseCase runBacktestUseCase, StrategyTemplateUseCase strategyTemplateUseCase) {
        this.runBacktestUseCase = runBacktestUseCase;
        this.strategyTemplateUseCase = strategyTemplateUseCase;
    }

    @PostMapping("/run")
    public SimpleBacktestResponse run(@RequestBody BacktestRequest request) {
        return runBacktestUseCase.run(request);
    }

    @GetMapping("/strategies")
    public List<StrategyInfoResponse> listStrategies() {
        return runBacktestUseCase.listStrategies();
    }

    @GetMapping("/strategy-templates")
    public List<StrategyTemplateSummaryResponse> listTemplates() {
        return strategyTemplateUseCase.listTemplates();
    }

    @PostMapping("/strategy-templates")
    public StrategyTemplateDetailResponse createTemplate(@RequestBody CreateStrategyTemplateRequest request) {
        return strategyTemplateUseCase.createTemplate(request);
    }

    @GetMapping("/strategy-templates/{templateId}")
    public StrategyTemplateDetailResponse getTemplate(@PathVariable String templateId) {
        return strategyTemplateUseCase.getTemplateDetail(templateId);
    }

    @GetMapping("/strategy-templates/{templateId}/versions")
    public List<StrategyTemplateVersionResponse> listTemplateVersions(@PathVariable String templateId) {
        return strategyTemplateUseCase.listVersions(templateId);
    }

    @PostMapping("/strategy-templates/{templateId}/versions")
    public StrategyTemplateDetailResponse saveTemplateVersion(
            @PathVariable String templateId,
            @RequestBody SaveStrategyTemplateVersionRequest request
    ) {
        return strategyTemplateUseCase.saveVersion(templateId, request);
    }

    @PostMapping("/strategy-templates/{templateId}/clone")
    public StrategyTemplateDetailResponse cloneTemplate(
            @PathVariable String templateId,
            @RequestBody CloneStrategyTemplateRequest request
    ) {
        return strategyTemplateUseCase.cloneTemplate(templateId, request);
    }
}
