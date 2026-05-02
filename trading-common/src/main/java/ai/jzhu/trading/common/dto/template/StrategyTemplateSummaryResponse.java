package ai.jzhu.trading.common.dto.template;

import java.time.Instant;

public record StrategyTemplateSummaryResponse(
        String templateId,
        String name,
        String description,
        String ownerId,
        String status,
        Integer latestVersion,
        Instant updatedAt
) {
}
