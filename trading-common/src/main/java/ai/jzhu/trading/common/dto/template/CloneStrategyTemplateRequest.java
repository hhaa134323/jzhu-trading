package ai.jzhu.trading.common.dto.template;

public record CloneStrategyTemplateRequest(
        String name,
        String description,
        String ownerId,
        Integer fromVersion,
        String changeNote
) {
}
