package com.yingge.cloud.enrichment.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.domain.CustomerOptions;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CustomerEnrichmentService {
    private static final long MAX_XLSX_BYTES = 20L * 1024 * 1024;
    private static final Pattern CLASS_GRADE = Pattern.compile("(?<!\\d)(\\d{2})\\d{2}(?!\\d)");
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public CustomerEnrichmentService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors, AuditService audit) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional
    public Preview preview(MultipartFile file) {
        CurrentActor actor = requireAdmin();
        if (file == null || file.isEmpty()) throw ApiException.badRequest("ENRICHMENT_FILE_EMPTY", "补全文件不能为空");
        String filename = file.getOriginalFilename() == null ? "客户资料补全.xlsx" : file.getOriginalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw ApiException.badRequest("ENRICHMENT_XLSX_REQUIRED", "客户资料补全仅支持 XLSX 文件");
        }
        if (file.getSize() > MAX_XLSX_BYTES) {
            throw ApiException.badRequest("ENRICHMENT_FILE_TOO_LARGE", "客户资料补全文件不能超过 20 MB");
        }
        byte[] bytes;
        ParsedWorkbook parsed;
        try {
            bytes = file.getBytes();
            parsed = parse(bytes);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.badRequest("ENRICHMENT_PARSE_FAILED", "无法解析补全文件，请检查模板和工作表名称");
        }
        String sha256 = sha256(bytes);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id, status, expires_at from customer_enrichment_jobs
                where organization_id=:organizationId and file_sha256=:sha256
                order by created_at desc limit 1
                """, params("organizationId", actor.organizationId(), "sha256", sha256));
        if (!existing.isEmpty()) {
            Map<String, Object> previous = existing.getFirst();
            Instant previousExpiry = ((java.sql.Timestamp) previous.get("expires_at")).toInstant();
            if ("COMPLETED".equals(previous.get("status")) || previousExpiry.isAfter(Instant.now())) {
                return report((UUID) previous.get("id"));
            }
            jdbc.update("delete from customer_enrichment_jobs where id=:id",
                    params("id", previous.get("id")));
        }

        UUID jobId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        jdbc.update("""
                insert into customer_enrichment_jobs
                  (id, organization_id, created_by_membership_id, filename, file_sha256, expires_at)
                values (:id, :organizationId, :membershipId, :filename, :sha256, :expiresAt)
                """, params("id", jobId, "organizationId", actor.organizationId(),
                "membershipId", actor.membershipId(), "filename", filename, "sha256", sha256,
                "expiresAt", Timestamp.from(expiresAt)));
        int ready = 0;
        int skipped = 0;
        int conflicts = 0;
        for (SourceRow source : parsed.rows()) {
            RowDecision decision = decide(actor.organizationId(), source, parsed.locationMappings());
            if ("READY".equals(decision.status())) ready++;
            else if ("CONFLICT".equals(decision.status()) || "AMBIGUOUS".equals(decision.status())) conflicts++;
            else skipped++;
            jdbc.update("""
                    insert into customer_enrichment_rows
                      (id, job_id, row_number, full_name, major, grade, high_school, source_location,
                       mapping_status, customer_id, row_status, message)
                    values (:id, :jobId, :rowNumber, :fullName, :major, :grade, :highSchool,
                       :sourceLocation, :mappingStatus, :customerId, :rowStatus, :message)
                    """, params("id", UUID.randomUUID(), "jobId", jobId, "rowNumber", source.rowNumber(),
                    "fullName", source.fullName(), "major", source.major(), "grade", source.grade(),
                    "highSchool", source.highSchool(), "sourceLocation", decision.sourceLocation(),
                    "mappingStatus", decision.mappingStatus(), "customerId", decision.customerId(),
                    "rowStatus", decision.status(), "message", decision.message()));
        }
        jdbc.update("""
                update customer_enrichment_jobs set total_rows=:total, ready_rows=:ready,
                  skipped_rows=:skipped, conflict_rows=:conflicts where id=:id
                """, params("total", parsed.rows().size(), "ready", ready, "skipped", skipped,
                "conflicts", conflicts, "id", jobId));
        audit.record(actor, "CUSTOMER_ENRICHMENT_PREVIEWED", "CUSTOMER_ENRICHMENT_JOB", jobId.toString(),
                "SUCCESS", Map.of("rows", parsed.rows().size(), "ready", ready));
        return report(jobId);
    }

    @Transactional
    public Preview commit(UUID jobId) {
        CurrentActor actor = requireAdmin();
        List<Map<String, Object>> jobs = jdbc.queryForList("""
                select id, status, expires_at from customer_enrichment_jobs
                where id=:id and organization_id=:organizationId for update
                """, params("id", jobId, "organizationId", actor.organizationId()));
        if (jobs.isEmpty()) throw ApiException.notFound("ENRICHMENT_JOB_NOT_FOUND", "补全任务不存在");
        String status = (String) jobs.getFirst().get("status");
        if ("COMPLETED".equals(status)) return report(jobId);
        Instant expiresAt = ((java.sql.Timestamp) jobs.getFirst().get("expires_at")).toInstant();
        if (expiresAt.isBefore(Instant.now())) {
            jdbc.update("update customer_enrichment_jobs set status='EXPIRED' where id=:id", params("id", jobId));
            throw ApiException.conflict("ENRICHMENT_PREVIEW_EXPIRED", "补全预览已超过 24 小时，请重新上传");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, customer_id, high_school, source_location from customer_enrichment_rows
                where job_id=:jobId and row_status='READY' order by row_number
                """, params("jobId", jobId));
        int updated = 0;
        int skippedAtCommit = 0;
        for (Map<String, Object> row : rows) {
            UUID customerId = (UUID) row.get("customer_id");
            String highSchool = (String) row.get("high_school");
            String sourceLocation = (String) row.get("source_location");
            int changed = jdbc.update("""
                    update customers set high_school=coalesce(high_school, :highSchool),
                      source_location=case
                        when source_location is null and (high_school is null or high_school=:highSchool)
                        then :sourceLocation else source_location end,
                      updated_at=now(), version=version+1
                    where id=:customerId and organization_id=:organizationId
                      and archived_at is null and (high_school is null or high_school=:highSchool)
                      and (high_school is null or source_location is null)
                    """, params("highSchool", highSchool, "sourceLocation", sourceLocation,
                    "customerId", customerId, "organizationId", actor.organizationId()));
            if (changed == 1) {
                updated++;
                jdbc.update("""
                        update customer_enrichment_rows set row_status='UPDATED',
                          applied_high_school=(select high_school=:highSchool from customers where id=:customerId),
                          applied_source_location=(select source_location=:sourceLocation from customers where id=:customerId)
                        where id=:rowId
                        """, params("highSchool", highSchool, "sourceLocation", sourceLocation,
                        "customerId", customerId, "rowId", row.get("id")));
            } else {
                skippedAtCommit++;
                jdbc.update("""
                        update customer_enrichment_rows set row_status='CONFLICT',
                          message='提交时客户资料已变化，未覆盖现有值' where id=:rowId
                        """, params("rowId", row.get("id")));
            }
        }
        jdbc.update("""
                update customer_enrichment_jobs set status='COMPLETED', updated_rows=:updated,
                  skipped_rows=skipped_rows+:skippedAtCommit, conflict_rows=conflict_rows+:skippedAtCommit,
                  completed_at=now() where id=:jobId
                """, params("updated", updated, "skippedAtCommit", skippedAtCommit, "jobId", jobId));
        audit.record(actor, "CUSTOMER_ENRICHMENT_COMMITTED", "CUSTOMER_ENRICHMENT_JOB", jobId.toString(),
                "SUCCESS", Map.of("updated", updated, "skippedAtCommit", skippedAtCommit));
        return report(jobId);
    }

    @Transactional(readOnly = true)
    public Preview report(UUID jobId) {
        CurrentActor actor = requireAdmin();
        List<JobSummary> jobs = jdbc.query("""
                select id, filename, file_sha256, status, total_rows, ready_rows, updated_rows,
                  skipped_rows, conflict_rows, expires_at, created_at, completed_at
                from customer_enrichment_jobs where id=:id and organization_id=:organizationId
                """, params("id", jobId, "organizationId", actor.organizationId()), this::mapJob);
        if (jobs.isEmpty()) throw ApiException.notFound("ENRICHMENT_JOB_NOT_FOUND", "补全任务不存在");
        List<RowResult> rows = jdbc.query("""
                select row_number, full_name, major, grade, high_school, source_location,
                  mapping_status, customer_id, row_status, message,
                  applied_high_school, applied_source_location
                from customer_enrichment_rows where job_id=:jobId order by row_number limit 1000
                """, params("jobId", jobId), this::mapRow);
        return new Preview(jobs.getFirst(), rows);
    }

    @Transactional(readOnly = true)
    public List<JobSummary> jobs() {
        CurrentActor actor = requireAdmin();
        return jdbc.query("""
                select id, filename, file_sha256, status, total_rows, ready_rows, updated_rows,
                  skipped_rows, conflict_rows, expires_at, created_at, completed_at
                from customer_enrichment_jobs where organization_id=:organizationId
                order by created_at desc limit 100
                """, params("organizationId", actor.organizationId()), this::mapJob);
    }

    @Transactional(readOnly = true)
    public List<RowResult> allRows(UUID jobId) {
        CurrentActor actor = requireAdmin();
        Integer count = jdbc.queryForObject("""
                select count(*) from customer_enrichment_jobs where id=:id and organization_id=:organizationId
                """, params("id", jobId, "organizationId", actor.organizationId()), Integer.class);
        if (count == null || count == 0) throw ApiException.notFound("ENRICHMENT_JOB_NOT_FOUND", "补全任务不存在");
        return jdbc.query("""
                select row_number, full_name, major, grade, high_school, source_location,
                  mapping_status, customer_id, row_status, message,
                  applied_high_school, applied_source_location
                from customer_enrichment_rows where job_id=:jobId order by row_number
                """, params("jobId", jobId), this::mapRow);
    }

    private RowDecision decide(UUID organizationId, SourceRow source, Map<String, LocationMapping> mappings) {
        if (blank(source.fullName()) || blank(source.major()) || blank(source.grade()) || blank(source.highSchool())) {
            return new RowDecision("INVALID", null, null, "INVALID", "姓名、专业、年级或入学前单位为空");
        }
        LocationMapping mapping = mappings.get(normalize(source.highSchool()));
        if (mapping == null || !"已匹配".equals(mapping.status())) {
            return new RowDecision("UNVERIFIED_LOCATION", null, mapping == null ? null : mapping.location(),
                    mapping == null ? "待核实" : mapping.status(), "所在地映射尚未核实，按规则跳过");
        }
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select id, high_school, source_location from customers
                where organization_id=:organizationId and archived_at is null
                  and lower(regexp_replace(trim(full_name), '\\s+', '', 'g'))=:fullName
                  and lower(regexp_replace(trim(coalesce(major,'')), '\\s+', '', 'g'))=:major
                  and grade=:grade
                """, params("organizationId", organizationId, "fullName", normalize(source.fullName()),
                "major", normalize(source.major()), "grade", source.grade()));
        if (candidates.isEmpty()) {
            Integer otherGrade = jdbc.queryForObject("""
                    select count(*) from customers where organization_id=:organizationId and archived_at is null
                      and lower(regexp_replace(trim(full_name), '\\s+', '', 'g'))=:fullName
                      and lower(regexp_replace(trim(coalesce(major,'')), '\\s+', '', 'g'))=:major
                    """, params("organizationId", organizationId, "fullName", normalize(source.fullName()),
                    "major", normalize(source.major())), Integer.class);
            return new RowDecision(otherGrade != null && otherGrade > 0 ? "GRADE_MISMATCH" : "NO_CUSTOMER",
                    null, mapping.location(), mapping.status(),
                    otherGrade != null && otherGrade > 0 ? "姓名和专业匹配，但年级不一致" : "CRM 中没有唯一匹配客户");
        }
        if (candidates.size() > 1) {
            return new RowDecision("AMBIGUOUS", null, mapping.location(), mapping.status(), "匹配到多个客户，未自动修改");
        }
        Map<String, Object> customer = candidates.getFirst();
        String existingSchool = (String) customer.get("high_school");
        String existingLocation = (String) customer.get("source_location");
        if (!blank(existingSchool) && !normalize(existingSchool).equals(normalize(source.highSchool()))) {
            return new RowDecision("CONFLICT", (UUID) customer.get("id"), mapping.location(), mapping.status(),
                    "CRM 已有不同高中，整行跳过");
        }
        if (!blank(existingSchool) && !blank(existingLocation)) {
            return new RowDecision("ALREADY_PRESENT", (UUID) customer.get("id"), mapping.location(), mapping.status(),
                    "高中和生源地均已有值");
        }
        return new RowDecision("READY", (UUID) customer.get("id"), mapping.location(), mapping.status(), "可补充空字段");
    }

    ParsedWorkbook parse(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sourceSheet = workbook.getSheet("Sheet1");
            var mappingSheet = workbook.getSheet("所在地映射");
            if (sourceSheet == null || mappingSheet == null) {
                throw ApiException.badRequest("ENRICHMENT_TEMPLATE_INVALID", "文件必须包含 Sheet1 和 所在地映射 工作表");
            }
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            Map<String, Integer> sourceHeaders = headers(sourceSheet.getRow(sourceSheet.getFirstRowNum()), formatter);
            requireHeaders(sourceHeaders, "姓名", "专业", "入学前单位");
            Map<String, Integer> mappingHeaders = headers(mappingSheet.getRow(mappingSheet.getFirstRowNum()), formatter);
            requireHeaders(mappingHeaders, "入学前单位", "所在地", "状态");

            Map<String, LocationMapping> mappings = new LinkedHashMap<>();
            for (int index = mappingSheet.getFirstRowNum() + 1; index <= mappingSheet.getLastRowNum(); index++) {
                var row = mappingSheet.getRow(index);
                if (row == null) continue;
                String school = cell(row, mappingHeaders.get("入学前单位"), formatter);
                if (blank(school)) continue;
                mappings.put(normalize(school), new LocationMapping(
                        cell(row, mappingHeaders.get("所在地"), formatter),
                        cell(row, mappingHeaders.get("状态"), formatter)));
            }

            List<SourceRow> rows = new ArrayList<>();
            for (int index = sourceSheet.getFirstRowNum() + 1; index <= sourceSheet.getLastRowNum(); index++) {
                var row = sourceSheet.getRow(index);
                if (row == null) continue;
                String name = cell(row, sourceHeaders.get("姓名"), formatter);
                String major = cell(row, sourceHeaders.get("专业"), formatter);
                String school = cell(row, sourceHeaders.get("入学前单位"), formatter);
                String explicitGrade = sourceHeaders.containsKey("年级")
                        ? cell(row, sourceHeaders.get("年级"), formatter) : null;
                String className = sourceHeaders.containsKey("班级")
                        ? cell(row, sourceHeaders.get("班级"), formatter) : null;
                if (blank(name) && blank(major) && blank(school)) continue;
                String grade = blank(explicitGrade) ? gradeFromClass(className) : CustomerOptions.normalizeGrade(explicitGrade);
                rows.add(new SourceRow(index + 1, value(name), value(major), value(grade), value(school)));
            }
            return new ParsedWorkbook(rows, mappings);
        }
    }

    private String gradeFromClass(String className) {
        if (blank(className)) return null;
        Matcher matcher = CLASS_GRADE.matcher(className);
        if (!matcher.find()) return null;
        return "20" + matcher.group(1);
    }

    private Map<String, Integer> headers(org.apache.poi.ss.usermodel.Row row, DataFormatter formatter) {
        if (row == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            String label = formatter.formatCellValue(row.getCell(index)).trim();
            if (!label.isBlank()) result.put(label, index);
        }
        return result;
    }

    private void requireHeaders(Map<String, Integer> headers, String... required) {
        for (String label : required) {
            if (!headers.containsKey(label)) {
                throw ApiException.badRequest("ENRICHMENT_TEMPLATE_INVALID", "缺少必需列：" + label);
            }
        }
    }

    private String cell(org.apache.poi.ss.usermodel.Row row, Integer index, DataFormatter formatter) {
        return index == null ? null : value(formatter.formatCellValue(row.getCell(index)));
    }

    private JobSummary mapJob(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new JobSummary(row.getObject("id", UUID.class), row.getString("filename"), row.getString("file_sha256"),
                row.getString("status"), row.getInt("total_rows"), row.getInt("ready_rows"),
                row.getInt("updated_rows"), row.getInt("skipped_rows"), row.getInt("conflict_rows"),
                instant(row, "expires_at"), instant(row, "created_at"), instant(row, "completed_at"));
    }

    private RowResult mapRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new RowResult(row.getInt("row_number"), row.getString("full_name"), row.getString("major"),
                row.getString("grade"), row.getString("high_school"), row.getString("source_location"),
                row.getString("mapping_status"), row.getObject("customer_id", UUID.class),
                row.getString("row_status"), row.getString("message"),
                row.getBoolean("applied_high_school"), row.getBoolean("applied_source_location"));
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以执行客户资料补全");
        return actor;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String normalize(String value) {
        String key = TextNormalizer.matchKey(value);
        return key == null ? "" : key;
    }
    private String value(String value) { return blank(value) ? null : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) result.addValue((String) values[index], values[index + 1]);
        return result;
    }

    record SourceRow(int rowNumber, String fullName, String major, String grade, String highSchool) {}
    record LocationMapping(String location, String status) {}
    record ParsedWorkbook(List<SourceRow> rows, Map<String, LocationMapping> locationMappings) {}
    private record RowDecision(String status, UUID customerId, String sourceLocation, String mappingStatus, String message) {}

    public record JobSummary(UUID id, String filename, String fileSha256, String status, int totalRows,
                             int readyRows, int updatedRows, int skippedRows, int conflictRows,
                             Instant expiresAt, Instant createdAt, Instant completedAt) {}
    public record RowResult(int rowNumber, String fullName, String major, String grade, String highSchool,
                            String sourceLocation, String mappingStatus, UUID customerId, String status,
                            String message, boolean appliedHighSchool, boolean appliedSourceLocation) {}
    public record Preview(JobSummary job, List<RowResult> rows) {}
}
