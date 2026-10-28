package com.yingge.cloud.enrichment.application;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerEnrichmentWorkbookTest {
    @Test
    void parsesStrictSourceRowsAndUsesVerifiedMappingSheetInsteadOfFormulaCells() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var source = workbook.createSheet("Sheet1");
            var header = source.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("班级");
            header.createCell(2).setCellValue("专业");
            header.createCell(3).setCellValue("入学前单位");
            header.createCell(4).setCellValue("所在地");
            var first = source.createRow(1);
            first.createCell(0).setCellValue("张三");
            first.createCell(1).setCellValue("计算机2601");
            first.createCell(2).setCellValue("计算机科学与技术");
            first.createCell(3).setCellValue("示例省示例市第一中学");
            first.createCell(4).setCellFormula("VLOOKUP(D2,所在地映射!A:B,2,FALSE)");
            var second = source.createRow(2);
            second.createCell(0).setCellValue("李四");
            second.createCell(1).setCellValue("通信2601");
            second.createCell(2).setCellValue("通信工程");
            second.createCell(3).setCellValue("待核实中学");

            var mapping = workbook.createSheet("所在地映射");
            var mappingHeader = mapping.createRow(0);
            mappingHeader.createCell(0).setCellValue("入学前单位");
            mappingHeader.createCell(1).setCellValue("所在地");
            mappingHeader.createCell(2).setCellValue("状态");
            var verified = mapping.createRow(1);
            verified.createCell(0).setCellValue("示例省示例市第一中学");
            verified.createCell(1).setCellValue("示例省示例市");
            verified.createCell(2).setCellValue("已匹配");
            var pending = mapping.createRow(2);
            pending.createCell(0).setCellValue("待核实中学");
            pending.createCell(1).setCellValue("待核实（单位信息不足）");
            pending.createCell(2).setCellValue("待核实");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var service = new CustomerEnrichmentService(null, null, null);
        var parsed = service.parse(bytes);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().getFirst().grade()).isEqualTo("2026");
        assertThat(parsed.rows().getFirst().highSchool()).isEqualTo("示例省示例市第一中学");
        assertThat(parsed.locationMappings().get("示例省示例市第一中学").location()).isEqualTo("示例省示例市");
        assertThat(parsed.locationMappings().get("待核实中学").status()).isEqualTo("待核实");
    }
}
