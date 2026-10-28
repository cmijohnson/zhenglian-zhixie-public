package com.yingge.cloud.migration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMigrationDryRunTest {
    @TempDir
    Path temporary;

    @Test
    void dryRunKeepsSourceImmutableAndWritesReportsAndVerifiedBackup() throws Exception {
        Path source = temporary.resolve("legacy.sqlite");
        createFixture(source);
        String before = sha256(source);
        Path backups = temporary.resolve("backups");
        Path reports = temporary.resolve("reports");
        var properties = new MigrationProperties(
                source.toString(), "fixture", null, null, true, 200,
                backups.toString(), reports.toString());
        var runner = new LegacyMigrationRunner(properties, null, null, new ObjectMapper().findAndRegisterModules());

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(sha256(source)).isEqualTo(before);
        assertThat(Files.list(backups).filter(path -> path.toString().endsWith(".sqlite"))).hasSize(1);
        assertThat(Files.list(reports).filter(path -> path.toString().endsWith(".json"))).hasSize(1);
        assertThat(Files.readString(Files.list(reports).filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow()))
                .contains("\"legacyCustomers\" : 1", "\"dryRun\" : true");
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
                      1, '测试客户', null, '138-0013-8000', null, null, '测试大学', '计算机', '大二', '示例市',
                      '在校生', '[\"就业\"]', '咨询课程', '高', '中', '未核实', '朋友推荐', '跟进中', null,
                      '2026-01-02T10:00:00', '2026-01-02T10:00:00', null, null, 0
                    )
                    """);
        }
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

