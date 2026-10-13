package com.smms.rpt.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.rpt.dto.DrillInvRecordQuery;
import com.smms.rpt.dto.DrillInvRecordRow;
import com.smms.rpt.dto.DrillLossQuery;
import com.smms.rpt.dto.DrillLossRow;
import com.smms.rpt.dto.DrillPurchaseQuery;
import com.smms.rpt.dto.DrillPurchaseRow;
import com.smms.rpt.dto.DrillReceiptQuery;
import com.smms.rpt.dto.DrillReceiptRow;
import com.smms.rpt.mapper.InventoryReportMapper;
import com.smms.rpt.mapper.PurchaseReportMapper;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 下钻服务单测（GAP-P0-10③）：采购/入库/库存流水/损耗下钻的成本类字段
 * 对无毛利权限角色不返回（BR-RPT-07），与汇总行掩码口径一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrillDownServiceTest {

    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of(), null, false);
    private static final LoginUser KEEPER = new LoginUser(4L, "keeper", "赵库管",
            List.of("KEEPER"), List.of("库管员"), Set.of(), null, false);

    @Mock
    private ReportMapper reportMapper;

    @Mock
    private PurchaseReportMapper purchaseReportMapper;

    @Mock
    private InventoryReportMapper inventoryReportMapper;

    private DrillDownService service;

    @BeforeEach
    void setUp() {
        service = new DrillDownService(reportMapper, purchaseReportMapper, inventoryReportMapper);
    }

    @Test
    @DisplayName("下钻采购单：无毛利权限时金额/结算金额不返回，单号与状态保留")
    void purchaseDrillMasksAmounts() {
        DrillPurchaseQuery q = new DrillPurchaseQuery(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 1L, null, 1, 20);
        DrillPurchaseRow row = new DrillPurchaseRow(10L, "PO202603010001", 1L, "供应商A",
                "COMPLETED", "李采购", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 10, 0), LocalDate.of(2026, 3, 3),
                new BigDecimal("1500.00"), new BigDecimal("1500.00"), new BigDecimal("75.00"),
                "SETTLED", new BigDecimal("1425.00"));
        when(purchaseReportMapper.countPurchaseDrill(any())).thenReturn(1L);
        when(purchaseReportMapper.selectPurchaseDrill(any(), any())).thenAnswer(inv -> {
            Page<DrillPurchaseRow> page = new Page<>(1, 20, false);
            page.setRecords(List.of(row));
            return page;
        });

        DrillPurchaseRow masked = service.purchaseOrders(q, KEEPER).list().get(0);
        assertThat(masked.totalAmount()).isNull();
        assertThat(masked.receivedAmount()).isNull();
        assertThat(masked.returnAmount()).isNull();
        assertThat(masked.settleAmount()).isNull();
        assertThat(masked.orderNo()).isEqualTo("PO202603010001");
        assertThat(masked.settleStatus()).isEqualTo("SETTLED");

        DrillPurchaseRow full = service.purchaseOrders(q, MANAGER).list().get(0);
        assertThat(full.totalAmount()).isEqualByComparingTo("1500.00");
        assertThat(full.settleAmount()).isEqualByComparingTo("1425.00");
    }

    @Test
    @DisplayName("下钻入库单：无毛利权限时入库金额不返回，按期标记保留")
    void receiptDrillMasksAmount() {
        DrillReceiptQuery q = new DrillReceiptQuery(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 1L, 1, 20);
        DrillReceiptRow row = new DrillReceiptRow(5L, "RK202603020001", 10L, "PO202603010001",
                1L, "供应商A", LocalDateTime.of(2026, 3, 2, 9, 0), "赵库管", "DN001",
                new BigDecimal("600.00"), LocalDate.of(2026, 3, 3), true);
        when(purchaseReportMapper.countReceiptDrill(any())).thenReturn(1L);
        when(purchaseReportMapper.selectReceiptDrill(any(), any())).thenAnswer(inv -> {
            Page<DrillReceiptRow> page = new Page<>(1, 20, false);
            page.setRecords(List.of(row));
            return page;
        });

        DrillReceiptRow masked = service.receipts(q, KEEPER).list().get(0);
        assertThat(masked.totalAmount()).isNull();
        assertThat(masked.onTime()).isTrue();

        assertThat(service.receipts(q, MANAGER).list().get(0).totalAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("下钻库存流水：无毛利权限时单位成本不返回，前后结存与单据号保留")
    void inventoryRecordDrillMasksUnitCost() {
        DrillInvRecordQuery q = new DrillInvRecordQuery(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "SALE_OUT", 1L, 1, 20);
        DrillInvRecordRow row = new DrillInvRecordRow(99L, 1L, "G001", "测试商品", "SALE_OUT",
                new BigDecimal("-2"), new BigDecimal("10"), new BigDecimal("8"),
                new BigDecimal("18.0000"), "XS", "XS202603010001", "陈收银",
                LocalDateTime.of(2026, 3, 1, 12, 0), null);
        when(inventoryReportMapper.countInvRecordDrill(any())).thenReturn(1L);
        when(inventoryReportMapper.selectInvRecordDrill(any(), any())).thenAnswer(inv -> {
            Page<DrillInvRecordRow> page = new Page<>(1, 20, false);
            page.setRecords(List.of(row));
            return page;
        });

        DrillInvRecordRow masked = service.inventoryRecords(q, KEEPER).list().get(0);
        assertThat(masked.unitCost()).isNull();
        assertThat(masked.beforeQty()).isEqualByComparingTo("10");
        assertThat(masked.bizNo()).isEqualTo("XS202603010001");

        assertThat(service.inventoryRecords(q, MANAGER).list().get(0).unitCost()).isEqualByComparingTo("18.0000");
    }

    @Test
    @DisplayName("下钻损耗单据：无毛利权限时成本单价与金额不返回，数量与审核信息保留")
    void lossDrillMasksAmounts() {
        DrillLossQuery q = new DrillLossQuery(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "loss", 1L, 1, 20);
        DrillLossRow row = new DrillLossRow(7L, "BS202603010001", "LOSS", 1L, "测试商品",
                new BigDecimal("2"), new BigDecimal("18.00"), new BigDecimal("36.00"),
                "EXPIRED", "赵库管", "王店长", LocalDateTime.of(2026, 3, 1, 15, 0));
        when(inventoryReportMapper.countLossDrill(any())).thenReturn(1L);
        when(inventoryReportMapper.selectLossDrill(any(), any())).thenAnswer(inv -> {
            Page<DrillLossRow> page = new Page<>(1, 20, false);
            page.setRecords(List.of(row));
            return page;
        });

        DrillLossRow masked = service.lossDocuments(q, KEEPER).list().get(0);
        assertThat(masked.costPrice()).isNull();
        assertThat(masked.amount()).isNull();
        assertThat(masked.qty()).isEqualByComparingTo("2");
        assertThat(masked.auditorName()).isEqualTo("王店长");

        DrillLossRow full = service.lossDocuments(q, MANAGER).list().get(0);
        assertThat(full.costPrice()).isEqualByComparingTo("18.00");
        assertThat(full.amount()).isEqualByComparingTo("36.00");
    }
}
