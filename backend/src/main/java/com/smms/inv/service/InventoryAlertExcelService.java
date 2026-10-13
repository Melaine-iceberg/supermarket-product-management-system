package com.smms.inv.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.inv.dto.AlertItemVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 库存预警 Excel 导出（FR-INV-05）：预警清单支持导出，
 * 按当前筛选条件流式写出（SXSSF 避免内存溢出，IR-SW-04），
 * 列固定为预警清单口径，行数上限取参数 export_max_rows（FR-RPT-08）。
 */
@Service
@RequiredArgsConstructor
public class InventoryAlertExcelService {

    /** 导出列（顺序固定）：商品 + 预警类型 + 阈值与缺口 */
    static final String[] COLUMNS = {
            "商品编码", "商品条码", "商品名称", "分类", "单位", "规格", "预警类型",
            "结存数量", "安全库存下限", "库存上限", "缺口量", "最近变动时间"
    };

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InventoryService inventoryService;
    private final SysConfigService configService;

    /** 按筛选条件导出预警清单，与列表同一口径（含缺口量排序）；行数上限 export_max_rows（超限提示缩小范围，FR-RPT-08；GAP-P0-01） */
    public byte[] export(AlertQuery query) {
        int maxRows = configService.getInt("export_max_rows", 50_000);
        List<AlertItemVO> list = inventoryService.listForAlertExport(query, maxRows + 1);
        if (list.size() > maxRows) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "导出数据超过上限 " + maxRows + " 行，请缩小筛选范围后重试（FR-RPT-08）");
        }
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("库存预警清单");
            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                head.createCell(i).setCellValue(COLUMNS[i]);
                sheet.setColumnWidth(i, 14 * 256);
            }
            int r = 1;
            for (AlertItemVO vo : list) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nvl(vo.goodsCode()));
                row.createCell(1).setCellValue(nvl(vo.barcode()));
                row.createCell(2).setCellValue(nvl(vo.name()));
                row.createCell(3).setCellValue(nvl(vo.categoryName()));
                row.createCell(4).setCellValue(nvl(vo.unitName()));
                row.createCell(5).setCellValue(nvl(vo.spec()));
                row.createCell(6).setCellValue(alertLabel(vo.alertType()));
                row.createCell(7).setCellValue(nvl(vo.quantity()));
                row.createCell(8).setCellValue(nvl(vo.safetyStockLower()));
                row.createCell(9).setCellValue(vo.safetyStockUpper() == null ? "未设置" : vo.safetyStockUpper().toPlainString());
                row.createCell(10).setCellValue(nvl(vo.gapQty()));
                row.createCell(11).setCellValue(vo.lastChangeTime() == null ? "" : vo.lastChangeTime().format(TIME_FORMAT));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "导出失败");
        }
    }

    private String alertLabel(String alertType) {
        return switch (alertType == null ? "" : alertType) {
            case "OUT" -> "缺货";
            case "WARNING" -> "预警";
            case "OVERSTOCK" -> "积压";
            default -> "";
        };
    }

    private String nvl(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
