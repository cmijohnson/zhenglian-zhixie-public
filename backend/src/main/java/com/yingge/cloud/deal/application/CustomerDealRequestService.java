package com.yingge.cloud.deal.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.deal.api.CustomerDealRequestDtos;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerDealRequestService {
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final AuditService audit;
    private final NotificationService notifications;

    public CustomerDealRequestService(JdbcTemplate jdbc, CurrentActorProvider actors,
                                      PermissionService permissions, CustomerService customers,
                                      AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional
    public CustomerDealRequestDtos.Response request(UUID customerId, String idempotencyKey) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        var customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireProfileEditable(customer, actor);
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_DEAL_FORBIDDEN", "归档客户不能申请成单");
        }
        String keyHash = idempotencyHash(idempotencyKey);
        List<CustomerDealRequestDtos.Response> repeated = byIdempotency(actor, customerId, keyHash);
        if (!repeated.isEmpty()) return repeated.getFirst();

        jdbc.queryForObject("select id from customers where id=? and organization_id=? for update",
                UUID.class, customerId, actor.organizationId());
        List<CustomerDealRequestDtos.Response> active = activeForCustomer(actor, customerId);
        if (!active.isEmpty()) {
            CustomerDealRequestDtos.Response existing = active.getFirst();
            if ("APPROVED".equals(existing.status())) {
                throw ApiException.conflict("CUSTOMER_ALREADY_WON", "该客户已成单");
            }
            return existing;
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into customer_deal_requests
                    (id,organization_id,customer_id,requested_by_membership_id,status,source,
                     requested_at,idempotency_key_hash,created_at,updated_at,version)
                    values (?,?,?,?,'PENDING','DIRECT',now(),?,now(),now(),0)
                    """, id, actor.organizationId(), customerId, actor.membershipId(), keyHash);
        } catch (DuplicateKeyException conflict) {
            List<CustomerDealRequestDtos.Response> concurrent = activeForCustomer(actor, customerId);
            if (!concurrent.isEmpty()) return concurrent.getFirst();
            throw ApiException.conflict("DEAL_REQUEST_CONFLICT", "成单申请已由其他请求处理");
        }
        appendEvent(actor, id, customerId, "REQUESTED", null, "PENDING");
        audit.record(actor, "CUSTOMER_DEAL_REQUESTED", "CUSTOMER_DEAL_REQUEST", id.toString(), "SUCCESS",
                Map.of("customerId", customerId.toString()));
        List<UUID> admins = adminMembershipIds(actor.organizationId());
        notifications.publish(actor.organizationId(), admins, "CUSTOMER_DEAL_REQUEST_PENDING",
                "待确认成单申请", customer.getFullName() + " 已申请成单确认",
                "/admin/governance", "deal-request-pending:" + id);
        return require(actor, id);
    }

    @Transactional(readOnly = true)
    public CustomerDealRequestDtos.Page adminList(String status, int page, int size) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String normalized = normalizeStatus(status);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String predicate = normalized == null ? "" : " and request.status=?";
        Object[] countArgs = normalized == null ? new Object[]{actor.organizationId()}
                : new Object[]{actor.organizationId(), normalized};
        Long total = jdbc.queryForObject("select count(*) from customer_deal_requests request where request.organization_id=?"
                + predicate, Long.class, countArgs);
        String sql = selectSql() + " where request.organization_id=?" + predicate
                + " order by request.requested_at desc,request.id limit ? offset ?";
        Object[] args = normalized == null
                ? new Object[]{actor.organizationId(), safeSize, safePage * safeSize}
                : new Object[]{actor.organizationId(), normalized, safeSize, safePage * safeSize};
        List<CustomerDealRequestDtos.Response> content = jdbc.query(sql, this::map, args);
        long elements = total == null ? 0 : total;
        int pages = elements == 0 ? 0 : (int) Math.ceil((double) elements / safeSize);
        return new CustomerDealRequestDtos.Page(content, safePage, safeSize, elements, pages);
    }

    @Transactional
    public CustomerDealRequestDtos.Response approve(UUID requestId, long expectedVersion) {
        return decide(requestId, expectedVersion, true);
    }

    @Transactional
    public CustomerDealRequestDtos.Response reject(UUID requestId, long expectedVersion) {
        return decide(requestId, expectedVersion, false);
    }

    private CustomerDealRequestDtos.Response decide(UUID requestId, long expectedVersion, boolean approve) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        CustomerDealRequestDtos.Response current = lock(actor, requestId);
        if (!"PENDING".equals(current.status())) {
            throw ApiException.conflict("DEAL_REQUEST_ALREADY_REVIEWED", "成单申请已处理");
        }
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("DEAL_REQUEST_VERSION_CONFLICT", "成单申请已更新，请刷新后重试");
        }
        String target = approve ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("""
                update customer_deal_requests set status=?,decided_by_membership_id=?,decided_at=now(),
                    confirmation_channel=case when ? then 'WEB_ADMIN' else confirmation_channel end,
                    confirmed_by_membership_id=case when ? then ? else confirmed_by_membership_id end,
                    confirmed_at=case when ? then now() else confirmed_at end,
                    updated_at=now(),version=version+1
                where id=? and organization_id=? and status='PENDING' and version=?
                """, target, actor.membershipId(), approve, approve, actor.membershipId(), approve,
                requestId, actor.organizationId(), expectedVersion);
        if (changed != 1) throw ApiException.conflict("DEAL_REQUEST_VERSION_CONFLICT", "成单申请已更新，请刷新后重试");
        appendEvent(actor, requestId, current.customerId(), approve ? "APPROVED" : "REJECTED",
                "PENDING", target);
        audit.record(actor, approve ? "CUSTOMER_DEAL_APPROVED" : "CUSTOMER_DEAL_REJECTED",
                "CUSTOMER_DEAL_REQUEST", requestId.toString(), "SUCCESS",
                Map.of("customerId", current.customerId().toString()));
        notifications.publish(actor.organizationId(), List.of(current.applicantMembershipId()),
                approve ? "CUSTOMER_DEAL_REQUEST_APPROVED" : "CUSTOMER_DEAL_REQUEST_REJECTED",
                approve ? "成单申请已确认" : "成单申请已驳回",
                current.customerName() + (approve ? " 已由管理员确认成单" : " 的成单申请已驳回"),
                "/customers/" + current.customerId(), "deal-request-decision:" + requestId + ":" + target);
        return require(actor, requestId);
    }

    private List<CustomerDealRequestDtos.Response> byIdempotency(CurrentActor actor, UUID customerId, String hash) {
        return jdbc.query(selectSql() + " where request.organization_id=? and request.requested_by_membership_id=?"
                        + " and request.customer_id=? and request.idempotency_key_hash=?", this::map,
                actor.organizationId(), actor.membershipId(), customerId, hash);
    }

    private List<CustomerDealRequestDtos.Response> activeForCustomer(CurrentActor actor, UUID customerId) {
        return jdbc.query(selectSql() + " where request.organization_id=? and request.customer_id=?"
                        + " and request.status in ('APPROVED','PENDING')"
                        + " order by case request.status when 'APPROVED' then 0 else 1 end limit 1",
                this::map, actor.organizationId(), customerId);
    }

    private CustomerDealRequestDtos.Response lock(CurrentActor actor, UUID requestId) {
        List<CustomerDealRequestDtos.Response> values = jdbc.query(selectSql()
                        + " where request.organization_id=? and request.id=? for update of request",
                this::map, actor.organizationId(), requestId);
        if (values.isEmpty()) throw ApiException.notFound("DEAL_REQUEST_NOT_FOUND", "成单申请不存在");
        return values.getFirst();
    }

    private CustomerDealRequestDtos.Response require(CurrentActor actor, UUID requestId) {
        List<CustomerDealRequestDtos.Response> values = jdbc.query(selectSql()
                        + " where request.organization_id=? and request.id=?",
                this::map, actor.organizationId(), requestId);
        if (values.isEmpty()) throw ApiException.notFound("DEAL_REQUEST_NOT_FOUND", "成单申请不存在");
        return values.getFirst();
    }

    private String selectSql() {
        return """
                select request.id,request.customer_id,customer.full_name customer_name,team.name team_name,
                  owner.display_name owner_name,request.requested_by_membership_id,
                  applicant.display_name applicant_name,request.status,request.requested_at,request.decided_at,
                  request.decided_by_membership_id,request.version
                from customer_deal_requests request
                join customers customer on customer.id=request.customer_id
                left join teams team on team.id=customer.team_id
                join memberships owner_membership on owner_membership.id=customer.owner_membership_id
                join user_accounts owner on owner.id=owner_membership.user_id
                join memberships applicant_membership on applicant_membership.id=request.requested_by_membership_id
                join user_accounts applicant on applicant.id=applicant_membership.user_id
                """;
    }

    private CustomerDealRequestDtos.Response map(ResultSet rs, int row) throws SQLException {
        Timestamp reviewedAt = rs.getTimestamp("decided_at");
        return new CustomerDealRequestDtos.Response(rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("team_name"), rs.getString("owner_name"),
                rs.getObject("requested_by_membership_id", UUID.class), rs.getString("applicant_name"),
                rs.getString("status"), rs.getTimestamp("requested_at").toInstant(),
                reviewedAt == null ? null : reviewedAt.toInstant(),
                rs.getObject("decided_by_membership_id", UUID.class), rs.getLong("version"));
    }

    private void appendEvent(CurrentActor actor, UUID requestId, UUID customerId, String type,
                             String previous, String next) {
        jdbc.update("""
                insert into customer_deal_request_events
                (id,organization_id,request_id,customer_id,actor_membership_id,event_type,
                 previous_status,new_status,details,created_at)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb),now())
                """, UUID.randomUUID(), actor.organizationId(), requestId, customerId, actor.membershipId(),
                type, previous, next, "{}");
    }

    private List<UUID> adminMembershipIds(UUID organizationId) {
        return jdbc.queryForList("""
                select distinct membership.id from memberships membership
                join membership_roles assignment on assignment.membership_id=membership.id
                join roles role on role.id=assignment.role_id
                join user_accounts account on account.id=membership.user_id
                where membership.organization_id=? and membership.status='ACTIVE'
                  and account.enabled=true and role.code='ORG_ADMIN'
                """, UUID.class, organizationId);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String value = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PENDING", "APPROVED", "REJECTED", "REVERSED").contains(value)) {
            throw ApiException.badRequest("DEAL_REQUEST_STATUS_INVALID", "成单申请状态无效");
        }
        return value;
    }

    private String idempotencyHash(String key) {
        if (key == null || key.isBlank() || key.trim().length() < 16 || key.trim().length() > 160) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须为 16–160 个字符");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
