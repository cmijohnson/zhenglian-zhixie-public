package com.yingge.cloud.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FlywayPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_test")
            .withUsername("yingge_test")
            .withPassword("test-password");

    @Test
    void migratesFromZeroAndCreatesPersistentSessionAndTenantTables() throws Exception {
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(64);

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema='public'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(25);
            }
            try (var roles = statement.executeQuery("select count(*) from roles where organization_id is null")) {
                assertThat(roles.next()).isTrue();
                assertThat(roles.getInt(1)).isEqualTo(4);
            }
            try (var sessions = statement.executeQuery("select count(*) from spring_session")) {
                assertThat(sessions.next()).isTrue();
            }
            try (var workflowTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('content_posts', 'daily_reports', 'work_tasks', 'work_task_assignments')
                    """)) {
                assertThat(workflowTables.next()).isTrue();
                assertThat(workflowTables.getInt(1)).isEqualTo(4);
            }
            try (var registrationCodes = statement.executeQuery("select count(*) from registration_codes")) {
                assertThat(registrationCodes.next()).isTrue();
            }
            try (var leaderIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='team_members_single_leader_idx'
                    """)) {
                assertThat(leaderIndex.next()).isTrue();
                assertThat(leaderIndex.getInt(1)).isEqualTo(1);
            }
            try (var announcements = statement.executeQuery("select count(*) from announcements")) {
                assertThat(announcements.next()).isTrue();
            }
            try (var leadershipFlag = statement.executeQuery("""
                    select show_on_leadership_screen
                    from announcements
                    limit 0
                    """)) {
                assertThat(leadershipFlag.getMetaData().getColumnCount()).isEqualTo(1);
            }
            try (var leadReviewColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='customers'
                    and column_name in ('lead_review_status','lead_review_reason',
                                        'lead_reviewed_by_membership_id','lead_reviewed_at')
                    """)) {
                assertThat(leadReviewColumns.next()).isTrue();
                assertThat(leadReviewColumns.getInt(1)).isEqualTo(4);
            }
            try (var assistantSettings = statement.executeQuery("select count(*) from assistant_settings")) {
                assertThat(assistantSettings.next()).isTrue();
            }
            try (var screenSettings = statement.executeQuery("select count(*) from leadership_screen_settings")) {
                assertThat(screenSettings.next()).isTrue();
            }
            try (var reminderSource = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='reminders'
                      and column_name='source_analysis_version_id'
                    """)) {
                assertThat(reminderSource.next()).isTrue();
                assertThat(reminderSource.getInt(1)).isEqualTo(1);
            }
            try (var schoolDictionaryIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='uq_dictionary_school_label'
                    """)) {
                assertThat(schoolDictionaryIndex.next()).isTrue();
                assertThat(schoolDictionaryIndex.getInt(1)).isEqualTo(1);
            }
            try (var sourceDictionaryIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='uq_dictionary_source_label'
                    """)) {
                assertThat(sourceDictionaryIndex.next()).isTrue();
                assertThat(sourceDictionaryIndex.getInt(1)).isEqualTo(1);
            }
            try (var collaborationIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='uq_customer_member_access_grant'
                    """)) {
                assertThat(collaborationIndex.next()).isTrue();
                assertThat(collaborationIndex.getInt(1)).isEqualTo(1);
            }
            try (var conflictIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='idx_customers_name_major_conflict'
                    """)) {
                assertThat(conflictIndex.next()).isTrue();
                assertThat(conflictIndex.getInt(1)).isEqualTo(1);
            }
            try (var mcpTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('oauth_clients','oauth_authorization_requests','oauth_grants',
                      'oauth_authorization_codes','oauth_access_tokens','oauth_refresh_tokens',
                      'mcp_idempotency_records','mcp_approval_requests')
                    """)) {
                assertThat(mcpTables.next()).isTrue();
                assertThat(mcpTables.getInt(1)).isEqualTo(8);
            }
            try (var authVersion = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='user_accounts'
                    and column_name='auth_version'
                    """)) {
                assertThat(authVersion.next()).isTrue();
                assertThat(authVersion.getInt(1)).isEqualTo(1);
            }
            try (var forumTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('forum_boards','forum_posts','forum_post_tags','forum_attachments',
                      'forum_comments','forum_likes','forum_announcement_reads','forum_reports',
                      'forum_moderation_actions')
                    """)) {
                assertThat(forumTables.next()).isTrue();
                assertThat(forumTables.getInt(1)).isEqualTo(9);
            }
            try (var friendFunnelColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='customers'
                    and column_name in ('friend_added_at','friend_trusted_at','friend_qualified_at')
                    """)) {
                assertThat(friendFunnelColumns.next()).isTrue();
                assertThat(friendFunnelColumns.getInt(1)).isEqualTo(3);
            }
            try (var performanceTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('performance_rule_versions','performance_participants',
                      'performance_interaction_claims','performance_activities',
                      'performance_activity_allocations','performance_manual_entries',
                      'performance_evidence','performance_score_ledger','performance_days',
                      'performance_daily_settlements')
                    """)) {
                assertThat(performanceTables.next()).isTrue();
                assertThat(performanceTables.getInt(1)).isEqualTo(10);
            }
            try (var collaborationTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('organization_follow_up_policies','follow_up_recruitments',
                      'follow_up_recruitment_claims','consultation_settings','consultation_failure_reasons',
                      'consultation_handoffs','qq_connections','qq_imports',
                      'customer_enrichment_jobs','customer_enrichment_rows')
                    """)) {
                assertThat(collaborationTables.next()).isTrue();
                assertThat(collaborationTables.getInt(1)).isEqualTo(10);
            }
            try (var enrichmentColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='customers'
                    and column_name in ('high_school','source_location')
                    """)) {
                assertThat(enrichmentColumns.next()).isTrue();
                assertThat(enrichmentColumns.getInt(1)).isEqualTo(2);
            }
            try (var automaticReminderIndex = statement.executeQuery("""
                    select count(*) from pg_indexes where schemaname='public'
                    and indexname='uq_pending_automatic_reminder'
                    """)) {
                assertThat(automaticReminderIndex.next()).isTrue();
                assertThat(automaticReminderIndex.getInt(1)).isEqualTo(1);
            }
            try (var intelligenceTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('customer_intelligence_settings','customer_knowledge_chunks',
                      'customer_analysis_jobs','customer_analysis_versions')
                    """)) {
                assertThat(intelligenceTables.next()).isTrue();
                assertThat(intelligenceTables.getInt(1)).isEqualTo(4);
            }
            try (var qqWorkbenchColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='qq_imports'
                    and column_name in ('chat_type','peer_uid','session_name','target_sender_uid',
                      'target_sender_qq','group_context_confirmed')
                    """)) {
                assertThat(qqWorkbenchColumns.next()).isTrue();
                assertThat(qqWorkbenchColumns.getInt(1)).isEqualTo(6);
            }
            try (var marketGovernanceTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('customer_classifications','customer_classification_review_queue',
                      'market_deadline_rule_versions','customer_deadline_snapshots','customer_deadline_events',
                      'customer_pool_entries','customer_ownership_states','customer_claim_records',
                      'customer_claim_cooldowns','customer_role_assignments','second_line_recruitments',
                      'second_line_contact_authorizations','customer_takeover_tasks','customer_ownership_events',
                      'customer_business_cycles','customer_business_participant_snapshots',
                      'customer_business_cycle_events','customer_business_participant_change_requests',
                      'customer_business_participant_change_approvals','customer_referrals','system_notifications',
                      'wecom_webhook_channels','wecom_webhook_deliveries')
                    """)) {
                assertThat(marketGovernanceTables.next()).isTrue();
                assertThat(marketGovernanceTables.getInt(1)).isEqualTo(23);
            }
            try (var feishuTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('feishu_bitable_integrations','feishu_bitable_fields',
                      'feishu_bitable_record_links','feishu_bitable_sync_queue','feishu_bitable_sync_runs')
                    """)) {
                assertThat(feishuTables.next()).isTrue();
                assertThat(feishuTables.getInt(1)).isEqualTo(5);
            }
            try (var feishuTriggers = statement.executeQuery("""
                    select count(*) from pg_trigger where not tgisinternal
                    and tgname in ('customers_feishu_sync_queue','customer_access_grants_feishu_sync_queue',
                      'customer_classifications_feishu_sync_queue','customer_deal_requests_feishu_sync_queue',
                      'customer_tags_feishu_sync_queue','customer_focus_directions_feishu_sync_queue')
                    """)) {
                assertThat(feishuTriggers.next()).isTrue();
                assertThat(feishuTriggers.getInt(1)).isEqualTo(6);
            }
            try (var feishuOauthPurposeConstraint = statement.executeQuery("""
                    select pg_get_constraintdef(oid) definition
                    from pg_constraint
                    where conname='feishu_oauth_intent_purpose_check'
                    """)) {
                assertThat(feishuOauthPurposeConstraint.next()).isTrue();
                assertThat(feishuOauthPurposeConstraint.getString("definition"))
                        .contains("LOGIN", "BIND", "SUBSCRIBE");
            }
            try (var releaseTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('product_releases','product_release_acknowledgements')
                    """)) {
                assertThat(releaseTables.next()).isTrue();
                assertThat(releaseTables.getInt(1)).isEqualTo(2);
            }
            try (var releases = statement.executeQuery("""
                    select count(*) total,
                           count(*) filter (where current and version='2026.09.07.2') current_count,
                           count(*) filter (where sections <> '{}'::jsonb) detailed_count
                    from product_releases where published
                    """)) {
                assertThat(releases.next()).isTrue();
                assertThat(releases.getInt("total")).isEqualTo(33);
                assertThat(releases.getInt("current_count")).isEqualTo(1);
                assertThat(releases.getInt("detailed_count")).isEqualTo(33);
            }
            try (var ownershipCycleColumn = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='customer_deadline_snapshots'
                    and column_name='ownership_cycle'
                    """)) {
                assertThat(ownershipCycleColumn.next()).isTrue();
                assertThat(ownershipCycleColumn.getInt(1)).isEqualTo(1);
            }
            try (var quoteColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='customer_business_cycles'
                    and column_name in ('lock_request_reason','lock_requested_by_membership_id','lock_requested_at',
                      'quote_type','quoted_at','quote_confirmed_by_membership_id','participant_locked_at')
                    """)) {
                assertThat(quoteColumns.next()).isTrue();
                assertThat(quoteColumns.getInt(1)).isEqualTo(7);
            }
            try (var interactionGovernanceColumns = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_name='interactions'
                    and column_name in ('interaction_type','content_fingerprint',
                      'deadline_effect','deadline_effect_reason')
                    """)) {
                assertThat(interactionGovernanceColumns.next()).isTrue();
                assertThat(interactionGovernanceColumns.getInt(1)).isEqualTo(4);
            }
            try (var immutableAuditTrigger = statement.executeQuery("""
                    select count(*) from pg_trigger where not tgisinternal
                    and tgname='audit_logs_append_only'
                    """)) {
                assertThat(immutableAuditTrigger.next()).isTrue();
                assertThat(immutableAuditTrigger.getInt(1)).isEqualTo(1);
            }
            try (var mobileTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('mobile_device_sessions','mobile_access_tokens','mobile_refresh_tokens',
                      'mobile_push_registrations','mobile_push_preferences','mobile_push_outbox',
                      'mobile_push_deliveries','mobile_webview_tickets','mobile_app_releases',
                      'customer_deal_requests','customer_deal_request_events')
                    """)) {
                assertThat(mobileTables.next()).isTrue();
                assertThat(mobileTables.getInt(1)).isEqualTo(11);
            }
            try (var noLegacyReason = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_name='customer_deal_requests' and column_name='rejection_reason'
                    """)) {
                assertThat(noLegacyReason.next()).isTrue();
                assertThat(noLegacyReason.getInt(1)).isZero();
            }
            try (var androidRelease = statement.executeQuery("""
                    select count(*) from mobile_app_releases
                    where platform='ANDROID' and version_code=3 and version_name='0.1.2'
                      and apk_path='android/yingge-cloud-0.1.2.apk'
                      and apk_size=3679166
                      and apk_sha256='b99374a68936793dd7e17391f355c4f46a1139b56f05340aa14c93321185d748'
                      and published
                    """)) {
                assertThat(androidRelease.next()).isTrue();
                assertThat(androidRelease.getInt(1)).isEqualTo(1);
            }
            try (var androidReliabilityRelease = statement.executeQuery("""
                    select count(*) from mobile_app_releases
                    where platform='ANDROID' and version_code=6 and version_name='0.2.1'
                      and apk_path='android/yingge-cloud-0.2.1.apk'
                      and apk_size=5205995
                      and apk_sha256='fa2035a7fe706b410f0a8a2a542e4ca8a8c8af60f8050f00b1523b40d7c56265'
                      and published
                    """)) {
                assertThat(androidReliabilityRelease.next()).isTrue();
                assertThat(androidReliabilityRelease.getInt(1)).isEqualTo(1);
            }
            try (var vectorExtension = statement.executeQuery("""
                    select count(*) from pg_extension where extname='vector'
                    """)) {
                assertThat(vectorExtension.next()).isTrue();
                assertThat(vectorExtension.getInt(1)).isEqualTo(1);
            }
            try (var leadershipGrade = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_name='leadership_screen_settings' and column_name='featured_grade'
                    """)) {
                assertThat(leadershipGrade.next()).isTrue();
                assertThat(leadershipGrade.getInt(1)).isEqualTo(1);
            }
            String organizationId = java.util.UUID.randomUUID().toString();
            String feishuUserId = java.util.UUID.randomUUID().toString();
            String feishuMembershipId = java.util.UUID.randomUUID().toString();
            String feishuCustomerId = java.util.UUID.randomUUID().toString();
            String feishuBlankCustomerId = java.util.UUID.randomUUID().toString();
            String blockedBlankCustomerId = java.util.UUID.randomUUID().toString();
            String feishuIntegrationId = java.util.UUID.randomUUID().toString();
            String feishuRunId = java.util.UUID.randomUUID().toString();
            statement.executeUpdate("""
                    insert into organizations (id, name, code)
                    values ('%s', '质量标签测试组织', 'quality-tag-test')
                    """.formatted(organizationId));
            try (var qualityTags = statement.executeQuery("""
                    select count(*) from tags
                    where organization_id='%s' and category='客户质量'
                      and name in ('A类用户','B类用户','C类用户')
                      and is_system=true and active=true
                    """.formatted(organizationId))) {
                assertThat(qualityTags.next()).isTrue();
                assertThat(qualityTags.getInt(1)).isEqualTo(3);
            }
            statement.executeUpdate("""
                    insert into user_accounts(id,username,password_hash,display_name)
                    values ('%s','feishu-trigger-user','test-hash','飞书触发测试成员')
                    """.formatted(feishuUserId));
            statement.executeUpdate("""
                    insert into memberships(id,organization_id,user_id)
                    values ('%s','%s','%s')
                    """.formatted(feishuMembershipId, organizationId, feishuUserId));
            statement.executeUpdate("""
                    insert into feishu_bitable_integrations(
                      id,organization_id,app_id,encrypted_app_secret,base_app_token,active,
                      schema_state,risk_acknowledged_at,created_by_membership_id,updated_by_membership_id
                    ) values ('%s','%s','cli_trigger',decode('00','hex'),'bascn_trigger',true,
                      'READY',now(),'%s','%s')
                    """.formatted(feishuIntegrationId, organizationId, feishuMembershipId, feishuMembershipId));
            statement.executeUpdate("""
                    insert into customers(
                      id,organization_id,owner_membership_id,visibility,full_name,phone,normalized_phone,
                      identity_type,intention_level,urgency_level,verification_status,current_status,
                      created_by_membership_id
                    ) values ('%s','%s','%s','PRIVATE','飞书触发客户','13900000001','13900000001',
                      '在校生','中','中','已核实','跟进中','%s')
                    """.formatted(feishuCustomerId, organizationId, feishuMembershipId, feishuMembershipId));
            try (var queued = statement.executeQuery("""
                    select generation,status from feishu_bitable_sync_queue
                    where integration_id='%s' and customer_id='%s'
                    """.formatted(feishuIntegrationId, feishuCustomerId))) {
                assertThat(queued.next()).isTrue();
                assertThat(queued.getLong("generation")).isEqualTo(1);
                assertThat(queued.getString("status")).isEqualTo("PENDING");
            }
            statement.executeUpdate("update customers set nickname='第二版' where id='%s'"
                    .formatted(feishuCustomerId));
            try (var queued = statement.executeQuery("""
                    select generation,status from feishu_bitable_sync_queue
                    where integration_id='%s' and customer_id='%s'
                    """.formatted(feishuIntegrationId, feishuCustomerId))) {
                assertThat(queued.next()).isTrue();
                assertThat(queued.getLong("generation")).isEqualTo(2);
                assertThat(queued.getString("status")).isEqualTo("PENDING");
            }
            statement.executeUpdate("""
                    insert into customers(
                      id,organization_id,owner_membership_id,visibility,full_name,
                      identity_type,intention_level,urgency_level,verification_status,
                      source_channel_legacy,current_status,contact_completion_status,
                      created_by_membership_id
                    ) values ('%s','%s','%s','ORGANIZATION','飞书空白快速建档',
                      '在校生','中','中','未核实','飞书临时建档','初次接触','PENDING','%s')
                    """.formatted(feishuBlankCustomerId, organizationId, feishuMembershipId,
                    feishuMembershipId));
            try (var blankCustomer = statement.executeQuery("""
                    select contact_completion_status,phone,wechat_id,qq_id
                    from customers where id='%s'
                    """.formatted(feishuBlankCustomerId))) {
                assertThat(blankCustomer.next()).isTrue();
                assertThat(blankCustomer.getString("contact_completion_status")).isEqualTo("PENDING");
                assertThat(blankCustomer.getString("phone")).isNull();
                assertThat(blankCustomer.getString("wechat_id")).isNull();
                assertThat(blankCustomer.getString("qq_id")).isNull();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into customers(
                      id,organization_id,owner_membership_id,visibility,full_name,
                      identity_type,intention_level,urgency_level,verification_status,
                      source_channel_legacy,current_status,contact_completion_status,
                      created_by_membership_id
                    ) values ('%s','%s','%s','ORGANIZATION','普通空白建档',
                      '在校生','中','中','未核实','普通渠道','初次接触','PENDING','%s')
                    """.formatted(blockedBlankCustomerId, organizationId, feishuMembershipId,
                    feishuMembershipId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_customer_contact");
            statement.executeUpdate("""
                    insert into feishu_bitable_sync_runs(
                      id,integration_id,organization_id,run_type,status,total_count
                    ) values ('%s','%s','%s','HOURLY','PENDING',1)
                    """.formatted(feishuRunId, feishuIntegrationId, organizationId));
            statement.executeUpdate("""
                    update feishu_bitable_sync_runs set status='COMPLETED_WITH_ERRORS' where id='%s'
                    """.formatted(feishuRunId));
        }
    }

    @Test
    void upgradesV27SchemaSnapshotThroughReminderSourceMigration() throws Exception {
        String databaseName = "yingge_v27_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }

        String upgradeJdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), databaseName);
        Flyway v27Snapshot = Flyway.configure()
                .dataSource(upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("27"))
                .load();
        var baselineResult = v27Snapshot.migrate();
        assertThat(baselineResult.migrationsExecuted).isEqualTo(27);
        assertThat(v27Snapshot.info().current().getVersion().getVersion()).isEqualTo("27");

        Flyway upgradedDatabase = Flyway.configure()
                .dataSource(upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        var upgradeResult = upgradedDatabase.migrate();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(37);
        assertThat(upgradedDatabase.info().current().getVersion().getVersion()).isEqualTo("64");

        try (var connection = DriverManager.getConnection(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var reminderSource = statement.executeQuery("""
                     select count(*) from information_schema.columns
                     where table_schema='public' and table_name='reminders'
                       and column_name='source_analysis_version_id'
                     """)) {
            assertThat(reminderSource.next()).isTrue();
            assertThat(reminderSource.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void upgradesExistingV43DatabaseThroughLockedMobileMigrations() throws Exception {
        String databaseName = "yingge_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }

        String upgradeJdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), databaseName);
        Flyway existingDatabase = Flyway.configure()
                .dataSource(upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("43"))
                .load();
        var baselineResult = existingDatabase.migrate();
        assertThat(baselineResult.migrationsExecuted).isEqualTo(43);
        assertThat(existingDatabase.info().current().getVersion().getVersion()).isEqualTo("43");

        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID wonCustomerId = UUID.randomUUID();
        UUID pendingCustomerId = UUID.randomUUID();
        UUID ambiguousQualityCustomerId = UUID.randomUUID();
        UUID wonCycleId = UUID.randomUUID();
        UUID pendingCycleId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into organizations(id,name,code) values ('%s','升级测试组织','upgrade-test')
                    """.formatted(organizationId));
            statement.executeUpdate("""
                    insert into user_accounts(id,username,password_hash,display_name)
                    values ('%s','upgrade-user','test-hash','升级测试成员')
                    """.formatted(userId));
            statement.executeUpdate("""
                    insert into memberships(id,organization_id,user_id)
                    values ('%s','%s','%s')
                    """.formatted(membershipId, organizationId, userId));
            statement.executeUpdate("""
                    insert into customers(
                      id,organization_id,owner_membership_id,visibility,full_name,phone,normalized_phone,
                      identity_type,intention_level,urgency_level,verification_status,current_status,
                      created_by_membership_id
                    ) values
                      ('%s','%s','%s','PRIVATE','已成单历史客户','13800000001','13800000001',
                       '在校生','高','中','已核实','跟进中','%s'),
                      ('%s','%s','%s','PRIVATE','待审核历史客户','13800000002','13800000002',
                       '在校生','高','中','已核实','跟进中','%s'),
                      ('%s','%s','%s','PRIVATE','多等级历史客户','13800000003','13800000003',
                       '在校生','中','中','已核实','跟进中','%s')
                    """.formatted(
                    wonCustomerId, organizationId, membershipId, membershipId,
                    pendingCustomerId, organizationId, membershipId, membershipId,
                    ambiguousQualityCustomerId, organizationId, membershipId, membershipId));
            statement.executeUpdate("""
                    insert into customer_business_cycles(
                      id,organization_id,customer_id,cycle_number,cycle_type,title,status,
                      won_declared_by_membership_id,won_declared_at,won_confirmed_by_membership_id,
                      won_confirmed_at,idempotency_key_hash,created_by_membership_id
                    ) values
                      ('%s','%s','%s',1,'INITIAL','历史已成单','WON','%s',now(),'%s',now(),'%s','%s'),
                      ('%s','%s','%s',1,'INITIAL','历史待确认','WON_PENDING','%s',now(),null,null,'%s','%s')
                    """.formatted(
                    wonCycleId, organizationId, wonCustomerId, membershipId, membershipId,
                    "a".repeat(64), membershipId,
                    pendingCycleId, organizationId, pendingCustomerId, membershipId,
                    "b".repeat(64), membershipId));
            statement.executeUpdate("""
                    insert into customer_tags(customer_id,tag_id)
                    select '%s',tag.id from tags tag
                    where tag.organization_id='%s' and tag.name='A类用户'
                    """.formatted(pendingCustomerId, organizationId));
            statement.executeUpdate("""
                    insert into customer_tags(customer_id,tag_id)
                    select '%s',tag.id from tags tag
                    where tag.organization_id='%s' and tag.name in ('A类用户','B类用户')
                    """.formatted(ambiguousQualityCustomerId, organizationId));
            try (var missingLegacyClassification = statement.executeQuery("""
                    select count(*) from customer_classifications where customer_id='%s'
                    """.formatted(pendingCustomerId))) {
                assertThat(missingLegacyClassification.next()).isTrue();
                assertThat(missingLegacyClassification.getInt(1)).isZero();
            }
        }

        Flyway upgradedDatabase = Flyway.configure()
                .dataSource(upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        var upgradeResult = upgradedDatabase.migrate();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(21);
        assertThat(upgradedDatabase.info().current().getVersion().getVersion()).isEqualTo("64");

        try (var connection = DriverManager.getConnection(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var mobileTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('mobile_device_sessions','mobile_access_tokens','mobile_refresh_tokens',
                      'mobile_push_registrations','mobile_push_preferences','mobile_push_outbox',
                      'mobile_push_deliveries','mobile_webview_tickets','mobile_app_releases',
                      'customer_deal_requests','customer_deal_request_events')
                    """)) {
                assertThat(mobileTables.next()).isTrue();
                assertThat(mobileTables.getInt(1)).isEqualTo(11);
            }
            try (var noLegacyReason = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='customer_deal_requests'
                      and column_name='rejection_reason'
                    """)) {
                assertThat(noLegacyReason.next()).isTrue();
                assertThat(noLegacyReason.getInt(1)).isZero();
            }
            try (var feishuCollaborationTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('feishu_identity_bindings','feishu_oauth_intents','feishu_event_inbox',
                      'feishu_inbound_changes','feishu_customer_drafts')
                    """)) {
                assertThat(feishuCollaborationTables.next()).isTrue();
                assertThat(feishuCollaborationTables.getInt(1)).isEqualTo(5);
            }
            try (var feishuIdentityConstraint = statement.executeQuery("""
                    select pg_get_constraintdef(oid) definition
                    from pg_constraint
                    where conname='feishu_binding_tenant_user_unique'
                    """)) {
                assertThat(feishuIdentityConstraint.next()).isTrue();
                assertThat(feishuIdentityConstraint.getString("definition"))
                        .contains("tenant_key", "feishu_user_id")
                        .doesNotContain("integration_id");
            }
            try (var feishuOauthPurposeConstraint = statement.executeQuery("""
                    select pg_get_constraintdef(oid) definition
                    from pg_constraint
                    where conname='feishu_oauth_intent_purpose_check'
                    """)) {
                assertThat(feishuOauthPurposeConstraint.next()).isTrue();
                assertThat(feishuOauthPurposeConstraint.getString("definition"))
                        .contains("LOGIN", "BIND", "SUBSCRIBE");
            }
            try (var ownerSubscriptionMarker = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='feishu_bitable_integrations'
                      and column_name='event_subscription_authorized_at'
                    """)) {
                assertThat(ownerSubscriptionMarker.next()).isTrue();
                assertThat(ownerSubscriptionMarker.getInt(1)).isEqualTo(1);
            }
            try (var dealBotTables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema='public'
                    and table_name in ('customer_deal_participants','customer_deal_bonus_ledger',
                      'feishu_deal_bot_settings','feishu_deal_bot_event_inbox','feishu_deal_bot_sessions',
                      'feishu_deal_message_outbox','feishu_deal_leaderboard_runs')
                    """)) {
                assertThat(dealBotTables.next()).isTrue();
                assertThat(dealBotTables.getInt(1)).isEqualTo(7);
            }
            try (var slashWorkflowSchema = statement.executeQuery("""
                    select
                      (select count(*) from information_schema.columns
                       where table_schema='public' and table_name='customer_deal_requests'
                         and column_name='order_amount_cents') deal_order_columns,
                      (select count(*) from information_schema.columns
                       where table_schema='public' and table_name='feishu_deal_bot_sessions'
                         and column_name in ('order_amount_cents','created_customer_id',
                           'customer_created_in_session','draft_ready_at','pending_customer_name',
                           'pending_owner_membership_id','pending_duplicate_count')) session_columns,
                      (select count(*) from information_schema.columns
                       where table_schema='public' and table_name='feishu_deal_bot_settings'
                         and column_name in ('quick_customer_creation_enabled',
                           'financial_visibility_acknowledged_at')) setting_columns,
                      (select count(*) from information_schema.tables
                       where table_schema='public' and table_name='feishu_deal_bot_pending_actions') pending_tables
                    """)) {
                assertThat(slashWorkflowSchema.next()).isTrue();
                assertThat(slashWorkflowSchema.getInt("deal_order_columns")).isEqualTo(1);
                assertThat(slashWorkflowSchema.getInt("session_columns")).isEqualTo(7);
                assertThat(slashWorkflowSchema.getInt("setting_columns")).isEqualTo(2);
                assertThat(slashWorkflowSchema.getInt("pending_tables")).isEqualTo(1);
            }
            try (var release = statement.executeQuery("""
                    select count(*) from product_releases
                    where version='2026.09.07.2' and published and current
                    """)) {
                assertThat(release.next()).isTrue();
                assertThat(release.getInt(1)).isEqualTo(1);
            }
            try (var dealWebSchema = statement.executeQuery("""
                    select
                      (select count(*) from information_schema.tables
                       where table_schema='public' and table_name='feishu_deal_web_drafts') draft_tables,
                      (select count(*) from pg_indexes where schemaname='public'
                       and indexname='uq_feishu_deal_web_active_member') active_indexes,
                      (select pg_get_constraintdef(oid) from pg_constraint
                       where conname='customer_deal_confirmation_channel_check') channel_constraint
                    """)) {
                assertThat(dealWebSchema.next()).isTrue();
                assertThat(dealWebSchema.getInt("draft_tables")).isEqualTo(1);
                assertThat(dealWebSchema.getInt("active_indexes")).isEqualTo(1);
                assertThat(dealWebSchema.getString("channel_constraint")).contains("FEISHU_WEB");
            }
            try (var contactCompatibility = statement.executeQuery("""
                    select
                      (select count(*) from information_schema.columns
                       where table_schema='public' and table_name='customers'
                         and column_name='contact_completion_status') status_columns,
                      (select pg_get_constraintdef(oid) from pg_constraint
                       where conname='ck_customer_contact') contact_constraint
                    """)) {
                assertThat(contactCompatibility.next()).isTrue();
                assertThat(contactCompatibility.getInt("status_columns")).isEqualTo(1);
                assertThat(contactCompatibility.getString("contact_constraint"))
                        .contains("contact_completion_status", "飞书临时建档", "未核实");
            }
            try (var androidRelease = statement.executeQuery("""
                    select count(*) from mobile_app_releases
                    where platform='ANDROID' and version_code=8 and version_name='0.2.3' and published
                      and apk_path='android/yingge-cloud-0.2.3.apk'
                      and apk_size=5205995
                      and apk_sha256='ac1caf1fab523b70fd76e6bbc7f839e1f289ff745a90c87566afad062b25bf3a'
                    """)) {
                assertThat(androidRelease.next()).isTrue();
                assertThat(androidRelease.getInt(1)).isEqualTo(1);
            }
            try (var migratedLegacyStates = statement.executeQuery("""
                    select status, count(*) total
                    from customer_deal_requests
                    where organization_id='%s' and source='LEGACY_BUSINESS_CYCLE'
                    group by status
                    order by status
                    """.formatted(organizationId))) {
                assertThat(migratedLegacyStates.next()).isTrue();
                assertThat(migratedLegacyStates.getString("status")).isEqualTo("APPROVED");
                assertThat(migratedLegacyStates.getInt("total")).isEqualTo(1);
                assertThat(migratedLegacyStates.next()).isTrue();
                assertThat(migratedLegacyStates.getString("status")).isEqualTo("PENDING");
                assertThat(migratedLegacyStates.getInt("total")).isEqualTo(1);
                assertThat(migratedLegacyStates.next()).isFalse();
            }
            try (var legacyClassification = statement.executeQuery("""
                    select level,source,recognition_reason,jsonb_array_length(evidence_interaction_ids) evidence_count
                    from customer_classifications where customer_id='%s'
                    """.formatted(pendingCustomerId))) {
                assertThat(legacyClassification.next()).isTrue();
                assertThat(legacyClassification.getString("level")).isEqualTo("A");
                assertThat(legacyClassification.getString("source")).isEqualTo("LEGACY_IMPORT");
                assertThat(legacyClassification.getString("recognition_reason")).contains("历史标签迁移");
                assertThat(legacyClassification.getInt("evidence_count")).isZero();
                assertThat(legacyClassification.next()).isFalse();
            }
            try (var ambiguousClassification = statement.executeQuery("""
                    select count(*) from customer_classifications where customer_id='%s'
                    """.formatted(ambiguousQualityCustomerId))) {
                assertThat(ambiguousClassification.next()).isTrue();
                assertThat(ambiguousClassification.getInt(1)).isZero();
            }
        }
    }
}
