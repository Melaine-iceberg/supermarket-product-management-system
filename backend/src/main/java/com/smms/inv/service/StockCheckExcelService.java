package com.smms.inv.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.inv.dto.CheckEntryRequest;
import com.smms.inv.dto.CheckImportVO;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckSummaryVO;
import com.smms.inv.mapper.StockCheckItemMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点实盘数 Excel 模板与导入（FR-INV-08）：
 * 模板按盘点单明细导出（编码/条码/名称/单位/账面数量，实盘数量与差异原因留空），
 * 回传后按 编码/条码 定位明细行，复用统一录入入口（服务端计算差异、差异行原因必填）。
 */
@Service
@RequiredArgsConstructor
public class StockCheckExcelService {

    /** 导入模板列（顺序固定，回传按同列解析） */
    static final String[] COLUMNS = {
            "商品编码", "商品条码", "商品名称", "单位", "账面数量", "实盘数量", "差异原因", "备注"
    };

    private final StockCheckItemMapper itemMapper;
    private final StockCheckService stockCheckService;

    /** 导出实盘录入模板（FR-INV-08）：仅盘点中的单据（状态校验由录入入口统一执行） */
    public byte[] template(Long orderId) {
        List<CheckItemVO> items = itemMapper.selectAllItems(orderId);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "盘点单不存在或没有明细");
        }
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("盘点录入");
            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                head.createCell(i).setCellValue(COLUMNS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            int r = 1;
            for (CheckItemVO item : items) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nvl(item.goodsCode()));
                row.createCell(1).setCellValue(nvl(item.barcode()));
                row.createCell(2).setCellValue(nvl(item.name()));
                row.createCell(3).setCellValue(nvl(item.unitName()));
                row.createCell(4).setCellValue(item.bookQty() == null ? "" : item.bookQty().toPlainString());
                row.createCell(5).setCellValue("");
                row.createCell(6).setCellValue("");
                row.createCell(7).setCellValue("");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模板生成失败");
        }
    }

    /** 导入实盘数（FR-INV-08）：编码/条码定位行，实盘留空的行跳过；全部行校验通过才写库 */
    public CheckImportVO importActual(Long orderId, InputStream in) {
        List<CheckItemVO> items = itemMapper.selectAllItems(orderId);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "盘点单不存在或没有明细");
        }
        List<CheckEntryRequest.Item> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankCell(row, 5)) {
                    continue; // 实盘数量留空：未盘，跳过（提交审核时按账面确认）
                }
                String code = cellString(row.getCell(0));
                String barcode = cellString(row.getCell(1));
                CheckItemVO target = match(items, code, barcode);
                if (target == null) {
                    errors.add("第" + (r + 1) + "行：编码/条码不在本次盘点范围内");
                    continue;
                }
                BigDecimal actual;
                try {
                    actual = new BigDecimal(cellString(row.getCell(5)).trim());
                } catch (NumberFormatException e) {
                    errors.add("第" + (r + 1) + "行：实盘数量不是合法数字");
                    continue;
                }
                if (actual.signum() < 0) {
                    errors.add("第" + (r + 1) + "行：实盘数量不能为负数");
                    continue;
                }
                if (actual.scale() > 3) {
                    errors.add("第" + (r + 1) + "行：实盘数量最多 3 位小数");
                    continue;
                }
                lines.add(new CheckEntryRequest.Item(target.goodsId(), actual,
                        cellString(row.getCell(6)), cellString(row.getCell(7))));
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "读取上传文件失败");
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "导入文件存在 " + errors.size() + " 处问题：" + String.join("；",
                            errors.stream().limit(5).toList()));
        }
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件中没有可导入的实盘数量（实盘列留空的行会被跳过）");
        }
        CheckSummaryVO summary = stockCheckService.enter(orderId, new CheckEntryRequest(lines));
        return new CheckImportVO(lines.size(), summary);
    }

    /** 按编码或条码定位盘点明细行（前端扫码定位行同口径） */
    private CheckItemVO match(List<CheckItemVO> items, String code, String barcode) {
        for (CheckItemVO item : items) {
            if (!code.isBlank() && code.equals(nvl(item.goodsCode()))) {
                return item;
            }
            if (!barcode.isBlank() && barcode.equals(nvl(item.barcode()))) {
                return item;
            }
        }
        return null;
    }

    private boolean isBlankCell(Row row, int col) {
        return cellString(row.getCell(col)).isBlank();
    }

    private String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            if (v == Math.rint(v)) {
                return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
            }
            return BigDecimal.valueOf(v).toPlainString();
        }
        return cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }
}
