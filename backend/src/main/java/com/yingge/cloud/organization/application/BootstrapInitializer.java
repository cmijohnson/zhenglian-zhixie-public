package com.yingge.cloud.organization.application;

import com.yingge.cloud.organization.infrastructure.MembershipEntity;
import com.yingge.cloud.organization.infrastructure.MembershipRepository;
import com.yingge.cloud.organization.infrastructure.OrganizationEntity;
import com.yingge.cloud.organization.infrastructure.OrganizationRepository;
import com.yingge.cloud.organization.infrastructure.UserAccountEntity;
import com.yingge.cloud.organization.infrastructure.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!migration")
public class BootstrapInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);
    private final BootstrapProperties properties;
    private final UserAccountRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public BootstrapInitializer(BootstrapProperties properties, UserAccountRepository users,
                                OrganizationRepository organizations, MembershipRepository memberships,
                                PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.properties = properties;
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (isBlank(properties.adminUsername()) || isBlank(properties.adminPassword())) {
            log.warn("No users exist and bootstrap administrator credentials are not configured");
            return;
        }
        Instant now = Instant.now();
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        organizations.saveAndFlush(new OrganizationEntity(organizationId, "英格营销团队", "yingge", now));
        jdbc.update("insert into organization_settings (organization_id) values (?)", organizationId);
        users.saveAndFlush(new UserAccountEntity(userId, properties.adminUsername().trim(),
                passwordEncoder.encode(properties.adminPassword()),
                isBlank(properties.adminDisplayName()) ? "系统管理员" : properties.adminDisplayName().trim(), now));
        memberships.saveAndFlush(new MembershipEntity(membershipId, organizationId, userId, now));
        jdbc.update("insert into membership_roles (membership_id, role_id) values (?, ?)",
                membershipId, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        jdbc.update("""
                insert into performance_rule_versions (
                    id, organization_id, version_no, effective_from, created_by_membership_id
                ) values (?, ?, 1, current_date, ?)
                on conflict (organization_id, effective_from) do nothing
                """, UUID.randomUUID(), organizationId, membershipId);
        seedDictionaries(organizationId);
        seedTags(organizationId, membershipId);
        log.info("Bootstrapped organizationId={} membershipId={} username={}",
                organizationId, membershipId, properties.adminUsername().trim());
    }

    private void seedDictionaries(UUID organizationId) {
        insertDictionary(organizationId, "SOURCE_CHANNEL", List.of("小红书", "抖音", "新生群", "运营商", "朋友推荐", "其他", "QQ"));
        insertDictionary(organizationId, "FOCUS_DIRECTION", List.of("就业", "考研", "考公", "升学", "转专业", "技能提升", "暂不明确"));
        insertDictionary(organizationId, "INTERACTION_CHANNEL", List.of("微信", "电话", "面聊", "活动", "群聊", "语音", "其他"));
        insertDictionary(organizationId, "SCHOOL", List.of("示例大学", "示例科技大学"));
    }

    private void insertDictionary(UUID organizationId, String type, List<String> labels) {
        for (int i = 0; i < labels.size(); i++) {
            jdbc.update("insert into dictionary_items (id, organization_id, type, code, label, sort_order) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), organizationId, type, "item-" + (i + 1), labels.get(i), i + 1);
        }
    }

    private void seedTags(UUID organizationId, UUID membershipId) {
        List<String[]> tags = List.of(
                new String[]{"新生", "状态补充", "#f59e0b"}, new String[]{"转专业", "状态补充", "#dc2626"},
                new String[]{"已进群", "状态补充", "#7c3aed"}, new String[]{"高意向", "状态补充", "#16a34a"},
                new String[]{"待回访", "状态补充", "#ea580c"}, new String[]{"家长参与", "风险", "#7c2d12"},
                new String[]{"信息不完整", "风险", "#b91c1c"}, new String[]{"重复咨询", "风险", "#475569"}
        );
        for (String[] tag : tags) {
            jdbc.update("insert into tags (id, organization_id, name, category, color, is_system, created_by_membership_id) values (?, ?, ?, ?, ?, true, ?)",
                    UUID.randomUUID(), organizationId, tag[0], tag[1], tag[2], membershipId);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
