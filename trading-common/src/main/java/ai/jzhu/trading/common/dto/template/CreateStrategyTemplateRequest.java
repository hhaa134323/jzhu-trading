package ai.jzhu.trading.common.dto.template;

import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;

public record CreateStrategyTemplateRequest(
        String name,
        String description,
        String ownerId,
        StrategyDefinition initialDefinition,
        String changeNote
) {
}
