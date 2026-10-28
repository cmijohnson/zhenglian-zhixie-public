package com.yingge.cloud.migration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LegacyMigrationCommitIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_migration")
            .withUsername("yingge_migration")
            .withPassword("migration-test-password");

    @TempDir
    Path temporary;

    @Test
    void commitsNullableContactsAndAllDependentsThenRerunsIdempotently() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        UUID organizationId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        jdbc.update("insert into organizations (id,name,code) values (?,?,?)",
                organizationId, "迁移测试组织", "migration-test");
        jdbc.update("insert into organization_settings (organization_id) values (?)", organizationId);
        jdbc.update("insert into user_accounts (id,username,password_hash,display_name) values (?,?,?,?)",
                accountId, "migration-owner", "not-used", "迁移负责人");
        jdbc.update("insert into memberships (id,organization_id,user_id) values (?,?,?)",
                ownerId, organizationId, accountId);

        Path source = temporary.resolve("legacy.sqlite");
        createFixture(source);
        String sourceSha = sha256(source);
        var properties = new MigrationProperties(
                source.toString(), "commit-fixture", organizationId.toString(), ownerId.toString(), false, 50,
                temporary.resolve("backups").toString(), temporary.resolve("reports").toString());
        var runner = new LegacyMigrationRunner(properties, jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper().findAndRegisterModules());

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(sha256(source)).isEqualTo(sourceSha);
        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from interactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reminders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from saved_filters", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from migration_runs where source_key='commit-fixture'", String.class))
                .isEqualTo("COMPLETED");

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from interactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reminders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from saved_filters", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from migration_runs where source_key='commit-fixture' and status='COMPLETED'",
                Integer.class)).isEqualTo(2);
    }

    private void createFixture(Path source) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement()) {
            statement.execute("""
                    create table student_profiles (
                      id integer primary key, full_name text, nickname text, phone text, wechat_id text, qq_id text,
                      school text, major text, grade text, city text, identity_type text, focus_directions text,
                      demand_summary text, intention_level text, urgency_level text, verification_status text,
                      source_channel text, current_status text, owner_notes text, created_at text, updated_at text,
                      last_contact_at text, next_follow_up_at text, is_archived integer
                    )
                    """);
            statement.execute("""
                    create table interaction_logs (
                      id integer primary key, student_profile_id integer, contacted_at text, channel text,
                      summary text, details text, outcome text, verification_todo text, next_follow_up_at text,
                      created_at text, foreign key(student_profile_id) references student_profiles(id)
                    )
                    """);
            statement.execute("create table tags (id integer primary key, name text, category text, color text, is_system integer)");
            statement.execute("create table profile_tags (student_profile_id integer, tag_id integer, primary key(student_profile_id, tag_id), foreign key(student_profile_id) references student_profiles(id), foreign key(tag_id) references tags(id))");
            statement.execute("create table saved_filters (id integer primary key, name text, filters_json text, sort_by text, show_on_homepage integer, created_at text)");
            statement.execute("create table reminder_events (id integer primary key, student_profile_id integer, interaction_log_id integer, remind_at text, title text, status text, notification_sent integer, created_at text, updated_at text, foreign key(student_profile_id) references student_profiles(id), foreign key(interaction_log_id) references interaction_logs(id))");
            statement.execute("create table app_settings (key text primary key, value text)");
            statement.execute("""
                    insert into student_profiles values (
                      1, '迁移客户', null, '138-0013-8000', null, null, '测试大学', '计算机', '大二', '示例市',
                      '在校生', '["就业"]', '咨询课程', '高', '中', '未核实', '朋友推荐', '跟进中', null,
                      '2026-01-02T10:00:00+08:00', '2026-01-02T10:30:00+08:00', null, null, 0
                    )
                    """);
            statement.execute("insert into tags values (1, '迁移标签', '业务', '#3366ff', 0)");
            statement.execute("insert into profile_tags values (1, 1)");
            statement.execute("""
                    insert into interaction_logs values (
                      1, 1, '2026-01-02T10:15:00+08:00', '电话', '迁移沟通', '详情', '继续跟进', null, null,
                      '2026-01-02T10:15:00+08:00'
                    )
                    """);
            statement.execute("""
                    insert into reminder_events values (
                      1, 1, 1, '2026-01-03T09:00:00+08:00', '迁移提醒', 'pending', 0,
                      '2026-01-02T10:20:00+08:00', '2026-01-02T10:20:00+08:00'
                    )
                    """);
            statement.execute("""
                    insert into saved_filters values (
                      1, '迁移筛选', '{"grade":"大二"}', 'updatedAt', 1, '2026-01-02T10:00:00+08:00'
                    )
                    """);
            statement.execute("insert into app_settings values ('default_reminder_time', '09:15')");
        }
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
