package ai.jzhu.trading.common.dto.template;

import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;

public record SaveStrategyTemplateVersionRequest(
        StrategyDefinition definition,
        String changeNote,
        String createdBy,
        String sourceKind,
        String code,
        String entrypoint
) {
}
