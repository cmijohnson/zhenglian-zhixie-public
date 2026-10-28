package com.yingge.cloud.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.api.AuditController;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditQueryService {
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;

    public AuditQueryService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors,
                             PermissionService permissions, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditController.AuditResponse> list(int page, int size) {
        return list(page, size, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditController.AuditResponse> list(int page, int size, UUID customerId,
                                                            UUID memberId, String action,
                                                            Instant from, Instant to) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Query query = query(actor.organizationId(), customerId, memberId, action, from, to);
        long total = jdbc.queryForObject("select count(*) " + query.fromWhere(), query.parameters(), Long.class);
        MapSqlParameterSource parameters = new MapSqlParameterSource(query.parameters().getValues())
                .addValue("limit", safeSize).addValue("offset", safePage * safeSize);
        List<AuditController.AuditResponse> rows = jdbc.query("""
                select audit.action,audit.entity_type,audit.entity_id,audit.outcome,audit.details::text,
                       audit.trace_id,audit.actor_membership_id,users.display_name actor_display_name,audit.created_at
                """ + query.fromWhere() + " order by audit.created_at desc limit :limit offset :offset",
                parameters, (rs, index) -> new AuditController.AuditResponse(
                        rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                        rs.getString("outcome"), details(rs.getString("details")), rs.getString("trace_id"),
                        rs.getObject("actor_membership_id", UUID.class), rs.getString("actor_display_name"),
                        rs.getTimestamp("created_at").toInstant()));
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        return new PageResponse<>(rows, safePage, safeSize, total, pages);
    }

    @Transactional
    public byte[] export(UUID customerId, UUID memberId, String action, Instant from, Instant to) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        Query query = query(actor.organizationId(), customerId, memberId, action, from, to);
        List<AuditController.AuditResponse> rows = jdbc.query("""
                select audit.action,audit.entity_type,audit.entity_id,audit.outcome,audit.details::text,
                       audit.trace_id,audit.actor_membership_id,users.display_name actor_display_name,audit.created_at
                """ + query.fromWhere() + " order by audit.created_at desc limit 10000",
                query.parameters(), (rs, index) -> new AuditController.AuditResponse(
                        rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                        rs.getString("outcome"), details(rs.getString("details")), rs.getString("trace_id"),
                        rs.getObject("actor_membership_id", UUID.class), rs.getString("actor_display_name"),
                        rs.getTimestamp("created_at").toInstant()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("操作审计");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            String[] headers = {"时间", "操作人", "成员ID", "动作", "对象类型", "对象ID", "结果", "traceId", "脱敏详情"};
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                header.getCell(i).setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (AuditController.AuditResponse item : rows) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(item.createdAt().toString());
                row.createCell(1).setCellValue(nullSafe(item.actorDisplayName()));
                row.createCell(2).setCellValue(item.actorMembershipId() == null ? "" : item.actorMembershipId().toString());
                row.createCell(3).setCellValue(item.action());
                row.createCell(4).setCellValue(nullSafe(item.entityType()));
                row.createCell(5).setCellValue(nullSafe(item.entityId()));
                row.createCell(6).setCellValue(item.outcome());
                row.createCell(7).setCellValue(nullSafe(item.traceId()));
                row.createCell(8).setCellValue(json.writeValueAsString(item.details()));
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            audit.record(actor, "AUDIT_EXPORTED", "AUDIT", null, "SUCCESS", Map.of("rowCount", rows.size()));
            return output.toByteArray();
        } catch (IOException exception) {
            throw ApiException.unavailable("AUDIT_EXPORT_FAILED", "审计日志导出失败");
        }
    }

    private Query query(UUID organizationId, UUID customerId, UUID memberId, String action,
                        Instant from, Instant to) {
        StringBuilder fromWhere = new StringBuilder("""
                 from audit_logs audit
                 left join memberships membership on membership.id=audit.actor_membership_id
                 left join user_accounts users on users.id=membership.user_id
                 where audit.organization_id=:organizationId
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource("organizationId", organizationId);
        if (customerId != null) {
            fromWhere.append(" and ((audit.entity_type='CUSTOMER' and audit.entity_id=:customerId) or audit.details->>'customerId'=:customerId)");
            parameters.addValue("customerId", customerId.toString());
        }
        if (memberId != null) {
            fromWhere.append(" and audit.actor_membership_id=:memberId");
            parameters.addValue("memberId", memberId);
        }
        if (action != null && !action.isBlank()) {
            String normalized = action.trim().toUpperCase();
            if (normalized.length() > 100 || !normalized.matches("[A-Z0-9_]+")) {
                throw ApiException.badRequest("AUDIT_ACTION_INVALID", "审计动作筛选无效");
            }
            fromWhere.append(" and audit.action=:action");
            parameters.addValue("action", normalized);
        }
        if (from != null) {
            fromWhere.append(" and audit.created_at>=:from");
            parameters.addValue("from", from);
        }
        if (to != null) {
            fromWhere.append(" and audit.created_at<:to");
            parameters.addValue("to", to);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("AUDIT_DATE_RANGE_INVALID", "审计开始时间必须早于结束时间");
        }
        return new Query(fromWhere.toString(), parameters);
    }

    private Map<String, Object> details(String value) {
        try {
            return value == null ? Map.of() : json.readValue(value, DETAILS);
        } catch (JsonProcessingException exception) {
            return Map.of("unavailable", true);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record Query(String fromWhere, MapSqlParameterSource parameters) {}
}
