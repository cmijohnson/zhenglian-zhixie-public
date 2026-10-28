package com.yingge.cloud.migration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.customer.domain.CustomerOptions;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Component
@Profile("migration")
public class LegacyMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationRunner.class);
    private final MigrationProperties properties;
    private final JdbcTemplate postgres;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public LegacyMigrationRunner(MigrationProperties properties, JdbcTemplate postgres,
                                 TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.properties = properties;
        this.postgres = postgres;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateConfiguration();
        Path source = Path.of(properties.sqlitePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IllegalStateException("SQLite source file does not exist: " + source);
        String sourceSha = sha256(source);
        String timestamp = Instant.now().toString().replace(":", "").replace(".", "-");
        Path backup = createBackup(source, sourceSha, timestamp);
        MigrationReport report = new MigrationReport(properties.sourceKey(), sourceSha, properties.dryRun(), backup.toString());

        Class.forName("org.sqlite.JDBC");
        String sqliteUrl = "jdbc:sqlite:file:" + source + "?mode=ro&immutable=1";
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            verifySource(sqlite, report);
            LegacySnapshot snapshot = readSnapshot(sqlite, report);
            validateSnapshot(snapshot, report);
            if (!properties.dryRun()) {
                if (!report.failures.isEmpty()) {
                    throw new IllegalStateException("Legacy validation failed; run dry-run and inspect the migration report first");
                }
                UUID organizationId = UUID.fromString(properties.organizationId());
                UUID ownerId = UUID.fromString(properties.ownerMembershipId());
                verifyTarget(organizationId, ownerId);
                UUID runId = UUID.randomUUID();
                postgres.update("""
                        insert into migration_runs (id, organization_id, source_key, source_sha256, dry_run, status)
                        values (?, ?, ?, ?, false, 'RUNNING')
                        """, runId, organizationId, properties.sourceKey(), sourceSha);
                try {
                    migrate(snapshot, organizationId, ownerId, report);
                    String status = report.failures.isEmpty() && report.conflicts.isEmpty() ? "COMPLETED" : "PARTIAL_FAILED";
                    postgres.update("update migration_runs set status=?, summary=?::jsonb, completed_at=now() where id=?",
                            status, objectMapper.writeValueAsString(report.asMap()), runId);
                } catch (Exception exception) {
                    postgres.update("update migration_runs set status='FAILED', summary=?::jsonb, completed_at=now() where id=?",
                            objectMapper.writeValueAsString(report.asMap()), runId);
                    throw exception;
                }
            }
        }
        Path reportPath = writeReport(report, timestamp);
        log.info("Migration finished dryRun={} report={}", properties.dryRun(), reportPath);
    }

    private void validateConfiguration() {
        if (blank(properties.sqlitePath())) throw new IllegalStateException("MIGRATION_SQLITE_PATH is required");
        if (blank(properties.sourceKey())) throw new IllegalStateException("MIGRATION_SOURCE_KEY is required");
        if (!properties.dryRun()) {
            if (blank(properties.organizationId())) throw new IllegalStateException("MIGRATION_ORGANIZATION_ID is required");
            if (blank(properties.ownerMembershipId())) throw new IllegalStateException("MIGRATION_OWNER_MEMBERSHIP_ID is required");
        }
    }

    private Path createBackup(Path source, String sha, String timestamp) throws Exception {
        Path directory = Path.of(properties.backupDir()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path destination = directory.resolve("legacy-" + timestamp + ".sqlite");
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        String copySha = sha256(destination);
        if (!sha.equals(copySha)) throw new IllegalStateException("SQLite backup checksum mismatch");
        Files.writeString(destination.resolveSibling(destination.getFileName() + ".sha256"), copySha + "  " + destination.getFileName() + "\n");
        return destination;
    }

    private void verifySource(Connection sqlite, MigrationReport report) throws Exception {
        try (Statement statement = sqlite.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new IllegalStateException("SQLite quick_check failed");
                }
            }
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (result.next()) throw new IllegalStateException("SQLite foreign key check failed");
            }
            List<String> required = List.of("student_profiles", "interaction_logs", "tags", "profile_tags",
                    "saved_filters", "reminder_events", "app_settings");
            for (String table : required) {
                try (ResultSet result = statement.executeQuery(
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'")) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Missing legacy table: " + table);
                }
            }
            report.checks.put("quickCheck", "ok");
            report.checks.put("foreignKeys", "ok");
        }
    }

    private LegacySnapshot readSnapshot(Connection sqlite, MigrationReport report) throws Exception {
        List<LegacyTag> tags = query(sqlite, "select * from tags order by id", row -> new LegacyTag(
                longValue(row, "id"), string(row, "name"), string(row, "category"), string(row, "color"), bool(row, "is_system"), hash(row)));
        List<LegacyCustomer> customers = query(sqlite, "select * from student_profiles order by id", row -> {
            List<String> focus;
            try { focus = objectMapper.readValue(string(row, "focus_directions"), new TypeReference<>() {}); }
            catch (Exception exception) { focus = List.of(); }
            return new LegacyCustomer(
                    longValue(row, "id"), string(row, "full_name"), string(row, "nickname"), string(row, "phone"),
                    string(row, "wechat_id"), string(row, "qq_id"), string(row, "school"), string(row, "major"),
                    string(row, "grade"), string(row, "city"), string(row, "identity_type"), focus,
                    string(row, "demand_summary"), string(row, "intention_level"), string(row, "urgency_level"),
                    string(row, "verification_status"), string(row, "source_channel"), string(row, "current_status"),
                    string(row, "owner_notes"), string(row, "created_at"), string(row, "updated_at"),
                    string(row, "last_contact_at"), string(row, "next_follow_up_at"), bool(row, "is_archived"), hash(row));
        });
        List<LegacyInteraction> interactions = query(sqlite, "select * from interaction_logs order by id", row -> new LegacyInteraction(
                longValue(row, "id"), longValue(row, "student_profile_id"), string(row, "contacted_at"),
                string(row, "channel"), string(row, "summary"), string(row, "details"), string(row, "outcome"),
                string(row, "verification_todo"), string(row, "next_follow_up_at"), string(row, "created_at"), hash(row)));
        List<LegacyReminder> reminders = query(sqlite, "select * from reminder_events order by id", row -> new LegacyReminder(
                longValue(row, "id"), longValue(row, "student_profile_id"), nullableLong(row, "interaction_log_id"),
                string(row, "remind_at"), string(row, "title"), string(row, "status"), bool(row, "notification_sent"),
                string(row, "created_at"), string(row, "updated_at"), hash(row)));
        List<LegacyProfileTag> profileTags = query(sqlite, "select * from profile_tags order by student_profile_id, tag_id",
                row -> new LegacyProfileTag(longValue(row, "student_profile_id"), longValue(row, "tag_id")));
        List<LegacySavedFilter> filters = query(sqlite, "select * from saved_filters order by id", row -> new LegacySavedFilter(
                longValue(row, "id"), string(row, "name"), string(row, "filters_json"), string(row, "sort_by"),
                bool(row, "show_on_homepage"), string(row, "created_at"), hash(row)));
        String defaultReminderTime = null;
        try (Statement statement = sqlite.createStatement();
             ResultSet result = statement.executeQuery("select value from app_settings where key='defaultReminderTime'")) {
            if (result.next()) defaultReminderTime = result.getString(1);
        }
        report.counts.put("legacyCustomers", customers.size());
        report.counts.put("legacyInteractions", interactions.size());
        report.counts.put("legacyReminders", reminders.size());
        report.counts.put("legacyTags", tags.size());
        report.counts.put("legacySavedFilters", filters.size());
        return new LegacySnapshot(tags, customers, interactions, reminders, profileTags, filters, defaultReminderTime);
    }

    private void validateSnapshot(LegacySnapshot snapshot, MigrationReport report) {
        for (LegacyCustomer customer : snapshot.customers) {
            if (blank(customer.fullName)) report.failures.add("student_profiles/" + customer.id + ": blank full_name");
            if (blank(customer.phone) && blank(customer.wechatId) && blank(customer.qqId)) {
                report.failures.add("student_profiles/" + customer.id + ": no contact method");
            }
            validateTime(customer.createdAt, "student_profiles/" + customer.id + "/created_at", report);
            validateTime(customer.updatedAt, "student_profiles/" + customer.id + "/updated_at", report);
        }
        for (LegacyInteraction interaction : snapshot.interactions) {
            if (blank(interaction.summary)) report.failures.add("interaction_logs/" + interaction.id + ": blank summary");
            validateTime(interaction.contactedAt, "interaction_logs/" + interaction.id + "/contacted_at", report);
        }
        report.checks.put("validatedRows", snapshot.customers.size() + snapshot.interactions.size() + snapshot.reminders.size());
    }

    private void migrate(LegacySnapshot snapshot, UUID organizationId, UUID ownerId, MigrationReport report) {
        Map<Long, UUID> tagIds = new LinkedHashMap<>();
        processBatches(snapshot.tags, tag -> migrateTag(tag, organizationId), report).forEach(outcome -> {
            if (outcome.targetId != null) tagIds.put(Long.parseLong(outcome.legacyId), outcome.targetId);
        });

        Map<Long, UUID> customerIds = new LinkedHashMap<>();
        processBatches(snapshot.customers, customer -> migrateCustomer(customer, organizationId, ownerId), report).forEach(outcome -> {
            if (outcome.targetId != null) customerIds.put(Long.parseLong(outcome.legacyId), outcome.targetId);
        });

        for (LegacyProfileTag link : snapshot.profileTags) {
            UUID customerId = customerIds.get(link.customerId);
            UUID tagId = tagIds.get(link.tagId);
            if (customerId != null && tagId != null) {
                postgres.update("insert into customer_tags (customer_id, tag_id) values (?, ?) on conflict do nothing", customerId, tagId);
            }
        }

        Map<Long, UUID> interactionIds = new LinkedHashMap<>();
        processBatches(snapshot.interactions, interaction -> migrateInteraction(interaction, organizationId, ownerId, customerIds), report)
                .forEach(outcome -> { if (outcome.targetId != null) interactionIds.put(Long.parseLong(outcome.legacyId), outcome.targetId); });
        processBatches(snapshot.reminders, reminder -> migrateReminder(reminder, organizationId, ownerId, customerIds, interactionIds), report);
        processBatches(snapshot.filters, filter -> migrateFilter(filter, organizationId, ownerId), report);

        if (!blank(snapshot.defaultReminderTime)) {
            postgres.update("update organization_settings set default_reminder_time=?::time, updated_at=now() where organization_id=?",
                    snapshot.defaultReminderTime, organizationId);
        }
    }

    private Outcome migrateTag(LegacyTag tag, UUID organizationId) {
        Mapping existing = mapping(organizationId, "tags", tag.id);
        if (existing != null && existing.sourceHash.equals(tag.hash)) return Outcome.skipped("tags", tag.id, existing.targetId);
        UUID targetId = existing == null ? UUID.randomUUID() : existing.targetId;
        postgres.update("""
                insert into tags (id, organization_id, name, category, color, is_system, active)
                values (?, ?, ?, ?, ?, ?, true)
                on conflict (organization_id, name) do update set category=excluded.category, color=excluded.color, active=true
                """, targetId, organizationId, tag.name, tag.category, tag.color, tag.system);
        UUID persistedId = postgres.queryForObject(
                "select id from tags where organization_id=? and name=?", UUID.class, organizationId, tag.name);
        if (existing == null) insertMapping(organizationId, "tags", tag.id, persistedId, tag.hash, null, Map.of());
        else updateMapping(existing.id, tag.hash, null, Map.of());
        return existing == null ? Outcome.created("tags", tag.id, persistedId) : Outcome.updated("tags", tag.id, persistedId);
    }

    private Outcome migrateCustomer(LegacyCustomer source, UUID organizationId, UUID ownerId) {
        Mapping existing = mapping(organizationId, "student_profiles", source.id);
        Map<String, Object> rawTimes = rawTimes(source.createdAt, source.updatedAt, source.lastContactAt, source.nextFollowUpAt);
        if (existing != null && existing.sourceHash.equals(source.hash)) return Outcome.skipped("student_profiles", source.id, existing.targetId);
        if (existing != null) {
            Long currentVersion = postgres.queryForObject("select version from customers where id=?", Long.class, existing.targetId);
            if (!Objects.equals(currentVersion, existing.targetVersion)) {
                return Outcome.conflict("student_profiles", source.id, existing.targetId, "source and target both changed");
            }
            postgres.update("""
                    update customers set full_name=?, nickname=?, phone=?, normalized_phone=?, wechat_id=?, normalized_wechat_id=?,
                    qq_id=?, normalized_qq_id=?, school=?, major=?, grade=?, city=?, identity_type=?, demand_summary=?,
                    intention_level=?, urgency_level=?, verification_status=?, source_channel_legacy=?, current_status=?, owner_notes=?,
                    last_contact_at=?, next_follow_up_at=?, archived_at=?, updated_at=?, version=version+1 where id=?
                    """, source.fullName, source.nickname, source.phone, TextNormalizer.contact(source.phone),
                    source.wechatId, TextNormalizer.contact(source.wechatId), source.qqId, TextNormalizer.contact(source.qqId),
                    source.school, source.major, CustomerOptions.normalizeGrade(source.grade), source.city, defaulted(source.identityType, "在校生"), source.demandSummary,
                    defaulted(source.intention, "中"), defaulted(source.urgency, "中"), defaulted(source.verification, "未核实"),
                    source.sourceChannel, source.archived ? "归档" : defaulted(source.status, "初次接触"), source.ownerNotes,
                    parseInstant(source.lastContactAt), parseInstant(source.nextFollowUpAt), source.archived ? parseInstant(source.updatedAt) : null,
                    parseInstant(source.updatedAt), existing.targetId);
            replaceFocus(existing.targetId, source.focusDirections);
            Long newVersion = postgres.queryForObject("select version from customers where id=?", Long.class, existing.targetId);
            updateMapping(existing.id, source.hash, newVersion, rawTimes);
            return Outcome.updated("student_profiles", source.id, existing.targetId);
        }

        // A NULL comparison is already false in PostgreSQL. Avoid separate
        // "? is not null" placeholders: an untyped NULL there makes the JDBC
        // driver fail with "could not determine data type of parameter".
        int duplicateCount = postgres.queryForObject("""
                select count(*) from customers where organization_id=? and
                (normalized_phone=? or normalized_wechat_id=? or normalized_qq_id=?)
                """, Integer.class, organizationId,
                TextNormalizer.contact(source.phone), TextNormalizer.contact(source.wechatId),
                TextNormalizer.contact(source.qqId));
        if (duplicateCount > 0) return Outcome.conflict("student_profiles", source.id, null, "exact contact already exists");

        UUID targetId = UUID.randomUUID();
        postgres.update("""
                insert into customers (id, organization_id, owner_membership_id, visibility, full_name, nickname,
                    phone, normalized_phone, wechat_id, normalized_wechat_id, qq_id, normalized_qq_id,
                    school, major, grade, city, identity_type, demand_summary, intention_level, urgency_level,
                    verification_status, source_channel_legacy, current_status, owner_notes, last_contact_at,
                    next_follow_up_at, archived_at, created_by_membership_id, created_at, updated_at, version)
                values (?, ?, ?, 'PRIVATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, targetId, organizationId, ownerId, source.fullName, source.nickname, source.phone,
                TextNormalizer.contact(source.phone), source.wechatId, TextNormalizer.contact(source.wechatId),
                source.qqId, TextNormalizer.contact(source.qqId), source.school, source.major, CustomerOptions.normalizeGrade(source.grade), source.city,
                defaulted(source.identityType, "在校生"), source.demandSummary, defaulted(source.intention, "中"),
                defaulted(source.urgency, "中"), defaulted(source.verification, "未核实"), source.sourceChannel,
                source.archived ? "归档" : defaulted(source.status, "初次接触"), source.ownerNotes,
                parseInstant(source.lastContactAt), parseInstant(source.nextFollowUpAt), source.archived ? parseInstant(source.updatedAt) : null,
                ownerId, parseInstant(source.createdAt), parseInstant(source.updatedAt));
        replaceFocus(targetId, source.focusDirections);
        insertMapping(organizationId, "student_profiles", source.id, targetId, source.hash, 0L, rawTimes);
        return Outcome.created("student_profiles", source.id, targetId);
    }

    private Outcome migrateInteraction(LegacyInteraction source, UUID organizationId, UUID ownerId, Map<Long, UUID> customers) {
        UUID customerId = customers.get(source.customerId);
        if (customerId == null) return Outcome.conflict("interaction_logs", source.id, null, "customer mapping missing");
        Mapping existing = mapping(organizationId, "interaction_logs", source.id);
        if (existing != null && existing.sourceHash.equals(source.hash)) return Outcome.skipped("interaction_logs", source.id, existing.targetId);
        UUID targetId = existing == null ? UUID.randomUUID() : existing.targetId;
        if (existing == null) {
            postgres.update("""
                    insert into interactions (id, organization_id, customer_id, contacted_at, channel, summary, details,
                    outcome, verification_todo, next_follow_up_at, created_by_membership_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, targetId, organizationId, customerId, parseInstant(source.contactedAt), source.channel,
                    source.summary, source.details, source.outcome, source.verificationTodo,
                    parseInstant(source.nextFollowUpAt), ownerId, parseInstant(source.createdAt));
            insertMapping(organizationId, "interaction_logs", source.id, targetId, source.hash, null,
                    rawTimes(source.contactedAt, source.createdAt, source.nextFollowUpAt));
            return Outcome.created("interaction_logs", source.id, targetId);
        }
        return Outcome.conflict("interaction_logs", source.id, targetId, "legacy interaction changed after migration");
    }

    private Outcome migrateReminder(LegacyReminder source, UUID organizationId, UUID ownerId,
                                    Map<Long, UUID> customers, Map<Long, UUID> interactions) {
        UUID customerId = customers.get(source.customerId);
        if (customerId == null) return Outcome.conflict("reminder_events", source.id, null, "customer mapping missing");
        Mapping existing = mapping(organizationId, "reminder_events", source.id);
        if (existing != null && existing.sourceHash.equals(source.hash)) return Outcome.skipped("reminder_events", source.id, existing.targetId);
        UUID targetId = existing == null ? UUID.randomUUID() : existing.targetId;
        String status = switch (source.status == null ? "pending" : source.status) {
            case "completed" -> "COMPLETED";
            case "cancelled" -> "CANCELLED";
            default -> "PENDING";
        };
        String delivery = source.notificationSent ? "SENT" : "UNSENT";
        if (existing == null) {
            postgres.update("""
                    insert into reminders (id, organization_id, customer_id, interaction_id, assigned_membership_id,
                    remind_at, title, status, delivery_status, completed_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, targetId, organizationId, customerId,
                    source.interactionId == null ? null : interactions.get(source.interactionId), ownerId,
                    parseInstant(source.remindAt), source.title, status, delivery,
                    "COMPLETED".equals(status) ? parseInstant(source.updatedAt) : null,
                    parseInstant(source.createdAt), parseInstant(source.updatedAt));
            insertMapping(organizationId, "reminder_events", source.id, targetId, source.hash, 0L,
                    rawTimes(source.remindAt, source.createdAt, source.updatedAt));
            return Outcome.created("reminder_events", source.id, targetId);
        }
        return Outcome.conflict("reminder_events", source.id, targetId, "legacy reminder changed after migration");
    }

    private Outcome migrateFilter(LegacySavedFilter source, UUID organizationId, UUID ownerId) {
        Mapping existing = mapping(organizationId, "saved_filters", source.id);
        if (existing != null && existing.sourceHash.equals(source.hash)) return Outcome.skipped("saved_filters", source.id, existing.targetId);
        UUID targetId = existing == null ? UUID.randomUUID() : existing.targetId;
        if (existing == null) {
            postgres.update("""
                    insert into saved_filters (id, organization_id, owner_membership_id, name, filters, sort_by,
                    show_on_homepage, created_at, updated_at) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    on conflict (owner_membership_id, name) do nothing
                    """, targetId, organizationId, ownerId, source.name, source.filtersJson, source.sortBy,
                    source.showOnHomepage, parseInstant(source.createdAt), parseInstant(source.createdAt));
            insertMapping(organizationId, "saved_filters", source.id, targetId, source.hash, null, rawTimes(source.createdAt));
            return Outcome.created("saved_filters", source.id, targetId);
        }
        return Outcome.conflict("saved_filters", source.id, targetId, "legacy saved filter changed after migration");
    }

    private <T> List<Outcome> processBatches(List<T> source, Function<T, Outcome> operation, MigrationReport report) {
        List<Outcome> all = new ArrayList<>();
        int batchSize = Math.max(1, properties.batchSize());
        for (int offset = 0; offset < source.size(); offset += batchSize) {
            List<T> batch = source.subList(offset, Math.min(offset + batchSize, source.size()));
            try {
                List<Outcome> outcomes = transactions.execute(status -> batch.stream().map(operation).toList());
                if (outcomes != null) all.addAll(outcomes);
            } catch (RuntimeException batchFailure) {
                for (T row : batch) {
                    try {
                        Outcome outcome = transactions.execute(status -> operation.apply(row));
                        if (outcome != null) all.add(outcome);
                    } catch (RuntimeException rowFailure) {
                        Throwable cause = rowFailure;
                        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                        log.warn("Migration row failed: {}", message);
                        report.failures.add(message);
                    }
                }
            }
        }
        all.forEach(report::apply);
        return all;
    }

    private void replaceFocus(UUID customerId, List<String> directions) {
        postgres.update("delete from customer_focus_directions where customer_id=?", customerId);
        List<String> normalized = directions == null || directions.isEmpty() ? List.of("暂不明确") : directions;
        normalized.stream().filter(value -> !blank(value)).distinct().forEach(value ->
                postgres.update("insert into customer_focus_directions (customer_id, direction) values (?, ?) on conflict do nothing",
                        customerId, value.trim()));
    }

    private Mapping mapping(UUID organizationId, String table, long legacyId) {
        List<Mapping> rows = postgres.query("""
                        select id, target_id, source_row_hash, target_version from legacy_id_mappings
                        where organization_id=? and source_key=? and source_table=? and legacy_id=?
                        """, (result, index) -> new Mapping(result.getObject("id", UUID.class),
                        result.getObject("target_id", UUID.class), result.getString("source_row_hash"),
                        result.getObject("target_version", Long.class)),
                organizationId, properties.sourceKey(), table, Long.toString(legacyId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertMapping(UUID organizationId, String table, long legacyId, UUID targetId,
                               String hash, Long targetVersion, Map<String, Object> rawTimes) {
        try {
            postgres.update("""
                    insert into legacy_id_mappings (id, organization_id, source_key, source_table, legacy_id,
                    target_id, source_row_hash, target_version, raw_times)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), organizationId, properties.sourceKey(), table, Long.toString(legacyId),
                    targetId, hash, targetVersion, objectMapper.writeValueAsString(rawTimes));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private void updateMapping(UUID mappingId, String hash, Long targetVersion, Map<String, Object> rawTimes) {
        try {
            postgres.update("update legacy_id_mappings set source_row_hash=?, target_version=?, raw_times=?::jsonb, migrated_at=now() where id=?",
                    hash, targetVersion, objectMapper.writeValueAsString(rawTimes), mappingId);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private void verifyTarget(UUID organizationId, UUID ownerId) {
        Integer valid = postgres.queryForObject("""
                select count(*) from memberships where id=? and organization_id=? and status='ACTIVE'
                """, Integer.class, ownerId, organizationId);
        if (valid == null || valid != 1) throw new IllegalStateException("Migration owner membership is invalid");
    }

    private Path writeReport(MigrationReport report, String timestamp) throws Exception {
        Path directory = Path.of(properties.reportDir()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path json = directory.resolve("migration-" + timestamp + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report.asMap());
        Path csv = directory.resolve("migration-" + timestamp + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("category,value\n");
            for (var entry : report.counts.entrySet()) writer.write(entry.getKey() + "," + entry.getValue() + "\n");
            for (String conflict : report.conflicts) writer.write("conflict,\"" + conflict.replace("\"", "\"\"") + "\"\n");
            for (String failure : report.failures) writer.write("failure,\"" + failure.replace("\"", "\"\"") + "\"\n");
        }
        return json;
    }

    private <T> List<T> query(Connection sqlite, String sql, RowMapper<T> mapper) throws Exception {
        List<T> rows = new ArrayList<>();
        try (Statement statement = sqlite.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) rows.add(mapper.map(row(result)));
        }
        return rows;
    }

    private Map<String, Object> row(ResultSet result) throws Exception {
        ResultSetMetaData metadata = result.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnName(i), result.getObject(i));
        return row;
    }

    private String hash(Map<String, Object> row) {
        try { return sha256(objectMapper.writeValueAsBytes(row)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String sha256(Path path) throws Exception { return sha256(Files.readAllBytes(path)); }
    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
    private String string(Map<String, Object> row, String key) { return row.get(key) == null ? null : row.get(key).toString(); }
    private long longValue(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Long nullableLong(Map<String, Object> row, String key) { return row.get(key) == null ? null : ((Number) row.get(key)).longValue(); }
    private boolean bool(Map<String, Object> row, String key) { return row.get(key) != null && ((Number) row.get(key)).intValue() == 1; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String defaulted(String value, String fallback) { return blank(value) ? fallback : value.trim(); }

    private OffsetDateTime parseInstant(String value) {
        if (blank(value)) return null;
        try { return Instant.parse(value).atOffset(java.time.ZoneOffset.UTC); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(value); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("Invalid legacy timestamp: " + value); }
    }

    private void validateTime(String value, String field, MigrationReport report) {
        if (blank(value)) return;
        try { parseInstant(value); } catch (RuntimeException exception) { report.failures.add(field + ": " + value); }
    }

    private Map<String, Object> rawTimes(String... values) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) if (values[i] != null) raw.put("time" + (i + 1), values[i]);
        return raw;
    }

    @FunctionalInterface private interface RowMapper<T> { T map(Map<String, Object> row) throws Exception; }
    private record Mapping(UUID id, UUID targetId, String sourceHash, Long targetVersion) {}
    private record Outcome(String table, String legacyId, UUID targetId, String kind, String message) {
        static Outcome created(String table, long id, UUID target) { return new Outcome(table, Long.toString(id), target, "created", null); }
        static Outcome updated(String table, long id, UUID target) { return new Outcome(table, Long.toString(id), target, "updated", null); }
        static Outcome skipped(String table, long id, UUID target) { return new Outcome(table, Long.toString(id), target, "skipped", null); }
        static Outcome conflict(String table, long id, UUID target, String message) { return new Outcome(table, Long.toString(id), target, "conflict", message); }
    }

    private static final class MigrationReport {
        private final String sourceKey;
        private final String sourceSha256;
        private final boolean dryRun;
        private final String backupPath;
        private final Map<String, Object> checks = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final List<String> conflicts = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private MigrationReport(String sourceKey, String sourceSha256, boolean dryRun, String backupPath) {
            this.sourceKey = sourceKey; this.sourceSha256 = sourceSha256; this.dryRun = dryRun; this.backupPath = backupPath;
        }
        private void apply(Outcome outcome) {
            counts.merge(outcome.kind, 1, Integer::sum);
            if ("conflict".equals(outcome.kind)) conflicts.add(outcome.table + "/" + outcome.legacyId + ": " + outcome.message);
        }
        private Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sourceKey", sourceKey); map.put("sourceSha256", sourceSha256); map.put("dryRun", dryRun);
            map.put("backupPath", backupPath); map.put("checks", checks); map.put("counts", counts);
            map.put("conflicts", conflicts); map.put("failures", failures); map.put("completedAt", Instant.now());
            return map;
        }
    }

    private record LegacySnapshot(List<LegacyTag> tags, List<LegacyCustomer> customers,
                                  List<LegacyInteraction> interactions, List<LegacyReminder> reminders,
                                  List<LegacyProfileTag> profileTags, List<LegacySavedFilter> filters,
                                  String defaultReminderTime) {}
    private record LegacyTag(long id, String name, String category, String color, boolean system, String hash) {}
    private record LegacyCustomer(long id, String fullName, String nickname, String phone, String wechatId, String qqId,
                                  String school, String major, String grade, String city, String identityType,
                                  List<String> focusDirections, String demandSummary, String intention, String urgency,
                                  String verification, String sourceChannel, String status, String ownerNotes,
                                  String createdAt, String updatedAt, String lastContactAt, String nextFollowUpAt,
                                  boolean archived, String hash) {}
    private record LegacyInteraction(long id, long customerId, String contactedAt, String channel, String summary,
                                     String details, String outcome, String verificationTodo,
                                     String nextFollowUpAt, String createdAt, String hash) {}
    private record LegacyReminder(long id, long customerId, Long interactionId, String remindAt, String title,
                                  String status, boolean notificationSent, String createdAt, String updatedAt, String hash) {}
    private record LegacyProfileTag(long customerId, long tagId) {}
    private record LegacySavedFilter(long id, String name, String filtersJson, String sortBy,
                                     boolean showOnHomepage, String createdAt, String hash) {}
}
