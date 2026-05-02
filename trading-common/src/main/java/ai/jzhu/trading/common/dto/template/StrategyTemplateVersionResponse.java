package ai.jzhu.trading.common.dto.template;

import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;

import java.time.Instant;

public record StrategyTemplateVersionResponse(
        String templateId,
        int versionNo,
        String sourceKind,
        StrategyDefinition definition,
        String changeNote,
        String createdBy,
        Instant createdAt
) {
}
