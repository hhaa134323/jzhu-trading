package ai.jzhu.trading.web.domain.port;

import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;

import java.util.List;

public interface BacktestPort {

    SimpleBacktestResponse runBacktest(BacktestRequest request);

    List<StrategyInfoResponse> getStrategies();

    List<StrategyTemplateSummaryResponse> getStrategyTemplates();

    StrategyTemplateDetailResponse createStrategyTemplate(CreateStrategyTemplateRequest request);

    StrategyTemplateDetailResponse getStrategyTemplate(String templateId);

    List<StrategyTemplateVersionResponse> getStrategyTemplateVersions(String templateId);

    StrategyTemplateDetailResponse saveStrategyTemplateVersion(String templateId, SaveStrategyTemplateVersionRequest request);

    StrategyTemplateDetailResponse cloneStrategyTemplate(String templateId, CloneStrategyTemplateRequest request);
}
