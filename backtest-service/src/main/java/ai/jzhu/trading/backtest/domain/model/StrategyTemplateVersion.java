package ai.jzhu.trading.backtest.domain.model;

import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;

import java.time.Instant;

public record StrategyTemplateVersion(
        String templateId,
        int versionNo,
        String sourceKind,
        StrategyDefinition definition,
        String changeNote,
        String createdBy,
        Instant createdAt
) {
}
