package ai.jzhu.trading.backtest.infrastructure.repository;

import ai.jzhu.trading.backtest.domain.model.StrategyTemplate;
import ai.jzhu.trading.backtest.domain.model.StrategyTemplateVersion;
import ai.jzhu.trading.common.dto.backtest.StrategyDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class StrategyTemplateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StrategyTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StrategyTemplateSummaryRow> listSummaries() {
        return jdbcTemplate.query("""
                SELECT t.template_id,
                       t.name,
                       t.description,
                       t.owner_id,
                       t.status,
                       t.updated_at,
                       COALESCE(MAX(v.version_no), 0) AS latest_version
                FROM strategy_template t
                LEFT JOIN strategy_template_version v ON v.template_id = t.template_id
                GROUP BY t.template_id, t.name, t.description, t.owner_id, t.status, t.updated_at
                ORDER BY t.updated_at DESC
                """, (rs, rowNum) -> new StrategyTemplateSummaryRow(
                rs.getString("template_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("owner_id"),
                rs.getString("status"),
                rs.getInt("latest_version"),
                toInstant(rs.getTimestamp("updated_at"))
        ));
    }

    public Optional<StrategyTemplate> findTemplate(String templateId) {
        List<StrategyTemplate> rows = jdbcTemplate.query("""
                SELECT template_id, name, description, owner_id, status, created_at, updated_at
                FROM strategy_template
                WHERE template_id = ?
                """, templateRowMapper(), templateId);
        return rows.stream().findFirst();
    }

    public List<StrategyTemplateVersion> listVersions(String templateId) {
        return jdbcTemplate.query("""
                SELECT template_id, version_no, source_kind, definition_json, change_note, created_by, created_at
                FROM strategy_template_version
                WHERE template_id = ?
                ORDER BY version_no DESC
                """, versionRowMapper(), templateId);
    }

    public Optional<StrategyTemplateVersion> findVersion(String templateId, int versionNo) {
        List<StrategyTemplateVersion> rows = jdbcTemplate.query("""
                SELECT template_id, version_no, source_kind, definition_json, change_note, created_by, created_at
                FROM strategy_template_version
                WHERE template_id = ? AND version_no = ?
                """, versionRowMapper(), templateId, versionNo);
        return rows.stream().findFirst();
    }

    public int getLatestVersionNo(String templateId) {
        Integer latest = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0)
                FROM strategy_template_version
                WHERE template_id = ?
                """, Integer.class, templateId);
        return latest == null ? 0 : latest;
    }

    public void insertTemplate(String templateId, String name, String description, String ownerId) {
        jdbcTemplate.update("""
                INSERT INTO strategy_template(template_id, name, description, owner_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, templateId, name, description, ownerId);
    }

    public void touchTemplate(String templateId) {
        jdbcTemplate.update("""
                UPDATE strategy_template
                SET updated_at = NOW()
                WHERE template_id = ?
                """, templateId);
    }

    public void insertVersion(String templateId, int versionNo, String sourceKind, StrategyDefinition definition, String changeNote, String createdBy) {
        jdbcTemplate.update("""
                INSERT INTO strategy_template_version(template_id, version_no, source_kind, definition_json, change_note, created_by)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, templateId, versionNo, sourceKind, serializeDefinition(definition), changeNote, createdBy);
    }

    private String serializeDefinition(StrategyDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid strategy definition", ex);
        }
    }

    private StrategyDefinition deserializeDefinition(String value) {
        try {
            return objectMapper.readValue(value, StrategyDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupted strategy definition json", ex);
        }
    }

    private RowMapper<StrategyTemplate> templateRowMapper() {
        return (rs, rowNum) -> new StrategyTemplate(
                rs.getString("template_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("owner_id"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private RowMapper<StrategyTemplateVersion> versionRowMapper() {
        return (rs, rowNum) -> new StrategyTemplateVersion(
                rs.getString("template_id"),
                rs.getInt("version_no"),
                rs.getString("source_kind"),
                deserializeDefinition(readJsonColumn(rs, "definition_json")),
                rs.getString("change_note"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private String readJsonColumn(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return "{}";
        }
        return value.toString();
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    public record StrategyTemplateSummaryRow(
            String templateId,
            String name,
            String description,
            String ownerId,
            String status,
            int latestVersion,
            Instant updatedAt
    ) {
    }
}
