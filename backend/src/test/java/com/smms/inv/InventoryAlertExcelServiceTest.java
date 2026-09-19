package com.smms.inv;

import com.smms.inv.dto.AlertItemVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.inv.service.InventoryAlertExcelService;
import com.smms.inv.service.InventoryService;
import com.smms.sys.service.SysConfigService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 库存预警 Excel 导出单元测试（FR-INV-05）：按筛选条件导出预警清单，
 * 列口径固定（商品 + 预警类型 + 阈值与缺口），行数上限取 export_max_rows。
 */
@ExtendWith(MockitoExtension.class)
class InventoryAlertExcelServiceTest {

    @Mock private InventoryService inventoryService;
    @Mock private SysConfigService configService;

    private InventoryAlertExcelService service;

    @BeforeEach
    void setUp() {
        service = new InventoryAlertExcelService(inventoryService, configService);
    }

    @Test
    @DisplayName("导出：表头与数据行按预警清单口径写出，缺口量与预警类型正确呈现")
    void export_rows() throws Exception {
        when(configService.getInt("export_max_rows", 50_000)).thenReturn(50_000);
        AlertItemVO vo = new AlertItemVO(9L, "010010001", "2001234567890", "可乐 330ml",
                5L, "碳酸饮料", "瓶", "500ml", 0,
                new BigDecimal("0"), new BigDecimal("10"), null, "OUT",
                new BigDecimal("10"), LocalDateTime.of(2026, 9, 19, 10, 0, 0));
        when(inventoryService.listForAlertExport(any(), eq(50_000))).thenReturn(List.of(vo));

        byte[] bytes = service.export(new AlertQuery(null, null, null, "OUT", 1, 20, "gap", "desc"));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("商品编码");
            assertThat(head.getCell(6).getStringCellValue()).isEqualTo("预警类型");
            assertThat(head.getCell(10).getStringCellValue()).isEqualTo("缺口量");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("010010001");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("可乐 330ml");
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("碳酸饮料");
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("缺货");
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("0");
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("10");
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("未设置");
            assertThat(row.getCell(10).getStringCellValue()).isEqualTo("10");
            assertThat(row.getCell(11).getStringCellValue()).isEqualTo("2026-09-19 10:00:00");
        }
    }

    @Test
    @DisplayName("导出：积压与预警类型呈现为中文标签")
    void export_alertLabels() throws Exception {
        when(configService.getInt("export_max_rows", 50_000)).thenReturn(50_000);
        AlertItemVO overstock = new AlertItemVO(10L, "010010002", "2001234567891", "洗衣液",
                6L, "日用品", "瓶", null, 0,
                new BigDecimal("300"), new BigDecimal("50"), new BigDecimal("200"), "OVERSTOCK",
                new BigDecimal("100"), null);
        AlertItemVO warning = new AlertItemVO(11L, "010010003", "2001234567892", "薯片",
                7L, "零食", "袋", null, 0,
                new BigDecimal("3"), new BigDecimal("10"), null, "WARNING",
                new BigDecimal("7"), null);
        when(inventoryService.listForAlertExport(any(), eq(50_000))).thenReturn(List.of(overstock, warning));

        byte[] bytes = service.export(new AlertQuery(null, null, null, null, 1, 20, null, null));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("积压");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("预警");
        }
    }
}
