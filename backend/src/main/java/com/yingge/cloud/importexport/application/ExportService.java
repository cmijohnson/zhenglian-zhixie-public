package com.yingge.cloud.importexport.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.application.PermissionService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class ExportService {
    private static final String[] HEADERS = {
            "姓名", "昵称", "手机号", "微信号", "QQ号", "学校", "专业", "年级", "城市", "身份类型",
            "关注方向", "需求摘要", "报名意向", "紧急程度", "核实状态", "来源渠道", "当前状态",
            "标签", "最近联系", "下次跟进", "创建时间", "更新时间", "内部备注"
    };

    private final CustomerService customers;
    private final InteractionService interactions;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;

    public ExportService(CustomerService customers, InteractionService interactions, CurrentActorProvider actors,
                         PermissionService permissions, AuditService audit) {
        this.customers = customers;
        this.interactions = interactions;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public void requireCustomerExportAccess() {
        permissions.requireBulkTransfer(actors.require());
    }

    @Transactional(readOnly = true)
    public void requireTimelineExportAccess(UUID customerId) {
        var actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
    }

    @Transactional
    public void exportCustomers(String format, OutputStream output) throws IOException {
        var actor = actors.require();
        permissions.requireBulkTransfer(actor);
        List<CustomerEntity> rows = customers.visibleEntities(actor, true);
        if ("xlsx".equalsIgnoreCase(format)) writeCustomersXlsx(rows, output); else writeCustomersCsv(rows, output);
        audit.record(actor, "CUSTOMERS_EXPORTED", "CUSTOMER", null, "SUCCESS", java.util.Map.of("count", rows.size()));
    }

    @Transactional
    public void exportTimeline(UUID customerId, String format, OutputStream output) throws IOException {
        var actor = actors.require();
        var customer = customers.requireVisibleEntity(customerId, actor);
        var timeline = interactions.list(customerId);
        if ("xlsx".equalsIgnoreCase(format)) {
            try (var workbook = new XSSFWorkbook()) {
                var sheet = workbook.createSheet("时间线");
                var header = sheet.createRow(0);
                String[] headers = {"沟通时间", "沟通方式", "摘要", "详细备注", "结果", "待核实", "下次跟进", "创建时间"};
                for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
                for (int i = 0; i < timeline.size(); i++) {
                    var item = timeline.get(i);
                    var row = sheet.createRow(i + 1);
                    String[] values = {text(item.contactedAt()), item.channel(), item.summary(), item.details(), item.outcome(),
                            item.verificationTodo(), text(item.nextFollowUpAt()), text(item.createdAt())};
                    for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(safe(values[c]));
                }
                workbook.write(output);
            }
        } else {
            output.write(0xEF); output.write(0xBB); output.write(0xBF);
            try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                 var csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                         .setHeader("沟通时间", "沟通方式", "摘要", "详细备注", "结果", "待核实", "下次跟进", "创建时间").get())) {
                for (var item : timeline) csv.printRecord(text(item.contactedAt()), item.channel(), safe(item.summary()),
                        safe(item.details()), safe(item.outcome()), safe(item.verificationTodo()),
                        text(item.nextFollowUpAt()), text(item.createdAt()));
            }
        }
        audit.record(actor, "TIMELINE_EXPORTED", "CUSTOMER", customer.getId().toString(), "SUCCESS", null);
    }

    private void writeCustomersCsv(List<CustomerEntity> rows, OutputStream output) throws IOException {
        output.write(0xEF); output.write(0xBB); output.write(0xBF);
        try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
             var csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADERS).get())) {
            for (CustomerEntity row : rows) csv.printRecord(values(row));
        }
    }

    private void writeCustomersXlsx(List<CustomerEntity> rows, OutputStream output) throws IOException {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("客户档案");
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) header.createCell(i).setCellValue(HEADERS[i]);
            for (int i = 0; i < rows.size(); i++) {
                var target = sheet.createRow(i + 1);
                List<String> values = values(rows.get(i));
                for (int column = 0; column < values.size(); column++) target.createCell(column).setCellValue(values.get(column));
            }
            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
        }
    }

    private List<String> values(CustomerEntity row) {
        return List.of(
                safe(row.getFullName()), safe(row.getNickname()), safe(row.getPhone()), safe(row.getWechatId()), safe(row.getQqId()),
                safe(row.getSchool()), safe(row.getMajor()), safe(row.getGrade()), safe(row.getCity()), safe(row.getIdentityType()),
                safe(String.join(" / ", row.getFocusDirections())), safe(row.getDemandSummary()), safe(row.getIntentionLevel()),
                safe(row.getUrgencyLevel()), safe(row.getVerificationStatus()), safe(row.getSourceChannelLegacy()),
                safe(row.getCurrentStatus()), safe(row.getTags().stream().filter(tag -> tag.isActive()).map(tag -> tag.getName()).sorted().collect(java.util.stream.Collectors.joining(" / "))),
                text(row.getLastContactAt()), text(row.getNextFollowUpAt()), text(row.getCreatedAt()), text(row.getUpdatedAt()), safe(row.getOwnerNotes())
        );
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }
    private String safe(String value) {
        if (value == null) return "";
        return value.matches("^[=+@-].*") ? "'" + value : value;
    }
}
