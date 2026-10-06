package com.smms.inv.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.InventoryHealthRow;
import com.smms.inv.dto.InventoryHealthVO;
import com.smms.inv.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 库存体检服务（FR-INV-11、NFR-REL-08）：按流水重算每个商品的理论结存
 * 并与结存字段比对（BR-INV-08），输出差异清单；支持一键按流水修正结存，
 * 用于验证核心不变式与故障恢复。
 */
@Service
@RequiredArgsConstructor
public class InventoryHealthService {

    private static final long STORE_ID = 1L;

    private final InventoryMapper inventoryMapper;

    /** 体检：差异清单分页 + 参与校验商品数（口径：全部商品，两边一致不计差异） */
    public InventoryHealthVO check(int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        long checkedSkus = inventoryMapper.countHealthChecked();
        IPage<InventoryHealthRow> result =
                inventoryMapper.selectHealthDiffPage(new Page<>(pageNo, pageSize), STORE_ID);
        return new InventoryHealthVO(result.getRecords(), result.getTotal(), checkedSkus);
    }

    /**
     * 一键按流水修正结存（FR-INV-11）：以流水为准则覆盖结存字段
     * quantity = Σchange_qty，成本金额按当前成本单价重算；
     * 结存行缺失时补行，成本取该商品最近一笔流水的成本单价兜底。
     */
    @Transactional
    public int fix() {
        List<InventoryHealthRow> diffs = inventoryMapper.selectHealthDiffList(STORE_ID);
        for (InventoryHealthRow diff : diffs) {
            BigDecimal costPrice = diff.costPrice();
            if (diff.rowMissing()) {
                inventoryMapper.ensureRow(diff.goodsId(), STORE_ID);
                BigDecimal latestCost = inventoryMapper.selectLatestRecordCost(diff.goodsId(), STORE_ID);
                costPrice = latestCost == null ? BigDecimal.ZERO : latestCost;
            }
            inventoryMapper.updateQuantityAndCost(diff.goodsId(), STORE_ID, diff.recordQty(), costPrice,
                    diff.recordQty().multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        }
        return diffs.size();
    }
}
