package ai.jzhu.trading.common.dto.template;

import java.time.Instant;
import java.util.List;

public record StrategyTemplateDetailResponse(
        String templateId,
        String name,
        String description,
        String ownerId,
        String status,
        Integer latestVersion,
        Instant createdAt,
        Instant updatedAt,
        List<StrategyTemplateVersionResponse> versions
) {
}
