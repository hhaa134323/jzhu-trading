package ai.jzhu.trading.backtest.domain.model;

import java.time.Instant;

public record StrategyTemplate(
        String templateId,
        String name,
        String description,
        String ownerId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
