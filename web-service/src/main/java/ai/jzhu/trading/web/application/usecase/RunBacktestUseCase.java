package ai.jzhu.trading.web.application.usecase;

import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;
import ai.jzhu.trading.web.domain.port.BacktestPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunBacktestUseCase {

    private final BacktestPort backtestPort;

    public RunBacktestUseCase(BacktestPort backtestPort) {
        this.backtestPort = backtestPort;
    }

    public SimpleBacktestResponse run(BacktestRequest request) {
        return backtestPort.runBacktest(request);
    }

    public List<StrategyInfoResponse> getStrategies() {
        return backtestPort.getStrategies();
    }

    public List<StrategyTemplateSummaryResponse> getStrategyTemplates() {
        return backtestPort.getStrategyTemplates();
    }

    public StrategyTemplateDetailResponse createStrategyTemplate(CreateStrategyTemplateRequest request) {
        return backtestPort.createStrategyTemplate(request);
    }

    public StrategyTemplateDetailResponse getStrategyTemplate(String templateId) {
        return backtestPort.getStrategyTemplate(templateId);
    }

    public List<StrategyTemplateVersionResponse> getStrategyTemplateVersions(String templateId) {
        return backtestPort.getStrategyTemplateVersions(templateId);
    }

    public StrategyTemplateDetailResponse saveStrategyTemplateVersion(String templateId, SaveStrategyTemplateVersionRequest request) {
        return backtestPort.saveStrategyTemplateVersion(templateId, request);
    }

    public StrategyTemplateDetailResponse cloneStrategyTemplate(String templateId, CloneStrategyTemplateRequest request) {
        return backtestPort.cloneStrategyTemplate(templateId, request);
    }
}
