package com.yingge.cloud.importexport.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportExportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final TransactionTemplate transactions;
    private final AuditService audit;

    public ImportExportService(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentActorProvider actors,
                               PermissionService permissions, CustomerService customers,
                               TransactionTemplate transactions, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.transactions = transactions;
        this.audit = audit;
    }

    public ImportPreview preview(MultipartFile file) {
        var actor = actors.require();
        permissions.requireBulkTransfer(actor);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("IMPORT_EMPTY", "导入文件为空");
        String filename = file.getOriginalFilename() == null ? "customers.csv" : file.getOriginalFilename();
        String format = filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? "XLSX" : "CSV";
        List<Map<String, String>> sourceRows;
        try {
            sourceRows = "XLSX".equals(format) ? parseXlsx(file.getBytes()) : parseCsv(file.getBytes());
        } catch (Exception exception) {
            throw ApiException.badRequest("IMPORT_PARSE_FAILED", "无法解析导入文件：" + exception.getMessage());
        }
        UUID jobId = UUID.randomUUID();
        var previews = new ArrayList<RowPreview>();
        int ready = 0;
        int failed = 0;

        jdbc.update("""
                insert into import_jobs (id, organization_id, created_by_membership_id, filename, format, status, total_rows)
                values (?, ?, ?, ?, ?, 'PREVIEWED', ?)
                """, jobId, actor.organizationId(), actor.membershipId(), filename, format, sourceRows.size());

        for (int i = 0; i < sourceRows.size(); i++) {
            int rowNumber = i + 2;
            var messages = new ArrayList<String>();
            CustomerDtos.UpsertRequest request = toRequest(sourceRows.get(i), messages);
            String status = messages.isEmpty() ? "READY" : "FAILED";
            if (messages.isEmpty()) ready++; else failed++;
            try {
                String dataJson = objectMapper.writeValueAsString(request);
                String messageJson = objectMapper.writeValueAsString(messages);
                jdbc.update("""
                        insert into import_row_results (id, import_job_id, row_number, status, normalized_data, messages)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        """, UUID.randomUUID(), jobId, rowNumber, status, dataJson, messageJson);
                previews.add(new RowPreview(rowNumber, status, request, messages));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to persist import preview", exception);
            }
        }
        jdbc.update("update import_jobs set failed_count=? where id=?", failed, jobId);
        audit.record(actor, "IMPORT_PREVIEWED", "IMPORT_JOB", jobId.toString(), "SUCCESS",
                Map.of("filename", filename, "rows", sourceRows.size()));
        return new ImportPreview(jobId, filename, format, sourceRows.size(), ready, failed, previews.stream().limit(100).toList());
    }

    public ImportReport commit(UUID jobId) {
        var actor = actors.require();
        permissions.requireBulkTransfer(actor);
        Map<String, Object> job;
        try {
            job = jdbc.queryForMap(
                    "select organization_id, status, total_rows from import_jobs where id=?", jobId);
        } catch (EmptyResultDataAccessException exception) {
            throw ApiException.notFound("IMPORT_NOT_FOUND", "导入任务不存在");
        }
        if (!actor.organizationId().equals(job.get("organization_id"))) {
            throw ApiException.notFound("IMPORT_NOT_FOUND", "导入任务不存在");
        }
        if (!"PREVIEWED".equals(job.get("status"))) {
            throw ApiException.conflict("IMPORT_ALREADY_COMMITTED", "该导入任务已经提交");
        }
        var rows = jdbc.query("""
                        select id, row_number, normalized_data::text from import_row_results
                        where import_job_id=? and status='READY' order by row_number
                        """, (result, index) -> new StoredRow(
                        result.getObject("id", UUID.class), result.getInt("row_number"), result.getString("normalized_data")), jobId);

        var results = new ArrayList<CommitResult>();
        int batchSize = 200;
        for (int offset = 0; offset < rows.size(); offset += batchSize) {
            List<StoredRow> batch = rows.subList(offset, Math.min(offset + batchSize, rows.size()));
            try {
                transactions.executeWithoutResult(status -> batch.forEach(row -> results.add(commitRow(row))));
            } catch (RuntimeException batchFailure) {
                results.removeIf(result -> batch.stream().anyMatch(row -> row.id().equals(result.rowId())));
                for (StoredRow row : batch) {
                    try {
                        CommitResult result = transactions.execute(status -> commitRow(row));
                        if (result != null) results.add(result);
                    } catch (RuntimeException rowFailure) {
                        transactions.executeWithoutResult(status -> markFailed(row, rowFailure));
                        results.add(new CommitResult(row.id(), "FAILED"));
                    }
                }
            }
        }

        int created = (int) results.stream().filter(result -> "CREATED".equals(result.status())).count();
        int skipped = (int) results.stream().filter(result -> "SKIPPED".equals(result.status())).count();
        int failed = (int) results.stream().filter(result -> "FAILED".equals(result.status())).count()
                + ((Number) job.get("total_rows")).intValue() - rows.size();
        String finalStatus = failed == 0 ? "COMPLETED" : "PARTIAL_FAILED";
        Map<String, Object> report = Map.of("created", created, "skipped", skipped, "failed", failed);
        try {
            jdbc.update("""
                    update import_jobs set status=?, created_count=?, skipped_count=?, failed_count=?,
                    report=?::jsonb, completed_at=now() where id=?
                    """, finalStatus, created, skipped, failed, objectMapper.writeValueAsString(report), jobId);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        audit.record(actor, "IMPORT_COMMITTED", "IMPORT_JOB", jobId.toString(), finalStatus, report);
        return report(jobId);
    }

    public ImportReport report(UUID jobId) {
        var actor = actors.require();
        permissions.requireBulkTransfer(actor);
        try {
            return jdbc.queryForObject("""
                    select id, organization_id, filename, format, status, total_rows, created_count,
                           skipped_count, failed_count, created_at, completed_at
                    from import_jobs where id=?
                    """, (result, index) -> {
                if (!actor.organizationId().equals(result.getObject("organization_id", UUID.class))) {
                    throw ApiException.notFound("IMPORT_NOT_FOUND", "导入任务不存在");
                }
                List<RowMessage> messages = jdbc.query("""
                                select row_number, status, messages::text from import_row_results
                                where import_job_id=? and status in ('FAILED','SKIPPED') order by row_number
                                """, (row, rowIndex) -> {
                            try {
                                List<String> parsed = objectMapper.readValue(row.getString("messages"), new TypeReference<>() {});
                                return new RowMessage(row.getInt("row_number"), row.getString("status"), parsed);
                            } catch (Exception exception) {
                                return new RowMessage(row.getInt("row_number"), row.getString("status"), List.of("报告解析失败"));
                            }
                        }, jobId);
                return new ImportReport(
                        result.getObject("id", UUID.class), result.getString("filename"), result.getString("format"),
                        result.getString("status"), result.getInt("total_rows"), result.getInt("created_count"),
                        result.getInt("skipped_count"), result.getInt("failed_count"), messages
                );
            }, jobId);
        } catch (EmptyResultDataAccessException exception) {
            throw ApiException.notFound("IMPORT_NOT_FOUND", "导入任务不存在");
        }
    }

    private CommitResult commitRow(StoredRow row) {
        try {
            CustomerDtos.UpsertRequest request = objectMapper.readValue(row.json(), CustomerDtos.UpsertRequest.class);
            var created = customers.create(request);
            jdbc.update("update import_row_results set status='CREATED', created_customer_id=? where id=?", created.id(), row.id());
            return new CommitResult(row.id(), "CREATED");
        } catch (ApiException exception) {
            if (exception.status() == HttpStatus.CONFLICT) {
                markSkipped(row, exception.getMessage());
                return new CommitResult(row.id(), "SKIPPED");
            }
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void markSkipped(StoredRow row, String message) {
        try {
            jdbc.update("update import_row_results set status='SKIPPED', messages=?::jsonb where id=?",
                    objectMapper.writeValueAsString(List.of(message)), row.id());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void markFailed(StoredRow row, RuntimeException failure) {
        try {
            String message = importFailureMessage(failure);
            jdbc.update("update import_row_results set status='FAILED', messages=?::jsonb where id=?",
                    objectMapper.writeValueAsString(List.of(message)), row.id());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String importFailureMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (failure instanceof ApiException api) return api.getMessage();
        String detail = root.getMessage();
        if (detail == null || detail.isBlank()) detail = root.getClass().getSimpleName();
        detail = detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (detail.startsWith("ERROR:")) return "导入处理失败：数据约束校验未通过";
        if (detail.length() > 240) detail = detail.substring(0, 240);
        return "导入处理失败：" + detail;
    }

    private CustomerDtos.UpsertRequest toRequest(Map<String, String> row, List<String> messages) {
        String fullName = value(row, "姓名", "fullname", "name");
        String phone = value(row, "手机号", "phone", "mobile");
        String wechat = value(row, "微信号", "微信", "wechat", "wechatid");
        String qq = value(row, "qq号", "qq", "qqid");
        if (fullName == null || fullName.isBlank()) messages.add("姓名不能为空");
        if (blank(phone) && blank(wechat) && blank(qq)) messages.add("手机号、微信号、QQ号至少填写一个");
        SetBuilder focus = split(value(row, "关注方向", "focusdirections"));
        return new CustomerDtos.UpsertRequest(
                fullName == null ? "" : fullName, value(row, "昵称", "nickname"), phone, wechat, qq,
                value(row, "学校", "school"), value(row, "专业", "major"), value(row, "年级", "grade"),
                value(row, "城市", "city"), value(row, "高中学校", "highschool"),
                value(row, "生源地", "sourcelocation"), value(row, "身份类型", "identitytype"), focus.values(),
                value(row, "需求摘要", "demandsummary"), value(row, "报名意向", "意向", "intentionlevel"),
                value(row, "紧急程度", "urgencylevel"), value(row, "核实状态", "verificationstatus"),
                null, value(row, "来源渠道", "sourcechannel"), value(row, "当前状态", "currentstatus"),
                value(row, "内部备注", "备注", "ownernotes"),
                null, null, null, null, null, null, null, Set.of(), null, null
        );
    }

    private List<Map<String, String>> parseCsv(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            var rows = new ArrayList<Map<String, String>>();
            for (var record : parser) {
                var row = new LinkedHashMap<String, String>();
                parser.getHeaderMap().forEach((header, index) -> row.put(normalizeHeader(header), record.get(index).trim()));
                if (row.values().stream().anyMatch(value -> !value.isBlank())) rows.add(row);
            }
            return rows;
        }
    }

    private List<Map<String, String>> parseXlsx(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            var formatter = new DataFormatter(Locale.CHINA);
            var headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return List.of();
            var headers = new ArrayList<String>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) headers.add(normalizeHeader(formatter.formatCellValue(headerRow.getCell(i))));
            var result = new ArrayList<Map<String, String>>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var source = sheet.getRow(rowIndex);
                if (source == null) continue;
                var row = new LinkedHashMap<String, String>();
                for (int column = 0; column < headers.size(); column++) {
                    row.put(headers.get(column), formatter.formatCellValue(source.getCell(column)).trim());
                }
                if (row.values().stream().anyMatch(value -> !value.isBlank())) result.add(row);
            }
            return result;
        }
    }

    private String value(Map<String, String> row, String... aliases) {
        for (String alias : aliases) {
            String value = row.get(normalizeHeader(alias));
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-/]", "");
    }

    private SetBuilder split(String value) {
        if (blank(value)) return new SetBuilder(java.util.Set.of());
        return new SetBuilder(java.util.Arrays.stream(value.split("[/／、,，;；|]"))
                .map(String::trim).filter(item -> !item.isBlank()).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record StoredRow(UUID id, int rowNumber, String json) {}
    private record CommitResult(UUID rowId, String status) {}
    private record SetBuilder(java.util.Set<String> values) {}
    public record RowPreview(int rowNumber, String status, CustomerDtos.UpsertRequest data, List<String> messages) {}
    public record ImportPreview(UUID jobId, String filename, String format, int totalRows, int readyRows,
                                int failedRows, List<RowPreview> rows) {}
    public record RowMessage(int rowNumber, String status, List<String> messages) {}
    public record ImportReport(UUID jobId, String filename, String format, String status, int totalRows,
                               int createdCount, int skippedCount, int failedCount, List<RowMessage> messages) {}
}
