package com.yingge.cloud.leadership.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.leadership.api.LeadershipDashboardDtos;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

@Service
public class LeadershipDashboardExportService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter GENERATED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LeadershipDashboardService dashboards;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public LeadershipDashboardExportService(
            LeadershipDashboardService dashboards,
            CurrentActorProvider actors,
            AuditService audit) {
        this.dashboards = dashboards;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional
    public void export(
            UUID teamId, UUID membershipId, Integer days, OutputStream output) throws IOException {
        var actor = actors.require();
        LeadershipDashboardDtos.Overview overview =
                dashboards.overview(teamId, membershipId, days);
        try (var workbook = new XSSFWorkbook()) {
            workbook.setForceFormulaRecalculation(true);
            Styles styles = new Styles(workbook);
            XSSFSheet summary = workbook.createSheet("领导摘要");
            XSSFSheet sources = workbook.createSheet("客户来源分析");
            XSSFSheet rankings = workbook.createSheet("个人排行榜");
            XSSFSheet daily = workbook.createSheet("每日趋势");

            writeSourceAnalysis(sources, overview, styles);
            writeRankings(rankings, overview, styles);
            writeDaily(daily, overview, styles);
            writeSummary(summary, daily, overview, styles);

            workbook.setActiveSheet(0);
            workbook.write(output);
        }
        audit.record(actor, "LEADERSHIP_DASHBOARD_EXPORTED", "LEADERSHIP_DASHBOARD", null,
                "SUCCESS", Map.of(
                        "days", overview.days(),
                        "teamId", teamId == null ? "" : teamId.toString(),
                        "membershipId", membershipId == null ? "" : membershipId.toString()));
    }

    private void writeSummary(
            XSSFSheet sheet,
            XSSFSheet dailySheet,
            LeadershipDashboardDtos.Overview overview,
            Styles styles) {
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(0, 3);
        title(sheet, "A1:I1", "英格客户云 · 领导经营数据摘要", styles);
        mergedText(sheet, "A2:I2",
                "统计周期 " + overview.startDate() + " 至 " + overview.endDate()
                        + "｜生成时间 "
                        + GENERATED_FORMAT.format(overview.generatedAt().atZone(BUSINESS_ZONE))
                        + "｜口径：未归档客户，个人数据按当前负责人归属",
                styles.note());

        String[] labels = {"总客户数", "添加好友客户数", "信任好友客户数", "筛选好友客户数"};
        long[] values = {
                overview.headline().totalCustomers(),
                overview.headline().addedFriendCustomers(),
                overview.headline().trustedFriendCustomers(),
                overview.headline().qualifiedFriendCustomers()
        };
        for (int index = 0; index < labels.length; index++) {
            int column = index * 2;
            var labelRow = sheet.getRow(3) == null ? sheet.createRow(3) : sheet.getRow(3);
            Cell label = labelRow.createCell(column);
            label.setCellValue(labels[index]);
            label.setCellStyle(styles.kpiLabel());
            Cell value = sheet.getRow(4) == null
                    ? sheet.createRow(4).createCell(column)
                    : sheet.getRow(4).createCell(column);
            value.setCellValue(values[index]);
            value.setCellStyle(styles.kpiValue());
            sheet.addMergedRegion(new CellRangeAddress(3, 3, column, column + 1));
            sheet.addMergedRegion(new CellRangeAddress(4, 5, column, column + 1));
        }

        row(sheet, 7, styles.section(), "漏斗转化率", "", "今日新增客户", "",
                "今日筛选客户", "", "整体任务完成率", "");
        var summaryRow = sheet.createRow(8);
        Cell addedRate = summaryRow.createCell(0);
        addedRate.setCellFormula("IF(A5=0,0,C5/A5)");
        addedRate.setCellStyle(styles.percentKpi());
        Cell todayNew = summaryRow.createCell(2);
        todayNew.setCellValue(overview.headline().todayNewCustomers());
        todayNew.setCellStyle(styles.numberKpi());
        Cell todayQualified = summaryRow.createCell(4);
        todayQualified.setCellValue(overview.headline().todayQualifiedCustomers());
        todayQualified.setCellStyle(styles.numberKpi());
        Cell taskRate = summaryRow.createCell(6);
        taskRate.setCellValue(overview.taskSummary().completionRate());
        taskRate.setCellStyle(styles.percentKpi());

        row(sheet, 10, styles.section(), "任务执行", "任务数", "已完成", "进行中",
                "待验收", "待开始", "退回", "逾期");
        row(sheet, 11, styles.body(), "",
                overview.taskSummary().assignmentCount(),
                overview.taskSummary().completedCount(),
                overview.taskSummary().inProgressCount(),
                overview.taskSummary().submittedCount(),
                overview.taskSummary().pendingCount(),
                overview.taskSummary().returnedCount(),
                overview.taskSummary().overdueCount());

        row(sheet, 13, styles.section(), "客户来源 TOP 5", "客户数", "添加", "信任",
                "筛选", "来源占比", "筛选转化率", "");
        int sourceRow = 14;
        for (var source : overview.sources().stream().limit(5).toList()) {
            row(sheet, sourceRow++, styles.body(), source.source(), source.customerCount(),
                    source.addedFriendCustomers(), source.trustedFriendCustomers(),
                    source.qualifiedFriendCustomers(), source.share(),
                    source.qualifiedRate(), "");
            sheet.getRow(sourceRow - 1).getCell(5).setCellStyle(styles.percent());
            sheet.getRow(sourceRow - 1).getCell(6).setCellStyle(styles.percent());
        }

        row(sheet, 13, 9, styles.section(), overview.featuredGrade() + " 级个人筛选榜 TOP 5",
                "筛选客户数", "任务完成率");
        int rankingRow = 14;
        for (var member : overview.rankings().stream()
                .sorted(Comparator.comparingLong(
                        LeadershipDashboardDtos.MemberRanking::qualifiedFriendCustomers).reversed())
                .limit(5).toList()) {
            row(sheet, rankingRow++, 9, styles.body(), member.displayName(),
                    member.qualifiedFriendCustomers(), member.taskCompletionRate());
            sheet.getRow(rankingRow - 1).getCell(11).setCellStyle(styles.percent());
        }

        addDailyChart(sheet, dailySheet, overview);
        for (int column = 0; column < 12; column++) {
            sheet.setColumnWidth(column, column == 0 || column == 9 ? 4600 : 3300);
        }
        sheet.getRow(0).setHeightInPoints(34);
        sheet.getRow(4).setHeightInPoints(35);
    }

    private void writeSourceAnalysis(
            XSSFSheet sheet,
            LeadershipDashboardDtos.Overview overview,
            Styles styles) {
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(1, 2);
        title(sheet, "A1:I1", "客户来源分析", styles);
        row(sheet, 1, styles.header(), "来源渠道", "客户数", "添加好友", "信任好友",
                "筛选好友", "来源占比", "添加率", "信任率", "筛选率");
        int lastRow = Math.max(2, overview.sources().size() + 2);
        for (int index = 0; index < overview.sources().size(); index++) {
            int rowIndex = index + 2;
            var source = overview.sources().get(index);
            row(sheet, rowIndex, styles.body(), source.source(), source.customerCount(),
                    source.addedFriendCustomers(), source.trustedFriendCustomers(),
                    source.qualifiedFriendCustomers(), "", "", "", "");
            int excelRow = rowIndex + 1;
            formula(sheet, rowIndex, 5,
                    "IF(SUM($B$3:$B$" + lastRow + ")=0,0,B" + excelRow
                            + "/SUM($B$3:$B$" + lastRow + "))", styles.percent());
            formula(sheet, rowIndex, 6,
                    "IF(B" + excelRow + "=0,0,C" + excelRow + "/B" + excelRow + ")",
                    styles.percent());
            formula(sheet, rowIndex, 7,
                    "IF(C" + excelRow + "=0,0,D" + excelRow + "/C" + excelRow + ")",
                    styles.percent());
            formula(sheet, rowIndex, 8,
                    "IF(D" + excelRow + "=0,0,E" + excelRow + "/D" + excelRow + ")",
                    styles.percent());
        }
        addTotalsRow(sheet, overview.sources().size() + 2, 9,
                new int[]{1, 2, 3, 4}, styles);
        sizeColumns(sheet, new int[]{5200, 3000, 3300, 3300, 3300, 3200, 3000, 3000, 3000});
    }

    private void writeRankings(
            XSSFSheet sheet,
            LeadershipDashboardDtos.Overview overview,
            Styles styles) {
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(3, 2);
        String gradeLabel = overview.featuredGrade() + " 级";
        title(sheet, "A1:M1", "个人客户与任务排行榜（客户仅统计" + gradeLabel + "）", styles);
        row(sheet, 1, styles.header(), "排名", "成员", "所属团队", gradeLabel + "总客户", gradeLabel + "添加好友",
                gradeLabel + "信任好友", gradeLabel + "筛选好友", "分配任务", "完成任务", "任务完成率",
                "添加率", "信任率", "筛选率");
        var rankings = overview.rankings().stream()
                .sorted(Comparator.comparingLong(
                        LeadershipDashboardDtos.MemberRanking::qualifiedFriendCustomers).reversed()
                        .thenComparing(LeadershipDashboardDtos.MemberRanking::displayName))
                .toList();
        for (int index = 0; index < rankings.size(); index++) {
            int rowIndex = index + 2;
            int excelRow = rowIndex + 1;
            var member = rankings.get(index);
            row(sheet, rowIndex, styles.body(), index + 1, member.displayName(), member.teamNames(),
                    member.totalCustomers(), member.addedFriendCustomers(),
                    member.trustedFriendCustomers(), member.qualifiedFriendCustomers(),
                    member.assignedTasks(), member.completedTasks(), "", "", "", "");
            formula(sheet, rowIndex, 9,
                    "IF(H" + excelRow + "=0,0,I" + excelRow + "/H" + excelRow + ")",
                    styles.percent());
            formula(sheet, rowIndex, 10,
                    "IF(D" + excelRow + "=0,0,E" + excelRow + "/D" + excelRow + ")",
                    styles.percent());
            formula(sheet, rowIndex, 11,
                    "IF(E" + excelRow + "=0,0,F" + excelRow + "/E" + excelRow + ")",
                    styles.percent());
            formula(sheet, rowIndex, 12,
                    "IF(F" + excelRow + "=0,0,G" + excelRow + "/F" + excelRow + ")",
                    styles.percent());
        }
        addTotalsRow(sheet, rankings.size() + 2, 13,
                new int[]{3, 4, 5, 6, 7, 8}, styles);
        sizeColumns(sheet, new int[]{
                2200, 4200, 6000, 3000, 3200, 3200, 3200,
                3000, 3000, 3300, 3000, 3000, 3000
        });
    }

    private void writeDaily(
            XSSFSheet sheet,
            LeadershipDashboardDtos.Overview overview,
            Styles styles) {
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(1, 2);
        title(sheet, "A1:E1", "每日好友转化趋势", styles);
        row(sheet, 1, styles.header(), "日期", "添加好友人数", "信任好友人数",
                "筛选好友人数", "累计筛选人数");
        for (int index = 0; index < overview.dailyConversions().size(); index++) {
            var item = overview.dailyConversions().get(index);
            row(sheet, index + 2, styles.body(), item.date().toString(),
                    item.addedFriendCustomers(), item.trustedFriendCustomers(),
                    item.qualifiedFriendCustomers(), item.cumulativeQualifiedCustomers());
        }
        sizeColumns(sheet, new int[]{3600, 3800, 3800, 3800, 3800});
    }

    private void addDailyChart(
            XSSFSheet sheet,
            XSSFSheet dailySheet,
            LeadershipDashboardDtos.Overview overview) {
        int itemCount = overview.dailyConversions().size();
        if (itemCount <= 0) return;
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 0, 23, 12, 41);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("每日好友转化人数趋势");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(org.apache.poi.xddf.usermodel.chart.LegendPosition.TOP);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(org.apache.poi.xddf.usermodel.chart.AxisCrosses.AUTO_ZERO);
        leftAxis.setMinimum(0);
        long maximum = overview.dailyConversions().stream()
                .flatMapToLong(item -> java.util.stream.LongStream.of(
                        item.addedFriendCustomers(),
                        item.trustedFriendCustomers(),
                        item.qualifiedFriendCustomers()))
                .max().orElse(1);
        leftAxis.setMajorUnit(Math.max(1, Math.ceil(maximum / 5.0)));

        CellRangeAddress categoryRange = new CellRangeAddress(2, itemCount + 1, 0, 0);
        XDDFDataSource<String> dates =
                XDDFDataSourcesFactory.fromStringCellRange(dailySheet, categoryRange);
        XDDFLineChartData data =
                (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        addLineSeries(data, dates, dailySheet, itemCount, 1, "添加好友");
        addLineSeries(data, dates, dailySheet, itemCount, 2, "信任好友");
        addLineSeries(data, dates, dailySheet, itemCount, 3, "筛选好友");
        chart.plot(data);
    }

    private void addLineSeries(
            XDDFLineChartData data,
            XDDFDataSource<String> dates,
            XSSFSheet dailySheet,
            int itemCount,
            int column,
            String title) {
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                dailySheet, new CellRangeAddress(2, itemCount + 1, column, column));
        XDDFChartData.Series series = data.addSeries(dates, values);
        series.setTitle(title, null);
        if (series instanceof XDDFLineChartData.Series lineSeries) {
            lineSeries.setSmooth(false);
            lineSeries.setMarkerStyle(org.apache.poi.xddf.usermodel.chart.MarkerStyle.CIRCLE);
        }
    }

    private void title(XSSFSheet sheet, String range, String text, Styles styles) {
        CellRangeAddress address = CellRangeAddress.valueOf(range);
        sheet.addMergedRegion(address);
        Cell cell = sheet.createRow(address.getFirstRow()).createCell(address.getFirstColumn());
        cell.setCellValue(text);
        cell.setCellStyle(styles.title());
    }

    private void mergedText(XSSFSheet sheet, String range, String text, CellStyle style) {
        CellRangeAddress address = CellRangeAddress.valueOf(range);
        sheet.addMergedRegion(address);
        Cell cell = sheet.createRow(address.getFirstRow()).createCell(address.getFirstColumn());
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private void addTotalsRow(
            XSSFSheet sheet,
            int rowIndex,
            int columns,
            int[] sumColumns,
            Styles styles) {
        var row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue("合计");
        row.getCell(0).setCellStyle(styles.total());
        int firstDataExcelRow = 3;
        int lastDataExcelRow = rowIndex;
        for (int column = 1; column < columns; column++) {
            Cell cell = row.createCell(column);
            if (contains(sumColumns, column)) {
                cell.setCellFormula("SUM(" + columnName(column) + firstDataExcelRow + ":"
                        + columnName(column) + lastDataExcelRow + ")");
            }
            cell.setCellStyle(styles.total());
        }
    }

    private boolean contains(int[] values, int target) {
        for (int value : values) if (value == target) return true;
        return false;
    }

    private String columnName(int zeroBasedColumn) {
        int value = zeroBasedColumn + 1;
        StringBuilder result = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private void formula(
            XSSFSheet sheet, int row, int column, String formula, CellStyle style) {
        Cell cell = sheet.getRow(row).getCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private void row(
            XSSFSheet sheet, int rowIndex, CellStyle style, Object... values) {
        row(sheet, rowIndex, 0, style, values);
    }

    private void row(
            XSSFSheet sheet, int rowIndex, int startColumn, CellStyle style, Object... values) {
        var row = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) : sheet.getRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(startColumn + index);
            Object value = values[index];
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value != null && !value.toString().isBlank()) {
                cell.setCellValue(value.toString());
            }
            cell.setCellStyle(style);
        }
    }

    private void sizeColumns(XSSFSheet sheet, int[] widths) {
        for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index]);
    }

    private record Styles(
            CellStyle title,
            CellStyle note,
            CellStyle section,
            CellStyle header,
            CellStyle body,
            CellStyle total,
            CellStyle percent,
            CellStyle kpiLabel,
            CellStyle kpiValue,
            CellStyle numberKpi,
            CellStyle percentKpi) {
        private Styles(XSSFWorkbook workbook) {
            this(
                    style(workbook, "0B1F33", "DFFBFF", true, 18, HorizontalAlignment.LEFT),
                    style(workbook, "E8F2F7", "476679", false, 9, HorizontalAlignment.LEFT),
                    style(workbook, "123B52", "92E9F0", true, 10, HorizontalAlignment.CENTER),
                    style(workbook, "0D3148", "D4F8FA", true, 10, HorizontalAlignment.CENTER),
                    style(workbook, "FFFFFF", "263A45", false, 10, HorizontalAlignment.LEFT),
                    style(workbook, "D7EEF2", "123B52", true, 10, HorizontalAlignment.CENTER),
                    percentageStyle(workbook),
                    style(workbook, "123B52", "89DDE7", true, 10, HorizontalAlignment.CENTER),
                    style(workbook, "071C2B", "19E6D3", true, 24, HorizontalAlignment.CENTER),
                    style(workbook, "FFFFFF", "123B52", true, 15, HorizontalAlignment.CENTER),
                    percentageKpiStyle(workbook)
            );
        }

        private static CellStyle style(
                XSSFWorkbook workbook,
                String fillHex,
                String fontHex,
                boolean bold,
                int fontSize,
                HorizontalAlignment alignment) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color(fillHex));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(alignment);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            Font font = workbook.createFont();
            font.setBold(bold);
            font.setFontHeightInPoints((short) fontSize);
            font.setColor(color(fontHex).getIndex());
            if (font instanceof org.apache.poi.xssf.usermodel.XSSFFont xssfFont) {
                xssfFont.setColor(color(fontHex));
                xssfFont.setFontName("Microsoft YaHei");
            }
            style.setFont(font);
            return style;
        }

        private static CellStyle percentageStyle(XSSFWorkbook workbook) {
            CellStyle style = style(workbook, "FFFFFF", "167A73", true, 10,
                    HorizontalAlignment.RIGHT);
            style.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            return style;
        }

        private static CellStyle percentageKpiStyle(XSSFWorkbook workbook) {
            CellStyle style = style(workbook, "FFFFFF", "167A73", true, 15,
                    HorizontalAlignment.CENTER);
            style.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            return style;
        }

        private static XSSFColor color(String hex) {
            return new XSSFColor(java.awt.Color.decode("#" + hex), null);
        }
    }
}
