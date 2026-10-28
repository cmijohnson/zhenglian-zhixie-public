package com.yingge.cloud.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MockDataFactory {
    private MockDataFactory() {}

    public static SeededData seedCustomerScale(
            JdbcTemplate jdbc,
            UUID organizationId,
            List<MemberSeed> members,
            int customerCount,
            String namePrefix
    ) {
        if (members.isEmpty()) throw new IllegalArgumentException("members must not be empty");
        Instant now = Instant.now();
        List<SeededCustomer> customers = new ArrayList<>(customerCount);
        List<Object[]> customerRows = new ArrayList<>(customerCount);
        List<Object[]> focusRows = new ArrayList<>(customerCount);
        List<Object[]> interactionRows = new ArrayList<>(customerCount * 2);
        List<Object[]> reminderRows = new ArrayList<>(customerCount);

        for (int index = 0; index < customerCount; index++) {
            MemberSeed member = members.get(index % members.size());
            UUID customerId = UUID.randomUUID();
            String visibility = switch (index % 3) {
                case 0 -> "PRIVATE";
                case 1 -> "TEAM";
                default -> "ORGANIZATION";
            };
            boolean archived = index % 25 == 0;
            String status = archived ? "归档" : index % 19 == 0 ? "无效" : index % 7 == 0 ? "高意向" : "跟进中";
            String grade = index % 4 == 0 ? "26级" : index % 4 == 1 ? "2026级" : "25级";
            Instant createdAt = now.minusSeconds((long) (index % 20) * 3_600);
            Instant updatedAt = now.minusSeconds((long) (index % 10) * 300);
            Instant nextFollowUp = now.plusSeconds((long) ((index % 9) - 4) * 3_600);
            String phone = "188" + String.format("%08d", index + Math.abs(namePrefix.hashCode() % 10_000_000));

            customerRows.add(new Object[]{
                    customerId, organizationId, member.membershipId(), member.teamId(), visibility,
                    namePrefix + String.format("-%04d", index), phone, phone, "示例大学", "市场营销",
                    grade, "示例市", "在校生", "规模测试需求 " + index,
                    index % 7 == 0 ? "高" : index % 3 == 0 ? "低" : "中",
                    index % 5 == 0 ? "高" : "中", index % 6 == 0 ? "已核实" : "未核实",
                    "规模测试", status, "mock-row-" + index,
                    Timestamp.from(now.minusSeconds(86_400L + index)), Timestamp.from(nextFollowUp),
                    archived ? Timestamp.from(now.minusSeconds(600)) : null, member.membershipId(),
                    Timestamp.from(createdAt), Timestamp.from(updatedAt)
            });
            focusRows.add(new Object[]{customerId, index % 2 == 0 ? "升学规划" : "新媒体运营"});
            customers.add(new SeededCustomer(customerId, member.membershipId(), member.teamId(), visibility, archived));

            for (int interactionIndex = 0; interactionIndex < 2; interactionIndex++) {
                interactionRows.add(new Object[]{
                        UUID.randomUUID(), organizationId, customerId,
                        Timestamp.from(now.minusSeconds((long) (interactionIndex + 1) * 86_400 + index)),
                        interactionIndex == 0 ? "微信" : "电话", "Mock 沟通 " + interactionIndex,
                        "大量测试沟通详情", "继续跟进", "核实意向",
                        Timestamp.from(nextFollowUp), member.membershipId(), Timestamp.from(now.minusSeconds(index))
                });
            }
            reminderRows.add(new Object[]{
                    UUID.randomUUID(), organizationId, customerId, member.membershipId(), Timestamp.from(nextFollowUp),
                    "Mock 跟进提醒 " + index, archived ? "CANCELLED" : "PENDING",
                    archived ? Timestamp.from(now.minusSeconds(300)) : null, Timestamp.from(createdAt), Timestamp.from(updatedAt)
            });
        }

        jdbc.batchUpdate("""
                insert into customers (
                    id, organization_id, owner_membership_id, team_id, visibility, full_name,
                    phone, normalized_phone, school, major, grade, city, identity_type, demand_summary,
                    intention_level, urgency_level, verification_status, source_channel_legacy,
                    current_status, owner_notes, last_contact_at, next_follow_up_at, archived_at,
                    created_by_membership_id, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, customerRows);
        jdbc.batchUpdate("insert into customer_focus_directions (customer_id, direction) values (?, ?)", focusRows);
        jdbc.batchUpdate("""
                insert into interactions (
                    id, organization_id, customer_id, contacted_at, channel, summary, details, outcome,
                    verification_todo, next_follow_up_at, created_by_membership_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, interactionRows);
        jdbc.batchUpdate("""
                insert into reminders (
                    id, organization_id, customer_id, assigned_membership_id, remind_at, title,
                    status, completed_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reminderRows);

        return new SeededData(customers, customerRows.size(), interactionRows.size(), reminderRows.size());
    }

    public static String customerCsv(int validRows, int invalidRows, int phoneOffset) {
        StringBuilder csv = new StringBuilder("\uFEFF姓名,手机号,年级,当前状态,关注方向\n");
        for (int index = 0; index < validRows; index++) {
            csv.append("批量客户-").append(String.format("%04d", index)).append(',')
                    .append("177").append(String.format("%08d", phoneOffset + index)).append(',')
                    .append(index % 2 == 0 ? "26级" : "25级").append(',')
                    .append(index % 5 == 0 ? "高意向" : "初次接触").append(',')
                    .append(index % 2 == 0 ? "升学规划" : "新媒体运营").append('\n');
        }
        for (int index = 0; index < invalidRows; index++) {
            csv.append(",,26级,初次接触,升学规划\n");
        }
        return csv.toString();
    }

    public static Map<UUID, Long> countByOwner(SeededData data) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (SeededCustomer customer : data.customers()) {
            if (!customer.archived()) counts.merge(customer.ownerMembershipId(), 1L, Long::sum);
        }
        return counts;
    }

    public record MemberSeed(UUID membershipId, UUID teamId) {}
    public record SeededCustomer(UUID id, UUID ownerMembershipId, UUID teamId, String visibility, boolean archived) {}
    public record SeededData(List<SeededCustomer> customers, int customerCount, int interactionCount, int reminderCount) {
        public SeededCustomer firstOwnedBy(UUID membershipId) {
            return customers.stream().filter(row -> row.ownerMembershipId().equals(membershipId) && !row.archived()).findFirst().orElseThrow();
        }

        public SeededCustomer firstPrivateOwnedBy(UUID membershipId) {
            return customers.stream().filter(row -> row.ownerMembershipId().equals(membershipId)
                    && "PRIVATE".equals(row.visibility()) && !row.archived()).findFirst().orElseThrow();
        }

        public SeededCustomer firstTeamCustomer(UUID teamId) {
            return customers.stream().filter(row -> teamId.equals(row.teamId())
                    && "TEAM".equals(row.visibility()) && !row.archived()).findFirst().orElseThrow();
        }
    }
}
