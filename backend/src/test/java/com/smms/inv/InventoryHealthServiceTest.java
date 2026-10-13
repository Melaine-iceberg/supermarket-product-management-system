package com.smms.inv;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.InventoryHealthRow;
import com.smms.inv.dto.InventoryHealthVO;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.service.InventoryHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存体检服务用例（FR-INV-11 / GAP-P2-12）：
 * 差异清单分页与商品总数、一键修正（补行 + 最近流水成本兜底 + 成本金额重算）、
 * 无差异时零修正。
 */
@ExtendWith(MockitoExtension.class)
class InventoryHealthServiceTest {

    @Mock
    private InventoryMapper inventoryMapper;

    private InventoryHealthService service() {
        return new InventoryHealthService(inventoryMapper);
    }

    private static InventoryHealthRow row(Long goodsId, String recordQty, String costPrice, boolean rowMissing) {
        return new InventoryHealthRow(goodsId, "SP" + goodsId, "商品" + goodsId, "分类",
                new BigDecimal(recordQty), new BigDecimal(recordQty), BigDecimal.ZERO,
                costPrice == null ? null : new BigDecimal(costPrice),
                LocalDateTime.now(), rowMissing);
    }

    @Test
    @DisplayName("体检：分页参数夹取（size≤100），返回差异清单、总数与参与校验商品数")
    void check_clampsPageSizeAndReturnsCounts() {
        when(inventoryMapper.countHealthChecked()).thenReturn(120L);
        Page<InventoryHealthRow> p = new Page<>(1, 100);
        p.setRecords(List.of(row(101L, "7", "2.5", false)));
        p.setTotal(3);
        when(inventoryMapper.selectHealthDiffPage(any(), eq(1L))).thenReturn(p);

        InventoryHealthVO vo = service().check(0, 999);

        assertThat(vo.checkedSkus()).isEqualTo(120);
        assertThat(vo.total()).isEqualTo(3);
        assertThat(vo.rows()).hasSize(1);
        ArgumentCaptor<Page<InventoryHealthRow>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(inventoryMapper).selectHealthDiffPage(pageCaptor.capture(), eq(1L));
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100);
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    }

    @Test
    @DisplayName("一键修正：缺失行先补行并以最近流水成本兜底，成本金额按 4 位成本重算 2 位金额")
    void fix_missingRowUsesLatestRecordCostFallback() {
        when(inventoryMapper.selectHealthDiffList(1L)).thenReturn(List.of(
                row(101L, "7", null, true),
                row(102L, "3", "1.2345", false)));
        when(inventoryMapper.selectLatestRecordCost(101L, 1L)).thenReturn(new BigDecimal("2.5"));

        int fixed = service().fix();

        assertThat(fixed).isEqualTo(2);
        verify(inventoryMapper).ensureRow(101L, 1L);
        verify(inventoryMapper).updateQuantityAndCost(101L, 1L, new BigDecimal("7"),
                new BigDecimal("2.5"), new BigDecimal("17.50"));
        // 已有行保留账面成本单价，不查最近流水
        verify(inventoryMapper, never()).ensureRow(102L, 1L);
        verify(inventoryMapper).updateQuantityAndCost(102L, 1L, new BigDecimal("3"),
                new BigDecimal("1.2345"), new BigDecimal("3.70"));
    }

    @Test
    @DisplayName("一键修正：缺失行且无任何流水时成本兜底为 0")
    void fix_missingRowWithoutRecordsUsesZeroCost() {
        when(inventoryMapper.selectHealthDiffList(1L)).thenReturn(List.of(row(103L, "5", null, true)));
        when(inventoryMapper.selectLatestRecordCost(103L, 1L)).thenReturn(null);

        service().fix();

        verify(inventoryMapper).updateQuantityAndCost(103L, 1L, new BigDecimal("5"),
                BigDecimal.ZERO, BigDecimal.ZERO.setScale(2));
    }

    @Test
    @DisplayName("无差异：返回 0 且不执行任何修正写入")
    void fix_noDiff_noWrites() {
        when(inventoryMapper.selectHealthDiffList(1L)).thenReturn(List.of());

        assertThat(service().fix()).isZero();
        verify(inventoryMapper, never()).ensureRow(any(), any());
        verify(inventoryMapper, never()).updateQuantityAndCost(any(), any(), any(), any(), any());
    }
}
