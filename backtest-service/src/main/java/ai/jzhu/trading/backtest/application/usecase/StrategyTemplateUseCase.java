package ai.jzhu.trading.backtest.application.usecase;

import ai.jzhu.trading.backtest.domain.model.StrategyTemplate;
import ai.jzhu.trading.backtest.domain.model.StrategyTemplateVersion;
import ai.jzhu.trading.backtest.infrastructure.repository.StrategyTemplateRepository;
import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class StrategyTemplateUseCase {

    private static final String SOURCE_KIND_JAVA_PARAMS = "JAVA_PARAMS";

    private final StrategyTemplateRepository strategyTemplateRepository;

    public StrategyTemplateUseCase(StrategyTemplateRepository strategyTemplateRepository) {
        this.strategyTemplateRepository = strategyTemplateRepository;
    }

    public List<StrategyTemplateSummaryResponse> listTemplates() {
        return strategyTemplateRepository.listSummaries().stream()
                .map(item -> new StrategyTemplateSummaryResponse(
                        item.templateId(),
                        item.name(),
                        item.description(),
                        item.ownerId(),
                        item.status(),
                        item.latestVersion() == 0 ? null : item.latestVersion(),
                        item.updatedAt()
                ))
                .toList();
    }

    public StrategyTemplateDetailResponse getTemplateDetail(String templateId) {
        StrategyTemplate template = strategyTemplateRepository.findTemplate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        List<StrategyTemplateVersion> versions = strategyTemplateRepository.listVersions(templateId);
        Integer latestVersion = versions.isEmpty() ? null : versions.get(0).versionNo();
        return toDetailResponse(template, latestVersion, versions);
    }

    public List<StrategyTemplateVersionResponse> listVersions(String templateId) {
        ensureTemplateExists(templateId);
        return strategyTemplateRepository.listVersions(templateId).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional
    public StrategyTemplateDetailResponse createTemplate(CreateStrategyTemplateRequest request) {
        validateCreateRequest(request);

        String templateId = buildTemplateId(request.name());
        String ownerId = normalizeOwner(request.ownerId());
        strategyTemplateRepository.insertTemplate(templateId, request.name().trim(), request.description(), ownerId);

        String changeNote = normalizeOrDefault(request.changeNote(), "init");
        strategyTemplateRepository.insertVersion(templateId, 1, SOURCE_KIND_JAVA_PARAMS, request.initialDefinition(), changeNote, ownerId);
        strategyTemplateRepository.touchTemplate(templateId);

        return getTemplateDetail(templateId);
    }

    @Transactional
    public StrategyTemplateDetailResponse saveVersion(String templateId, SaveStrategyTemplateVersionRequest request) {
        ensureTemplateExists(templateId);
        validateDefinition(request == null ? null : request.definition());

        int nextVersion = strategyTemplateRepository.getLatestVersionNo(templateId) + 1;
        String createdBy = normalizeOwner(request.createdBy());
        String changeNote = normalizeOrDefault(request.changeNote(), "version " + nextVersion);

        strategyTemplateRepository.insertVersion(
                templateId,
                nextVersion,
                SOURCE_KIND_JAVA_PARAMS,
                request.definition(),
                changeNote,
                createdBy
        );
        strategyTemplateRepository.touchTemplate(templateId);

        return getTemplateDetail(templateId);
    }

    @Transactional
    public StrategyTemplateDetailResponse cloneTemplate(String templateId, CloneStrategyTemplateRequest request) {
        StrategyTemplate sourceTemplate = ensureTemplateExists(templateId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        int sourceVersion = request.fromVersion() == null
                ? strategyTemplateRepository.getLatestVersionNo(templateId)
                : request.fromVersion();
        if (sourceVersion <= 0) {
            throw new IllegalArgumentException("No version found to clone");
        }

        StrategyTemplateVersion source = strategyTemplateRepository.findVersion(templateId, sourceVersion)
                .orElseThrow(() -> new IllegalArgumentException("Template version not found: " + templateId + "#" + sourceVersion));

        String newTemplateId = buildTemplateId(request.name());
        String ownerId = normalizeOwner(request.ownerId());
        String description = request.description() == null ? sourceTemplate.description() : request.description();

        strategyTemplateRepository.insertTemplate(newTemplateId, request.name().trim(), description, ownerId);
        strategyTemplateRepository.insertVersion(
                newTemplateId,
                1,
                SOURCE_KIND_JAVA_PARAMS,
                source.definition(),
                normalizeOrDefault(request.changeNote(), "clone from " + templateId + "#" + sourceVersion),
                ownerId
        );
        strategyTemplateRepository.touchTemplate(newTemplateId);

        return getTemplateDetail(newTemplateId);
    }

    private StrategyTemplate ensureTemplateExists(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId is required");
        }
        return strategyTemplateRepository.findTemplate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
    }

    private void validateCreateRequest(CreateStrategyTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        validateDefinition(request.initialDefinition());
    }

    private void validateDefinition(StrategyDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("strategy definition is required");
        }
        if (definition.baseStrategyId() == null || definition.baseStrategyId().isBlank()) {
            throw new IllegalArgumentException("baseStrategyId is required");
        }
        if (definition.engineType() == null || definition.engineType().isBlank()) {
            throw new IllegalArgumentException("engineType is required");
        }
    }

    private StrategyTemplateDetailResponse toDetailResponse(StrategyTemplate template, Integer latestVersion, List<StrategyTemplateVersion> versions) {
        return new StrategyTemplateDetailResponse(
                template.templateId(),
                template.name(),
                template.description(),
                template.ownerId(),
                template.status(),
                latestVersion,
                template.createdAt(),
                template.updatedAt(),
                versions.stream().map(this::toVersionResponse).toList()
        );
    }

    private StrategyTemplateVersionResponse toVersionResponse(StrategyTemplateVersion version) {
        return new StrategyTemplateVersionResponse(
                version.templateId(),
                version.versionNo(),
                version.sourceKind(),
                version.definition(),
                version.changeNote(),
                version.createdBy(),
                version.createdAt()
        );
    }

    private String buildTemplateId(String name) {
        String normalized = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalized.isBlank()) {
            normalized = "strategy";
        }
        if (normalized.length() > 24) {
            normalized = normalized.substring(0, 24);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return "tpl_" + normalized + "_" + suffix;
    }

    private String normalizeOwner(String ownerId) {
        return normalizeOrDefault(ownerId, "demo-user");
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
