package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class FeishuCustomerSnapshotRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public FeishuCustomerSnapshotRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<CustomerSnapshot> find(UUID organizationId, List<UUID> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        return jdbc.query("""
                select customer.id,customer.version,customer.full_name,customer.nickname,customer.phone,
                       customer.wechat_id,customer.qq_id,customer.school,customer.high_school,customer.major,
                       customer.grade,customer.city,customer.source_location,customer.identity_type,
                       coalesce(source.label,customer.source_channel_legacy,'') source_channel,
                       coalesce(classification.level,'未标注') quality,
                       customer.intention_level,customer.urgency_level,customer.current_status,
                       case when approved.id is not null then '已成单'
                            when pending.id is not null then '待管理员审核' else '未申请' end deal_state,
                       owner_account.display_name owner_name,coalesce(team.name,'') team_name,
                       coalesce(collaborator.names,'') collaborators,coalesce(customer.demand_summary,'') demand_summary,
                       coalesce(focus.names,'') focus_directions,coalesce(tag.names,'') tags,
                       customer.last_contact_at,customer.next_follow_up_at,customer.visibility,
                       customer.archived_at,customer.created_at,customer.updated_at
                from customers customer
                join memberships owner_member on owner_member.id=customer.owner_membership_id
                join user_accounts owner_account on owner_account.id=owner_member.user_id
                left join teams team on team.id=customer.team_id
                left join dictionary_items source on source.id=customer.source_dictionary_id
                left join lateral (
                    select item.level from customer_classifications item
                    where item.customer_id=customer.id order by item.revision desc limit 1
                ) classification on true
                left join lateral (
                    select request.id from customer_deal_requests request
                    where request.customer_id=customer.id and request.status='APPROVED'
                    order by request.decided_at desc nulls last,request.created_at desc limit 1
                ) approved on true
                left join lateral (
                    select request.id from customer_deal_requests request
                    where request.customer_id=customer.id and request.status='PENDING'
                    order by request.created_at desc limit 1
                ) pending on true
                left join lateral (
                    select string_agg(distinct account.display_name,'、' order by account.display_name) names
                    from customer_access_grants grant_row
                    join memberships member on member.id=grant_row.grantee_membership_id and member.status='ACTIVE'
                    join user_accounts account on account.id=member.user_id and account.enabled=true
                    where grant_row.customer_id=customer.id and grant_row.grantee_membership_id is not null
                ) collaborator on true
                left join lateral (
                    select string_agg(direction,'、' order by direction) names
                    from customer_focus_directions item where item.customer_id=customer.id
                ) focus on true
                left join lateral (
                    select string_agg(tag_item.name,'、' order by tag_item.name) names
                    from customer_tags item join tags tag_item on tag_item.id=item.tag_id
                    where item.customer_id=customer.id and tag_item.category<>'客户质量'
                ) tag on true
                where customer.organization_id=:organizationId and customer.id in (:customerIds)
                order by customer.id
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("customerIds", customerIds), (rs, row) -> {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            UUID id = rs.getObject("id", UUID.class);
            long version = rs.getLong("version");
            values.put("customerId", id.toString());
            values.put("fullName", text(rs.getString("full_name")));
            values.put("nickname", text(rs.getString("nickname")));
            values.put("phone", text(rs.getString("phone")));
            values.put("wechatId", text(rs.getString("wechat_id")));
            values.put("qqId", text(rs.getString("qq_id")));
            values.put("school", text(rs.getString("school")));
            values.put("highSchool", text(rs.getString("high_school")));
            values.put("major", text(rs.getString("major")));
            values.put("grade", text(rs.getString("grade")));
            values.put("city", text(rs.getString("city")));
            values.put("sourceLocation", text(rs.getString("source_location")));
            values.put("identityType", text(rs.getString("identity_type")));
            values.put("sourceChannel", text(rs.getString("source_channel")));
            values.put("quality", text(rs.getString("quality")));
            values.put("intentionLevel", text(rs.getString("intention_level")));
            values.put("urgencyLevel", text(rs.getString("urgency_level")));
            values.put("currentStatus", text(rs.getString("current_status")));
            values.put("dealState", text(rs.getString("deal_state")));
            values.put("ownerName", text(rs.getString("owner_name")));
            values.put("teamName", text(rs.getString("team_name")));
            values.put("collaborators", text(rs.getString("collaborators")));
            values.put("demandSummary", text(rs.getString("demand_summary")));
            values.put("focusDirections", text(rs.getString("focus_directions")));
            values.put("tags", text(rs.getString("tags")));
            values.put("lastContactAt", epoch(rs.getTimestamp("last_contact_at")));
            values.put("nextFollowUpAt", epoch(rs.getTimestamp("next_follow_up_at")));
            String visibility = rs.getString("visibility");
            values.put("visibility", text(visibility));
            values.put("privateCustomer", "PRIVATE".equals(visibility));
            values.put("archived", rs.getTimestamp("archived_at") != null);
            values.put("createdAt", epoch(rs.getTimestamp("created_at")));
            values.put("updatedAt", epoch(rs.getTimestamp("updated_at")));
            values.put("sourceVersion", Long.toString(version));
            return new CustomerSnapshot(id, version, values, hash(values));
        });
    }

    private String hash(Map<String, Object> values) {
        try {
            byte[] serialized = json.writeValueAsString(values).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Feishu customer snapshot", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Feishu customer snapshot", exception);
        }
    }

    private Object epoch(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record CustomerSnapshot(UUID customerId, long sourceVersion, LinkedHashMap<String, Object> values,
                                   String hash) {
        public Map<String, Object> remoteFields(Map<String, String> remoteNames, Instant syncedAt) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(remoteNames.get(key), value));
            result.put(remoteNames.get("syncedAt"), syncedAt.toEpochMilli());
            return result;
        }
    }
}
