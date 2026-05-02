package ai.jzhu.trading.web.presentation.controller;

import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;
import ai.jzhu.trading.web.application.usecase.RunBacktestUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/web")
public class BacktestController {

    private final RunBacktestUseCase runBacktestUseCase;

    public BacktestController(RunBacktestUseCase runBacktestUseCase) {
        this.runBacktestUseCase = runBacktestUseCase;
    }

    @PostMapping("/backtest/run")
    public SimpleBacktestResponse runBacktest(@RequestBody BacktestRequest request) {
        return runBacktestUseCase.run(request);
    }

    @GetMapping("/strategies")
    public List<StrategyInfoResponse> getStrategies() {
        return runBacktestUseCase.getStrategies();
    }

    @GetMapping("/strategy-templates")
    public List<StrategyTemplateSummaryResponse> getStrategyTemplates() {
        return runBacktestUseCase.getStrategyTemplates();
    }

    @PostMapping("/strategy-templates")
    public StrategyTemplateDetailResponse createStrategyTemplate(@RequestBody CreateStrategyTemplateRequest request) {
        return runBacktestUseCase.createStrategyTemplate(request);
    }

    @GetMapping("/strategy-templates/{templateId}")
    public StrategyTemplateDetailResponse getStrategyTemplate(@PathVariable String templateId) {
        return runBacktestUseCase.getStrategyTemplate(templateId);
    }

    @GetMapping("/strategy-templates/{templateId}/versions")
    public List<StrategyTemplateVersionResponse> getStrategyTemplateVersions(@PathVariable String templateId) {
        return runBacktestUseCase.getStrategyTemplateVersions(templateId);
    }

    @PostMapping("/strategy-templates/{templateId}/versions")
    public StrategyTemplateDetailResponse saveStrategyTemplateVersion(
            @PathVariable String templateId,
            @RequestBody SaveStrategyTemplateVersionRequest request
    ) {
        return runBacktestUseCase.saveStrategyTemplateVersion(templateId, request);
    }

    @PostMapping("/strategy-templates/{templateId}/clone")
    public StrategyTemplateDetailResponse cloneStrategyTemplate(
            @PathVariable String templateId,
            @RequestBody CloneStrategyTemplateRequest request
    ) {
        return runBacktestUseCase.cloneStrategyTemplate(templateId, request);
    }
}
