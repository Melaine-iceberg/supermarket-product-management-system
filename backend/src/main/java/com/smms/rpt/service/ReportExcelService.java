package com.smms.rpt.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报表 Excel 导出基建（FR-RPT-08）：所有报表共用。
 * 行数上限取系统参数 export_max_rows（默认 5 万行），超限提示缩小范围；
 * SXSSF 流式写出避免内存溢出（IR-SW-04）；金额/百分比统一 2 位小数（BR-RPT-05）。
 * 各报表导出端点以 @OpLog 记录操作日志（FR-RPT-08）。
 */
@Service
@RequiredArgsConstructor
public class ReportExcelService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysConfigService configService;

    /** 导出表模型：sheet 名 + 固定列头 + 字符串行 */
    public record Table(String sheetName, String[] columns, List<String[]> rows) {
    }

    /** 单次导出行数上限（FR-RPT-08） */
    public int maxRows() {
        return configService.getInt("export_max_rows", 50_000);
    }

    /** 行数超限校验：超限提示缩小查询范围（FR-RPT-08） */
    public void checkLimit(long total) {
        if (total > maxRows()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "导出数据超过上限 " + maxRows() + " 行，请缩小查询范围后重试（FR-RPT-08）");
        }
    }

    /** SXSSF 流式写出（IR-SW-04） */
    public byte[] build(Table table) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(table.sheetName());
            Row head = sheet.createRow(0);
            for (int i = 0; i < table.columns().length; i++) {
                head.createCell(i).setCellValue(table.columns()[i]);
                sheet.setColumnWidth(i, 14 * 256);
            }
            int r = 1;
            for (String[] row : table.rows()) {
                Row line = sheet.createRow(r++);
                for (int i = 0; i < row.length && i < table.columns().length; i++) {
                    line.createCell(i).setCellValue(row[i] == null ? "" : row[i]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "导出失败");
        }
    }

    // ---------- 统一格式化（BR-RPT-05：金额/百分比 2 位小数；除零场景显示 —） ----------

    /** 金额：null → 空串；否则 2 位小数 */
    public static String amount(BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 数量：null → 空串；去掉多余的尾零 */
    public static String qty(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** 百分比：null（含除零）→ "—"（BR-RPT-01） */
    public static String percent(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 时间：yyyy-MM-dd HH:mm:ss */
    public static String time(LocalDateTime v) {
        return v == null ? "" : v.format(TIME_FORMAT);
    }

    /** 通用空值保护 */
    public static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
