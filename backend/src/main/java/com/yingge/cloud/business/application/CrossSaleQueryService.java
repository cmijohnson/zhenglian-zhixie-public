package com.yingge.cloud.business.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.business.api.CrossSaleDtos;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CrossSaleQueryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(BUSINESS_ZONE);
    private static final String FROM = """
            from customers customer
            join memberships owner on owner.id=customer.owner_membership_id
            join user_accounts owner_account on owner_account.id=owner.user_id
            left join teams team on team.id=customer.team_id
            left join lateral (
              select cycle_row.* from customer_business_cycles cycle_row
              where cycle_row.organization_id=customer.organization_id
                and cycle_row.customer_id=customer.id
              order by cycle_row.cycle_number desc,cycle_row.created_at desc
              limit 1
            ) cycle on true
            left join lateral (
              select count(distinct collaborator_membership.id) collaborator_count,
                     string_agg(distinct collaborator_account.display_name, '、'
                       order by collaborator_account.display_name) collaborator_names
              from customer_access_grants grant_row
              join memberships collaborator_membership
                on collaborator_membership.id=grant_row.grantee_membership_id
               and collaborator_membership.organization_id=grant_row.organization_id
               and collaborator_membership.status='ACTIVE'
              join user_accounts collaborator_account
                on collaborator_account.id=collaborator_membership.user_id
               and collaborator_account.enabled=true
              where grant_row.organization_id=customer.organization_id
                and grant_row.customer_id=customer.id
                and collaborator_membership.id<>customer.owner_membership_id
            ) collaboration on true
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public CrossSaleQueryService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors, AuditService audit) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<CrossSaleDtos.Response> search(
            Boolean crossSale, UUID ownerMembershipId, UUID collaboratorMembershipId,
            BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String quality,
            int page, int size) {
        Query query = query(crossSale, ownerMembershipId, collaboratorMembershipId,
                status, markedFrom, markedTo, quality);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        MapSqlParameterSource parameters = new MapSqlParameterSource(query.parameters().getValues())
                .addValue("limit", safeSize)
                .addValue("offset", safePage * safeSize);
        long total = jdbc.queryForObject("select count(*) " + FROM + " where " + query.where(),
                query.parameters(), Long.class);
        List<CrossSaleDtos.Response> items = jdbc.query(selectSql() + FROM + " where " + query.where()
                + orderBy() + " limit :limit offset :offset", parameters, this::map);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new PageResponse<>(items, safePage, safeSize, total, totalPages);
    }

    @Transactional
    public byte[] export(
            Boolean crossSale, UUID ownerMembershipId, UUID collaboratorMembershipId,
            BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String quality) {
        Query query = query(crossSale, ownerMembershipId, collaboratorMembershipId,
                status, markedFrom, markedTo, quality);
        List<CrossSaleDtos.Response> rows = jdbc.query(
                selectSql() + FROM + " where " + query.where() + orderBy(),
                query.parameters(), this::map);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("交叉单查询");
            sheet.createFreezePane(0, 1);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            String[] headers = {"客户姓名", "客户等级", "团队", "主负责人", "协作者", "协作者人数",
                    "是否交叉单", "最近业务周期", "业务状态", "报价类型", "报价时间", "最终结果",
                    "友商/事实", "标记时间", "档案更新时间", "客户ID"};
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
                header.getCell(column).setCellStyle(headerStyle);
            }
            for (int index = 0; index < rows.size(); index++) {
                CrossSaleDtos.Response item = rows.get(index);
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(text(item.customerName()));
                row.createCell(1).setCellValue(text(item.customerQuality()));
                row.createCell(2).setCellValue(item.teamName() == null ? "未分组" : item.teamName());
                row.createCell(3).setCellValue(text(item.ownerDisplayName()));
                row.createCell(4).setCellValue(text(item.collaboratorNames()));
                row.createCell(5).setCellValue(item.collaboratorCount());
                row.createCell(6).setCellValue(item.crossSale() ? "是" : "否");
                row.createCell(7).setCellValue(cycleTitle(item));
                row.createCell(8).setCellValue(statusLabel(item.businessCycleStatus()));
                row.createCell(9).setCellValue(text(item.quoteType()));
                row.createCell(10).setCellValue(dateTime(item.quotedAt()));
                row.createCell(11).setCellValue(resultLabel(item.businessCycleStatus()));
                row.createCell(12).setCellValue(competitorFact(item));
                row.createCell(13).setCellValue(dateTime(item.statusMarkedAt()));
                row.createCell(14).setCellValue(dateTime(item.customerUpdatedAt()));
                row.createCell(15).setCellValue(item.customerId().toString());
            }
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.length - 1));
            }
            int[] widths = {18, 10, 18, 16, 28, 12, 12, 28, 16, 14, 20, 16, 36, 20, 20, 38};
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            workbook.write(output);
            audit.record(query.actor(), "CROSS_SALE_EXPORTED", "CROSS_SALE", null, "SUCCESS",
                    Map.of("rowCount", rows.size(), "filtered", hasFilters(crossSale, ownerMembershipId,
                            collaboratorMembershipId, status, markedFrom, markedTo, quality)));
            return output.toByteArray();
        } catch (IOException exception) {
            throw ApiException.unavailable("CROSS_SALE_EXPORT_FAILED", "交叉单数据导出失败");
        }
    }

    private Query query(Boolean crossSale, UUID ownerMembershipId, UUID collaboratorMembershipId,
                        BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String quality) {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有组织管理员或团队长可以查看交叉单汇总");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId());
        List<String> clauses = new ArrayList<>();
        clauses.add("customer.organization_id=:organizationId");
        if (crossSale != null) clauses.add((crossSale ? "" : "not ") + "("
                + CrossSaleDefinition.ACTIVE_COLLABORATOR_EXISTS + ")");
        if (ownerMembershipId != null) {
            clauses.add("customer.owner_membership_id=:ownerMembershipId");
            parameters.addValue("ownerMembershipId", ownerMembershipId);
        }
        if (collaboratorMembershipId != null) {
            clauses.add("""
                    exists (
                      select 1 from customer_access_grants selected_grant
                      join memberships selected_membership
                        on selected_membership.id=selected_grant.grantee_membership_id
                       and selected_membership.organization_id=selected_grant.organization_id
                       and selected_membership.status='ACTIVE'
                      join user_accounts selected_account
                        on selected_account.id=selected_membership.user_id and selected_account.enabled=true
                      where selected_grant.organization_id=customer.organization_id
                        and selected_grant.customer_id=customer.id
                        and selected_membership.id=:collaboratorMembershipId
                        and selected_membership.id<>customer.owner_membership_id
                    )
                    """);
            parameters.addValue("collaboratorMembershipId", collaboratorMembershipId);
        }
        if (status != null) {
            clauses.add("cycle.status=:status");
            parameters.addValue("status", status.name());
        }
        if (markedFrom != null) {
            clauses.add("cycle.status_marked_at>=:markedFrom");
            parameters.addValue("markedFrom", markedFrom);
        }
        if (markedTo != null) {
            clauses.add("cycle.status_marked_at<:markedTo");
            parameters.addValue("markedTo", markedTo);
        }
        String qualityLabel = qualityLabel(quality);
        if (qualityLabel != null) {
            clauses.add("""
                    exists(select 1 from customer_tags quality_link join tags quality_tag on quality_tag.id=quality_link.tag_id
                      where quality_link.customer_id=customer.id and quality_tag.organization_id=customer.organization_id
                        and quality_tag.active=true and quality_tag.category=:qualityCategory
                        and quality_tag.name=:qualityLabel)
                    """);
            parameters.addValue("qualityCategory", CustomerQuality.CATEGORY).addValue("qualityLabel", qualityLabel);
        }
        if (!actor.isAdmin() && actor.isTeamManager()) {
            if (actor.managedTeamIds().isEmpty()) clauses.add("false");
            else {
                clauses.add("customer.team_id in (:managedTeamIds)");
                parameters.addValue("managedTeamIds", actor.managedTeamIds());
            }
        }
        return new Query(actor, String.join(" and ", clauses), parameters);
    }

    private String selectSql() {
        return """
                select customer.id customer_id,customer.full_name customer_name,customer.team_id,
                  customer.owner_membership_id,customer.updated_at customer_updated_at,
                  owner_account.display_name owner_name,team.name team_name,
                  coalesce(collaboration.collaborator_count,0) collaborator_count,
                  coalesce(collaboration.collaborator_names,'') collaborator_names,
                  (coalesce(collaboration.collaborator_count,0)>0) cross_sale,
                  (select substring(quality_tag.name,1,1) from customer_tags quality_link
                    join tags quality_tag on quality_tag.id=quality_link.tag_id
                    where quality_link.customer_id=customer.id and quality_tag.organization_id=customer.organization_id
                      and quality_tag.active=true and quality_tag.category='客户质量'
                    order by case quality_tag.name when 'A类用户' then 1 when 'B类用户' then 2 else 3 end limit 1) customer_quality,
                  cycle.id business_cycle_id,cycle.cycle_number,cycle.title cycle_title,cycle.status cycle_status,
                  cycle.quote_type,cycle.quoted_at,cycle.competitor_name,cycle.competitor_fact,cycle.status_marked_at
                """;
    }

    private String orderBy() {
        return " order by coalesce(cycle.status_marked_at,customer.updated_at) desc,customer.id";
    }

    private CrossSaleDtos.Response map(ResultSet row, int index) throws SQLException {
        String cycleStatus = row.getString("cycle_status");
        return new CrossSaleDtos.Response(
                row.getObject("customer_id", UUID.class), row.getString("customer_name"),
                row.getString("customer_quality"), row.getObject("team_id", UUID.class), row.getString("team_name"),
                row.getObject("owner_membership_id", UUID.class), row.getString("owner_name"),
                row.getInt("collaborator_count"), row.getString("collaborator_names"), row.getBoolean("cross_sale"),
                row.getObject("business_cycle_id", UUID.class), row.getObject("cycle_number", Integer.class),
                row.getString("cycle_title"), cycleStatus == null ? null : BusinessCycleStatus.valueOf(cycleStatus),
                row.getString("quote_type"), instant(row, "quoted_at"), row.getString("competitor_name"),
                row.getString("competitor_fact"), instant(row, "status_marked_at"),
                row.getTimestamp("customer_updated_at").toInstant());
    }

    private String qualityLabel(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return CustomerQuality.LEVELS.stream()
                .filter(level -> level.code().equalsIgnoreCase(quality.trim())
                        || level.label().equalsIgnoreCase(quality.trim()))
                .map(CustomerQuality.Level::label)
                .findFirst().orElseThrow(() -> ApiException.badRequest("INVALID_CUSTOMER_QUALITY", "客户等级只支持 A、B、C"));
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private boolean hasFilters(Boolean crossSale, UUID ownerMembershipId, UUID collaboratorMembershipId,
                               BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String quality) {
        return crossSale != null || ownerMembershipId != null || collaboratorMembershipId != null || status != null
                || markedFrom != null || markedTo != null || (quality != null && !quality.isBlank());
    }

    private String cycleTitle(CrossSaleDtos.Response item) {
        if (item.businessCycleId() == null) return "尚未建立业务周期";
        return "#" + item.businessCycleNumber() + " · " + text(item.businessCycleTitle());
    }

    private String statusLabel(BusinessCycleStatus status) {
        if (status == null) return "无业务周期";
        return switch (status) {
            case IN_PROGRESS -> "进行中";
            case LOCK_PENDING -> "待锁定";
            case FORMALLY_QUOTED -> "已正式报价";
            case WON_PENDING -> "待确认成单";
            case WON -> "已成单";
            case LOST_TO_COMPETITOR -> "友商流失";
            case CANCELLED -> "已取消";
        };
    }

    private String resultLabel(BusinessCycleStatus status) {
        if (status == null) return "";
        return switch (status) {
            case WON -> "已成单";
            case LOST_TO_COMPETITOR -> "友商流失";
            case CANCELLED -> "已取消";
            default -> "推进中";
        };
    }

    private String competitorFact(CrossSaleDtos.Response item) {
        if (item.competitorName() == null && item.competitorFact() == null) return "";
        return (item.competitorName() == null ? "" : item.competitorName())
                + (item.competitorName() != null && item.competitorFact() != null ? " · " : "")
                + text(item.competitorFact());
    }

    private String dateTime(Instant value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private record Query(CurrentActor actor, String where, MapSqlParameterSource parameters) {
    }
}
